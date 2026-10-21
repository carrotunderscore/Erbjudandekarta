"""ICA e-handels-crawler (handlaprivatkund.ica.se) - per-butik-HYLLPRIS via den nya ecom-plattformen.

Bakgrund: ICA tog bort pris ur globalsearch/quicksearch (~2026-06-16), så per-butik-pris (Steg 6) för ICA
måste hämtas här i stället. Se `ICA-ECOM-API.md` för endpoint-referensen. Körs PARALLELLT med den gamla
quicksearch-baserade per-butik-crawlen (`store_crawl._ica_fetch_store`, produkt-närvaro/kategori) tills vi
litar på ecom-täckningen.

Metod (token-baserad, async-httpx, VM:ens egen IP - ingen proxy/rotation):
  0. Lös ICA:s AWS-WAF-challenge EN gång via headless browser -> aws-waf-token (`ica_ecom_token`). Tokenen
     lyfter det tokenlösa taket (~8 anrop/IP -> 202) HELT och är domän-nivå (funkar för alla butiker).
  1. GET /api/webproductpagews/v1/categories (RELATIV path + basePath=/stores/{acct}-cookie) -> kategoriträd.
  2. Per löv: GET /api/webproductpagews/v6/product-pages?categoryId=X (hög maxPageSize -> undvik den
     statslöst-trasiga pageTokenen) -> produkter med pris/jämförpris/erbjudanden/retailerProductId.
  3. Mappa retailerProductId (== quicksearchens consumerItemId) -> gtin (bygg-lager, ej i denna fil).

Svaren bär INGEN gtin/EAN - bara retailerProductId. EAN-bryggan görs av anroparen.
"""
import asyncio
import logging
from datetime import datetime, timezone

from . import apilog, config, database, ica_ecom_token

log = logging.getLogger("matbutiker")


def _now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# Anropen går mot den RELATIVA /api-pathen (utan /stores/{acct}) - butiken styrs av basePath-cookien.
# Med full /stores/{acct}/api-path OCH basePath-cookie dubbel-routas requesten -> HTTP 405. UA måste matcha
# browsern som löste challengen (WAF binder token till UA), därför samma UA som ica_ecom_token.
_API = "https://handlaprivatkund.ica.se/api"
_UA = ica_ecom_token.UA
_HDRS = {"User-Agent": _UA, "Accept": "application/json; charset=utf-8",
         "Accept-Language": "sv-SE,sv;q=0.9,en;q=0.7", "ecom-request-source": "web"}
_PAGE_SIZE = 1000       # maxPageSize (servern capar dock hårt vid ~991 dekorerade/svar)
_BROWSE_MAX = 950       # browse-tröskel: descenda noder > detta. Servern capar vid ~991, så en nod med
                        # 992-1000 produkter skulle tyst huggas av på EN sida -> descenda i st.f. (marginal).
_PAGE_PACE = 0.15       # paus mellan kategori-anrop (med token finns ingen budget; håll takten sansad)
_STORE_V1 = "https://handla.ica.se/api/store/v1"  # e-handelsbutik-lista (ingen WAF) -> filtrera bort
                                                  # icke-ecom-butiker (302:ar till chooseStore)


def _cookie_header(jar, acct):
    """Bygg Cookie-headern för EN butik: token-jaren + basePath=/stores/{acct}. basePath sätts per anrop
    (ej i httpx cookie-jar) så samtidiga butiker inte krockar om en delad jar."""
    parts = [f"{k}={v}" for k, v in jar.items()]
    parts.append(f"basePath=/stores/{acct}")
    return "; ".join(parts)


async def ecom_accounts(client):
    """Set med accountId för de ICA-butiker som FAKTISKT har e-handel (onlineVersion>=2), ur store/v1
    (ingen WAF). ~348 av 1288 ICA-butiker; resten är fysiska-bara och 302:ar -> slöseri om de crawlas."""
    r = await client.get(_STORE_V1, params={"groupby": "citygroup", "customerType": "B2C"},
                         headers={"User-Agent": _UA, "Accept": "application/json"}, timeout=30)
    r.raise_for_status()
    out = set()

    def _walk(o):
        if isinstance(o, dict):
            if "accountId" in o:
                ov = str(o.get("onlineVersion") or "")
                if ov.isdigit() and int(ov) >= 2:
                    out.add(str(o["accountId"]))
            else:
                for v in o.values():
                    _walk(v)
        elif isinstance(o, list):
            for v in o:
                _walk(v)
    _walk(r.json())
    return out


def _num(v):
    """{'amount': '27.35'} eller '27.35' -> float, annars None."""
    if isinstance(v, dict):
        v = v.get("amount")
    try:
        return float(str(v).replace(",", ".")) if v is not None else None
    except (ValueError, TypeError):
        return None


