from __future__ import annotations

import json
import math
import secrets
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import quote

import requests
import streamlit as st


st.set_page_config(page_title="Erbjudandekartan", page_icon="🛒", layout="wide")

CHAINS = {
    "ica": {"name": "ICA", "emoji": "🔴", "color": "#d94b45", "offers": True},
    "coop": {"name": "Coop", "emoji": "🟢", "color": "#288c5a", "offers": True},
    "willys": {"name": "Willys", "emoji": "🟡", "color": "#d39b18", "offers": True},
    "hemkop": {"name": "Hemköp", "emoji": "🟠", "color": "#d57432", "offers": True},
    "lidl": {"name": "Lidl", "emoji": "🔵", "color": "#4168a8", "offers": False},
    "citygross": {"name": "City Gross", "emoji": "🟣", "color": "#7756a6", "offers": False},
}
CHAIN_ALIASES = {"citygross": {"citygross", "city_gross"}}
ACCOUNT_FILE = Path(__file__).with_name(".fyndkartan_account.json")

_GEOLOCATION_COMPONENT = st.components.v2.component(
    "offer_map_geolocation",
    html="""
<div style="font-family: sans-serif;">
  <button id="request-location" type="button" style="padding: .55rem .85rem; cursor: pointer;">
    Använd min plats för att sortera närmast först
  </button>
  <span id="location-status" style="margin-left: .5rem;"></span>
</div>
""",
    js="""
export default function(component) {
  const { parentElement, data, setStateValue } = component;
  const button = parentElement.querySelector("#request-location");
  const status = parentElement.querySelector("#location-status");
  if (!button || !status) return;
  const position = data?.position;
  if (position?.latitude != null && position?.longitude != null) {
    status.textContent = "Plats hittad";
  } else if (position?.error) {
    status.textContent = position.error;
  }
  button.onclick = () => {
    if (!navigator.geolocation) {
      setStateValue("position", { error: "Platsdelning stöds inte i den här webbläsaren." });
      return;
    }
    status.textContent = "Hämtar plats…";
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => setStateValue("position", {
        latitude: coords.latitude,
        longitude: coords.longitude,
        captured_at: Date.now()
      }),
      (error) => setStateValue("position", {
        error: error.code === 1 ? "Platsåtkomst nekades." : "Kunde inte hämta platsen."
      }),
      { enableHighAccuracy: false, timeout: 15000, maximumAge: 300000 }
    );
  };
}
""",
)


def _saved_accounts() -> dict[str, dict[str, str]]:
    try:
        content = json.loads(ACCOUNT_FILE.read_text(encoding="utf-8"))
        return content if isinstance(content, dict) else {}
    except (FileNotFoundError, json.JSONDecodeError, OSError, TypeError):
        return {}


def _save_account(base: str, email: str, password: str) -> None:
    accounts = _saved_accounts()
    accounts[base] = {"email": email, "password": password}
    temp_file = ACCOUNT_FILE.with_suffix(".tmp")
    temp_file.write_text(json.dumps(accounts, indent=2), encoding="utf-8")
    temp_file.replace(ACCOUNT_FILE)


def authenticated_session(base: str) -> requests.Session:
    """Sign in with this app's saved account, or register it once on a new API."""
    accounts = _saved_accounts()
    saved = accounts.get(base)
    session = requests.Session()

    if saved:
        login = session.post(
            f"{base}/v1/auth/login",
            json=saved,
            timeout=20,
        )
        if login.ok:
            return session

        # If the local Fyndkartan database was reset, recreate the same account.
        registration = session.post(
            f"{base}/v1/auth/register",
            json=saved,
            timeout=20,
        )
        if registration.ok:
            return session
        if registration.status_code != 409:
            raise RuntimeError(f"Fyndkartan kunde inte logga in eller återskapa kontot: {registration.text}")

    # Create a random account only once for this API address. The reserved example.com
    # domain avoids using a real person's mailbox; Fyndkartan only validates email shape.
    for _ in range(3):
        credentials = {
            "email": f"erbjudandekartan-{uuid.uuid4().hex}@example.com",
            "password": secrets.token_urlsafe(32),
        }
        registration = session.post(
            f"{base}/v1/auth/register",
            json=credentials,
            timeout=20,
        )
        if registration.ok:
            _save_account(base, credentials["email"], credentials["password"])
            return session
        if registration.status_code == 409:
            continue
        raise RuntimeError(f"Kunde inte skapa automatiskt Fyndkartan-konto: {registration.text}")
    raise RuntimeError("Fyndkartan avvisade flera slumpade e-postadresser. Kontrollera API-servern.")

