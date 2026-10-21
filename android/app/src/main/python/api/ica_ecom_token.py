"""aws-waf-token för ICA:s ecom-plattform (`handlaprivatkund.ica.se`).

ICA:s AWS-WAF challengar de prissatta endpointsen (product-pages/bop) efter ~8 TOKENLÖSA anrop per IP.
En giltig `aws-waf-token`-cookie (som en browser får när den löser challengens JS på butikssidan) lyfter
det taket HELT - verifierat 150/150 anrop utan tak, från VM:ens egen IP, ingen proxy. Tokenen är på
DOMÄN-nivå: samma token funkar för ALLA butiker, man byter bara `basePath=/stores/{acct}`-cookien per
butik (se `ica_ecom._cookie_header`). Därför behövs ingen IP-rotation - bara en token som förnyas.

Vi löser challengen med en headless chromium via Playwright (lazy import - tung, valfri dep). Tokenen
cachas per process och förnyas vid TTL-utgång eller reaktivt när crawlen ser en 202 (= token utgången).
"""
import asyncio
import logging
import time

from . import config

log = logging.getLogger("matbutiker")

# UA som challengen löstes med MÅSTE matcha UA:t httpx sedan skickar (WAF binder token till UA).
UA = "Mozilla/5.0 (X11; Linux x86_64; rv:120.0) Gecko/20100101 Firefox/120.0"

_TOKEN = {"jar": None, "ts": 0.0, "fail_ts": 0.0}
_lock = asyncio.Lock()
_SOLVE_COOLDOWN = 120  # efter ett misslyckat _solve: reta inte challengen igen på så här länge, så en
                       # browser/challenge som börjar fela mitt i en crawl inte utlöser en ny ~15s chromium-
                       # start per efterföljande 202 (butikerna felar då snabbt i st.f. att serialiseras)


async def _solve():
    """Headless chromium -> butikssidan -> WAF-challengen löses av sidans JS -> cookie-jar (utan basePath,
    som sätts per butik). Höjer om ingen aws-waf-token kom (challengen olöst)."""
    from playwright.async_api import async_playwright  # lazy: tung dep, bara ecom-crawlen behöver den
    acct = config.ICA_ECOM_TOKEN_STORE
    async with async_playwright() as p:
        br = await p.chromium.launch(headless=True)
        try:
            ctx = await br.new_context(user_agent=UA)
            pg = await ctx.new_page()
            await pg.goto(f"https://handlaprivatkund.ica.se/stores/{acct}",
                          wait_until="domcontentloaded", timeout=60000)
            await pg.wait_for_timeout(9000)  # låt challengens JS köra klart
            cookies = await ctx.cookies()
        finally:
            await br.close()
    jar = {c["name"]: c["value"] for c in cookies}
    if not jar.get("aws-waf-token"):
        raise RuntimeError("ica_ecom_token: ingen aws-waf-token efter challenge")
    jar.pop("basePath", None)  # basePath sätts per butik av crawlen
    return jar


async def get_cookies():
    """Returnera cachad cookie-jar; lös challengen på nytt om tom eller TTL-utgången. Färskheten kollas
    INNE i låset, så när flera crawl-workers samtidigt ser en utgången token löser bara EN om - de övriga
    ser den nyss uppdaterade tokenen och återanvänder (ingen browser-storm). Cirkelbrytare: om vi har en
    (gammal) jar men nyss misslyckats lösa, returnera den gamla under cooldown i st.f. att reta browsern
    varje anrop. Har vi INGEN jar (första primingen) bubblar felet upp -> crawl_all_ecom avbryter."""
    async with _lock:
        now = time.monotonic()
        if _TOKEN["jar"] and now - _TOKEN["ts"] < config.ICA_ECOM_TOKEN_TTL:
            return _TOKEN["jar"]  # färsk
        if _TOKEN["jar"] and now - _TOKEN["fail_ts"] < _SOLVE_COOLDOWN:
            return _TOKEN["jar"]  # TTL ute men nyligt solve-fel -> använd gammal jar, storma inte
        try:
            _TOKEN["jar"] = await _solve()
            _TOKEN["ts"] = time.monotonic()
            _TOKEN["fail_ts"] = 0.0
            log.info("ica_ecom_token: ny aws-waf-token hämtad (%d cookies)", len(_TOKEN["jar"]))
        except Exception:
            _TOKEN["fail_ts"] = time.monotonic()
            raise
        return _TOKEN["jar"]


async def refresh(stale_jar):
    """Reaktiv förnyelse när crawlen fått en 202 (token underkänd). Löser challengen BARA om cachen
    fortfarande håller den token som fallerade (`stale_jar`) - har en annan worker redan förnyat returneras
    den nya utan ny browser-start. Cirkelbrytare: efter ett misslyckat `_solve` retas det inte igen på
    `_SOLVE_COOLDOWN` sekunder (då returneras den gamla jaren och anropet får fela snabbt), så ett trasigt
    browser-steg mitt i crawlen inte utlöser en ~15s-start per efterföljande 202."""
    async with _lock:
        if _TOKEN["jar"] is not None and _TOKEN["jar"] is not stale_jar:
            return _TOKEN["jar"]  # någon annan worker hann redan förnya
        if time.monotonic() - _TOKEN["fail_ts"] < _SOLVE_COOLDOWN:
            return _TOKEN["jar"]  # nyligt solve-fel -> reta inte browsern igen än
        try:
            _TOKEN["jar"] = await _solve()
            _TOKEN["ts"] = time.monotonic()
            _TOKEN["fail_ts"] = 0.0
            log.info("ica_ecom_token: token förnyad efter 202 (%d cookies)", len(_TOKEN["jar"]))
        except Exception as e:  # noqa: BLE001 - ytas, inte sväljs: markera fel-fönster + logga orsaken
            _TOKEN["fail_ts"] = time.monotonic()
            log.error("ica_ecom_token: token-förnyelse misslyckades (%s) - cooldown %ds",
                      e, _SOLVE_COOLDOWN)
        return _TOKEN["jar"]


def seed_cookies(jar):
    """Testhook: injicera en färdig cookie-jar (t.ex. dumpad från en browser) så crawlen kan köras/testas
    utan att starta Playwright. Strippar basePath (sätts per butik)."""
    j = dict(jar)
    j.pop("basePath", None)
    _TOKEN["jar"] = j
    _TOKEN["ts"] = time.monotonic()