async def _get_json(client, path, params, acct, retries=3):
    """GET {_API}/{path} med butikens basePath-cookie + aws-waf-token -> JSON, retry/backoff. En 202/403
    betyder utgången/saknad token (challenge) -> förnya token EN gång och reta; övriga fel backoff-retas."""
    last, refreshed = None, False
    for attempt in range(retries + 1):
        jar = await ica_ecom_token.get_cookies()
        try:
            r = await client.get(f"{_API}/{path}", params=params,
                                  headers={**_HDRS, "Cookie": _cookie_header(jar, acct)}, timeout=30)
            if r.status_code == 200 and "json" in r.headers.get("content-type", ""):
                return r.json()
            last = f"HTTP {r.status_code} {r.headers.get('content-type', '')[:20]}"
            if r.status_code in (202, 403) and not refreshed:  # token underkänd -> förnya en gång och reta
                refreshed = True
                await ica_ecom_token.refresh(jar)  # identitets-baserad: löser bara om ingen redan förnyat
                continue
        except Exception as e:  # noqa: BLE001 (transport/JSON)
            last = f"{type(e).__name__}"
        if attempt < retries:
            await asyncio.sleep(1.0 + attempt * 1.5)  # backoff: 1s, 2.5s, 4s
    raise RuntimeError(f"ica_ecom _get_json misslyckades ({last}): {path[:40]}")


async def fetch_categories(client, acct):
    """Hämta butikens kategoriträd MED produkt-antal (`decoration=true`; `false` ger productCount=0).
    Returnerar topp-noderna (rekursiv `childCategories` behålls) - walken nedan väljer browse-nivå."""
    return await _get_json(client, "webproductpagews/v1/categories",
                           {"decoration": "true", "categoryDepth": 5}, acct)


def _browse_plan(nodes, page_size):
    """Rekursivt: välj den kategori-nivå att browsa där productCount ryms i EN sida (pageToken paginerar
    inte statslöst). En topp-browse returnerar HELA subträdet, så vi browsar den grövsta nod som får plats;
    bara grenar > page_size descendas. Yield:ar (categoryId, name, productCount)."""
    for n in nodes or []:
        pc = n.get("productCount") or 0
        if pc == 0:
            continue
        kids = n.get("childCategories")
        if pc <= page_size or not kids:
            yield n.get("categoryId"), n.get("name"), pc
        else:
            yield from _browse_plan(kids, page_size)


def _norm_product(p):
    """decoratedProduct -> normaliserad rad. retailerProductId (== consumerItemId) är EAN-bryggan.
    Bär både ordinariepris och ev. reapris (erbjudande)."""
    up = (p.get("unitPrice") or {})
    pup = (p.get("promoPrice") or {})
    promos = p.get("promotions") or []
    return {
        "retailer_product_id": str(p.get("retailerProductId") or ""),
        "name": p.get("name"),
        "brand": p.get("brand"),
        "package": p.get("packSizeDescription"),
        "price": _num(p.get("price")),
        "comparison_value": _num(up.get("price")),
        "comparison_unit": up.get("unitName"),
        "promo_price": _num(pup) or None,
        "promo_text": (promos[0].get("description") if promos else None),
        "required_qty": (promos[0].get("requiredProductQuantity") if promos else None),
        "category_path": p.get("categoryPath") or [],
        "image": ((p.get("image") or {}).get("src")),
        "available": p.get("available"),
    }


async def fetch_category_products(client, acct, category_id, page_size=None):
    """Hämta en kategoris produkter (dekorerade med pris). Hög maxPageSize -> en sida (se pageToken-gotcha).
    Returnerar lista av normaliserade rader (deduplicerade på retailer_product_id inom kategorin)."""
    size = page_size or _PAGE_SIZE
    params = {"categoryId": category_id, "maxPageSize": size, "maxProductsToDecorate": size,
              "tag": ["web", "category-item"]}
    data = await _get_json(client, "webproductpagews/v6/product-pages", params, acct)
    seen, out = set(), []
    for grp in data.get("productGroups") or []:
        for p in grp.get("decoratedProducts") or []:
            rid = str(p.get("retailerProductId") or "")
            if rid and rid not in seen:
                seen.add(rid)
                out.append(_norm_product(p))
    # pageToken-varning: om svaret cappades finns fler produkter vi inte får statslöst
    meta = data.get("metadata") or {}
    api_info = data.get("additionalPageInfo") or {}
    total = meta.get("totalHits") or api_info.get("totalProductCount")
    return out, total


async def fetch_store_products(client, acct, pace=None):
    """Async generator: yield:a (category_name, rows, capped) per browsad kategori för en butik.
    Browsar den grövsta kategori-nivå som ryms i en sida (se `_browse_plan`). `capped` = True om noden
    ändå gav färre än productCount (då finns produkter vi inte når statslöst)."""
    pace = _PAGE_PACE if pace is None else pace
    tree = await fetch_categories(client, acct)
    for cat_id, name, pc in _browse_plan(tree, _BROWSE_MAX):
        if not cat_id:
            continue
        try:
            rows, _total = await fetch_category_products(client, acct, cat_id)
        except Exception as e:  # noqa: BLE001
            log.warning("ica_ecom: kategori %s (%s) fel: %s", name, acct, e)
            raise
        yield name, rows, len(rows) < pc
        await asyncio.sleep(pace)