def api_get(path: str, *, params: dict[str, Any] | None = None) -> Any:
    base = st.session_state.api_base.rstrip("/")
    session: requests.Session | None = st.session_state.api_session
    if session is None or st.session_state.api_session_base != base:
        session = authenticated_session(base)
        st.session_state.api_session = session
        st.session_state.api_session_base = base
    response = session.get(f"{base}{path}", params=params, timeout=25)
    if response.status_code == 401:
        session = authenticated_session(base)
        st.session_state.api_session = session
        st.session_state.api_session_base = base
        response = session.get(f"{base}{path}", params=params, timeout=25)
    response.raise_for_status()
    return response.json()


def offer_category(offer: dict[str, Any]) -> str:
    category = offer.get("category")
    if category and str(category).strip():
        return str(category).strip()

    # Some offer sources omit the category. Recognize a few common products from
    # their names so they can still be grouped usefully without guessing broadly.
    title = " ".join(str(offer.get(key) or "") for key in ("name", "title", "product_name")).casefold()
    if any(word in title for word in ("bröd", "limpa", "fralla", "knäckebröd", "toast", "baguette")):
        return "Bröd"
    if any(word in title for word in ("läsk", "juice", "vatten", "saft", "öl", "mjölk", "dryck", "kaffe", "te")):
        return "Dryck"
    return "Övrigt"


def distance_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """Great-circle distance between two coordinates, in kilometers."""
    radius = 6371.0
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lng2 - lng1)
    value = math.sin(delta_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2) ** 2
    return radius * 2 * math.atan2(math.sqrt(value), math.sqrt(1 - value))


for key, default in (("api_base", "http://localhost:8700"), ("api_session", None), ("api_session_base", None), ("stores", None), ("api_error", None)):
    if key not in st.session_state:
        st.session_state[key] = default

with st.sidebar:
    st.markdown("### ⚙️ Anslutning")
    st.caption("Erbjudandekartan hämtar data från en Fyndkartan-server.")
    api_base = st.text_input("Fyndkartan API", value=st.session_state.api_base, help="Exempelvis http://localhost:8700")
    st.caption("Appen skapar och sparar automatiskt ett slumpat konto lokalt på datorn.")
    if st.button("Anslut och hämta butiker", use_container_width=True, type="primary"):
        try:
            st.session_state.api_base = api_base.strip()
            st.session_state.api_session = authenticated_session(st.session_state.api_base.rstrip("/"))
            st.session_state.api_session_base = st.session_state.api_base.rstrip("/")
            st.session_state.stores = None
            st.session_state.api_error = None
            st.rerun()
        except Exception as exc:
            st.session_state.api_error = str(exc)
    st.divider()
    st.caption("Fyndkartan körs som en separat API-server. Standardadressen är localhost:8700.")