_STORE_PACE = 0.2   # paus efter varje butik (med token finns ingen budget; sansad takt räcker)

# Parallell-fasens live-state för konsolen (per-process, som övriga crawl-states).
ECOM_STATE = {"running": False, "done": 0, "total": 0, "stores_ok": 0, "products": 0, "mapped": 0,
              "changes": 0, "errors": 0, "current": None, "started_at": None, "finished_at": None,
              "last_error": None}


async def crawl_store(client, acct):
    """Crawla en ICA-butiks hela ecom-sortiment -> ica_ecom_prices (separat tabell). Mappar
    retailerProductId -> gtin via ica_cid_ean (quicksearch-byggd). Returnerar stats-dict."""
    products, ncat, capped = {}, 0, 0
    async for _name, rows, cap in fetch_store_products(client, acct):
        ncat += 1
        capped += 1 if cap else 0
        for r in rows:
            products[r["retailer_product_id"]] = r  # dedup över kategorier
    eanmap = await asyncio.to_thread(database.ica_ean_for_cids, list(products))
    for rid, r in products.items():
        r["ean"] = eanmap.get(rid)
    written, changes = await asyncio.to_thread(database.upsert_ica_ecom_prices, acct, list(products.values()))
    return {"products": len(products), "categories": ncat, "capped": capped, "written": written,
            "changes": changes,
            "priced": sum(1 for r in products.values() if r["price"] is not None),
            "mapped": sum(1 for r in products.values() if r["ean"]),
            "promos": sum(1 for r in products.values() if r["promo_price"])}


async def crawl_all_ecom(cap=None, max_age_hours=None, concurrency=None):
    """Kör ecom-pris-crawlen för de enabled+frågbara ICA-butikerna (bunden samtidighet). Skriver till
    ica_ecom_prices + en crawl_runs-rad (kind='ecom_prices'). Parallellt med quicksearch-crawlen; rör
    inte dess rotation/last_crawled. `max_age_hours=None/0` = alla valda butiker."""
    st = ECOM_STATE
    if st["running"]:
        return {"status": "running"}
    st.update(running=True, done=0, total=0, stores_ok=0, products=0, mapped=0, changes=0, errors=0,
              current=None, last_error=None, started_at=_now(), finished_at=None)
    sem = asyncio.Semaphore(concurrency or config.ICA_ECOM_CONC)
    try:
        # Prima aws-waf-token en gång upp front. Misslyckas browsern/challengen kan INGEN butik crawlas ->
        # markera som fel (annars skriver finally en missvisande "ok, 0/0" och crawlen ser tyst lyckad ut).
        try:
            await ica_ecom_token.get_cookies()
        except Exception as e:  # noqa: BLE001
            st["errors"] += 1
            st["last_error"] = f"token-priming: {type(e).__name__}: {e}"[:200]
            log.error("ica_ecom: token-priming misslyckades, avbryter crawlen: %s", e)
            return st  # finally registrerar ok_med_fel med felet
        async with apilog.make_client(follow_redirects=True) as client:
            # Filtrera bort icke-ecom-butiker (73% av ICA-butikerna 302:ar till chooseStore -> bränner
            # WAF-budget + räknas som falska fel). Rotation FÖRST, sedan ecom-filter, sedan cap.
            try:
                ecom = await ecom_accounts(client)
            except Exception as e:  # noqa: BLE001 (store/v1 nere -> crawla ofiltrerat hellre än inget)
                log.warning("ica_ecom: ecom_accounts misslyckades (%s) - crawlar ofiltrerat", e)
                ecom = None
            allq = database.ica_ecom_stores_to_crawl(cap=None, max_age_hours=max_age_hours)
            queue = [a for a in allq if ecom is None or a in ecom]
            if cap:
                queue = queue[:cap]
            st["total"] = len(queue)

            async def one(acct):
                async with sem:
                    try:
                        r = await crawl_store(client, acct)
                        st["stores_ok"] += 1
                        st["products"] += r["products"]
                        st["mapped"] += r["mapped"]
                        st["changes"] += r["changes"]
                        st["current"] = f"ICA {acct}: {r['products']} prod ({r['mapped']} mappade)"
                    except Exception as e:  # noqa: BLE001
                        st["errors"] += 1
                        st["last_error"] = f"{acct}: {type(e).__name__}: {e}"[:200]
                        log.warning("ica_ecom: butik %s fel: %s", acct, e)
                    finally:
                        st["done"] += 1
                    await asyncio.sleep(_STORE_PACE)
            await asyncio.gather(*(one(a) for a in queue), return_exceptions=True)
    finally:
        st.update(running=False, finished_at=_now(), current=None)
        await asyncio.to_thread(
            database.record_crawl_run, "ecom_prices", "ica", started=st["started_at"],
            finished=st["finished_at"], status=("ok_med_fel" if st["errors"] else "ok"),
            rows=st["products"], changed=st["changes"], errors=st["errors"],
            stores_ok=st["stores_ok"], stores_total=st["total"], last_error=st["last_error"])
    return st