@st.dialog("Erbjudanden", width="large")
def show_chain(chain: str, available_stores: list[dict[str, Any]]) -> None:
    config = CHAINS[chain]
    st.markdown(f"### {config['emoji']} {config['name']}")
    city_options = sorted({(s.get("address") or {}).get("city", "") for s in available_stores if (s.get("address") or {}).get("city")})
    city = st.selectbox("Stad", ["Alla städer", *city_options], key=f"city_{chain}")
    candidates = available_stores if city == "Alla städer" else [s for s in available_stores if (s.get("address") or {}).get("city") == city]
    labels = [
        f"{s.get('name', 'Butik')} · {((s.get('address') or {}).get('city') or '')}"
        + (f" · {s['distance_km']:.1f} km" if s.get("distance_km") is not None else "")
        for s in candidates
    ]
    selected_label = st.selectbox("Välj butik", labels, key=f"store_{chain}") if labels else None
    if not candidates or selected_label is None:
        st.info("Inga butiker hittades med det valet.")
        return
    store = candidates[labels.index(selected_label)]
    address = store.get("address") or {}
    st.caption(" · ".join(part for part in [address.get("street"), address.get("postal_code"), address.get("city")] if part))
    if not config["offers"]:
        st.info("Fyndkartan har ännu inga hämtade erbjudanden för den här kedjan.")
        if (store.get("links") or {}).get("offers"):
            st.link_button("Öppna butikens reklamblad", store["links"]["offers"])
        return
    with st.spinner("Hämtar aktuella erbjudanden…"):
        try:
            store_chain = str(store.get("chain") or chain)
            result = api_get(f"/v1/stores/{quote(store_chain)}/{quote(str(store.get('store_id', '')), safe='')}/offers")
        except Exception as exc:
            st.error(f"Kunde inte hämta erbjudanden: {exc}")
            return
    offers = result if isinstance(result, list) else result.get("offers", [])
    note = result.get("note") if isinstance(result, dict) else None
    if note:
        st.info(note)
    if not offers:
        st.info("Inga erbjudanden hittades för den här butiken just nu.")
        return
    categories = sorted({offer_category(offer) for offer in offers}, key=str.casefold)
    selected_category = st.selectbox(
        "Filtrera efter kategori",
        ["Alla kategorier", *categories],
        key=f"offer_category_{chain}_{store.get('store_id', '')}",
    )
    visible_offers = [offer for offer in offers if selected_category == "Alla kategorier" or offer_category(offer) == selected_category]
    st.markdown(f"**Visar {len(visible_offers)} av {len(offers)} erbjudanden**")
    for offer in visible_offers:
        title = offer.get("name") or offer.get("title") or offer.get("product_name") or "Erbjudande"
        price = offer.get("price")
        unit = offer.get("unit") or offer.get("price_unit") or "kr"
        price_text = offer.get("price_text")
        package = offer.get("package_size") or offer.get("package")
        if not package and offer.get("package_value") is not None:
            package = f"{offer['package_value']:g} {offer.get('package_unit') or ''}".strip()
            if offer.get("package_approx"):
                package = f"ca {package}"
        comparison_value = offer.get("comparison_value")
        comparison_unit = offer.get("comparison_unit")
        deal_type = offer.get("deal_type")
        multibuy_qty = offer.get("multibuy_qty")
        dates = []
        for field, prefix in (("valid_from", "Från"), ("valid_to", "Till"), ("start_date", "Från"), ("end_date", "Till")):
            value = offer.get(field)
            if value and not any(x.startswith(prefix) for x in dates):
                try:
                    parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
                    value = parsed.strftime("%d %b %Y")
                except ValueError:
                    pass
                dates.append(f"{prefix} {value}")
        if not dates and offer.get("validity"):
            dates.append(str(offer["validity"]))
        with st.container(border=True):
            image_url = offer.get("image")
            if image_url:
                st.image(image_url, alt=title, width=120)
            left, right = st.columns([4, 1], vertical_alignment="center")
            with left:
                st.markdown(f"**{title}**")
                if offer.get("brand"):
                    st.caption(offer["brand"])
                st.caption(f"Kategori: {offer_category(offer)}")
                if package:
                    st.write(f"Förpackning: **{package}**")
                else:
                    st.caption("Förpackningsstorlek saknas i erbjudandedatan.")
                if comparison_value is not None and comparison_unit:
                    approximate = " (beräknat, ungefärligt)" if offer.get("comparison_derived") else ""
                    st.write(f"Jämförpris: **{comparison_value:g} kr/{comparison_unit}**{approximate}")
                if price_text:
                    st.write(f"Erbjudande: **{price_text}**")
                elif deal_type == "multibuy" and multibuy_qty and price is not None:
                    st.write(f"Flerpack: **{multibuy_qty} st för {price:g} kr** ({price / multibuy_qty:g} kr/st)")
                elif deal_type == "by_weight":
                    st.write("Priset gäller efter vikt.")
                if offer.get("member_price"):
                    st.badge("Medlemspris", color="orange")
                origin = offer.get("origin")
                if origin:
                    st.caption(f"Ursprung: {', '.join(origin) if isinstance(origin, list) else origin}")
                st.caption(" · ".join(dates) if dates else "Giltighetstid saknas")
            with right:
                if price is not None:
                    st.metric("Erbjudandepris", f"{price:g} {unit}".strip(), border=False)
        st.divider()


st.caption("VECKANS MATFYND")
st.title("Erbjudandekartan")
st.write("Hitta veckans erbjudanden hos matbutiker nära dig. Välj en kedja och butik för att se aktuella priser och giltighetstid.")

if st.session_state.api_error:
    st.error(st.session_state.api_error)

try:
    active_base = st.session_state.api_base.rstrip("/")
    if st.session_state.api_session is None or st.session_state.api_session_base != active_base:
        st.session_state.api_session = authenticated_session(active_base)
        st.session_state.api_session_base = active_base
    if st.session_state.stores is None:
        payload = api_get("/v1/stores")
        st.session_state.stores = payload.get("stores", []) if isinstance(payload, dict) else payload
    stores = st.session_state.stores or []
except requests.RequestException as exc:
    stores = []
    st.warning(f"Kan inte nå Fyndkartan API på {st.session_state.api_base}. Starta API-servern och anslut igen. ({exc})")
except PermissionError as exc:
    stores = []
    st.info(str(exc))
except RuntimeError as exc:
    stores = []
    st.error(str(exc))

if stores:
    city_filter = st.text_input("⌕  Filtrera butiker på stad", placeholder="Skriv till exempel Uppsala")
    if city_filter.strip():
        stores = [s for s in stores if city_filter.casefold() in ((s.get("address") or {}).get("city") or "").casefold()]
    geo_key = "offer_map_geolocation_state"
    geo_state = st.session_state.get(geo_key, {})
    geo_result = _GEOLOCATION_COMPONENT(
        key=geo_key,
        data={"position": geo_state.get("position")},
        on_position_change=lambda: None,
    )
    position = getattr(geo_result, "position", None) or geo_state.get("position") or {}
    user_lat = position.get("latitude") if isinstance(position, dict) else None
    user_lng = position.get("longitude") if isinstance(position, dict) else None
    if user_lat is not None and user_lng is not None:
        for store in stores:
            location = store.get("location") or {}
            if location.get("lat") is not None and location.get("lng") is not None:
                store["distance_km"] = distance_km(user_lat, user_lng, location["lat"], location["lng"])
            else:
                store["distance_km"] = None
        stores.sort(key=lambda store: store["distance_km"] if store["distance_km"] is not None else float("inf"))
        st.caption("Butikerna sorteras närmast först. Avståndet är ungefärligt fågelvägen.")
    elif isinstance(position, dict) and position.get("error"):
        st.caption(f"{position['error']} Du kan tillåta platsåtkomst i webbläsaren och försöka igen.")
    st.subheader("Välj matbutik")
    for row in range(0, len(CHAINS), 3):
        cols = st.columns(3, gap="medium")
        for col, (chain, config) in zip(cols, list(CHAINS.items())[row:row + 3]):
            accepted_codes = CHAIN_ALIASES.get(chain, {chain})
            chain_stores = [s for s in stores if s.get("chain") in accepted_codes]
            with col:
                with st.container(border=True):
                    st.markdown(f"### {config['emoji']} {config['name']}")
                    st.caption(f"{len(chain_stores)} butiker i urvalet")
                    nearest = next((store["distance_km"] for store in chain_stores if store.get("distance_km") is not None), None)
                    if nearest is not None:
                        st.caption(f"Närmaste: {nearest:.1f} km")
                    st.badge("Erbjudanden tillgängliga" if config["offers"] else "Erbjudanden saknas i API:t", color="green" if config["offers"] else "gray")
                if st.button(f"Visa {config['name']} →", key=f"open_{chain}", width="stretch", disabled=not chain_stores):
                    show_chain(chain, chain_stores)
else:
    with st.container(border=True):
        st.subheader("Anslut till Fyndkartan för att börja")
        st.write("Starta Fyndkartans API-server och klicka sedan på “Anslut och hämta butiker” i sidopanelen. Erbjudanden stöds enligt projektets API för ICA, Coop, Willys och Hemköp. Lidl och City Gross visas också, men Fyndkartan har ännu inte erbjudandedata för dem.")

st.divider()
st.caption("Data från Fyndkartan · Erbjudanden och giltighetstid visas i den mån uppgifterna finns i källan.")
