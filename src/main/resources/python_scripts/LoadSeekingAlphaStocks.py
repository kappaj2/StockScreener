#!/usr/bin/env python3
"""
Post symbols extracted from TradingView watchlist 'SeekingAlphaRecommendations'
section '2026_05_10' to the local Stock Watch addStock endpoint.

Tweak ENDPOINT, BODY_BUILDER, and STRIP_EXCHANGE below if the API expects a
different shape. Each request's status and response body are printed so we can
see exactly what the server says.
"""

import sys
import urllib.error
import urllib.parse
import urllib.request

# ---- Config you may want to tweak ------------------------------------------
ENDPOINT = "http://127.0.0.1:8085/api/v1/watch"  # POST with query params
LIST_NAME = "SeekingAlphaRecommendations"
STRIP_EXCHANGE = True   # True -> "ACHV"; False -> "NASDAQ:ACHV"
TIMEOUT_SEC = 10
# ----------------------------------------------------------------------------

SYMBOLS = [
    "NASDAQ:ACHV", "NYSE:UHS",   "NASDAQ:REGN", "NYSE:ECC",   "NASDAQ:ATEX",
    "NASDAQ:CHTR", "NYSE:RBLX",  "NASDAQ:ZENA", "NASDAQ:MANH","NASDAQ:MRVL",
    "NYSE:CAG",    "NYSE:SYY",   "NYSE:GPC",    "NYSE:PLOW",  "NYSE:GWW",
    "NASDAQ:ALTO", "NYSE:SW",    "NYSE:DLNG",   "NYSE:VICI",  "NYSE:O",
]

def normalize(sym: str) -> str:
    return sym.split(":", 1)[1] if STRIP_EXCHANGE and ":" in sym else sym

def post(symbol: str) -> tuple[int, str]:
    qs = urllib.parse.urlencode({"symbol": symbol, "sourceList": LIST_NAME})
    url = f"{ENDPOINT}?{qs}"
    req = urllib.request.Request(
        url,
        data=b"",  # POST with empty body; params live in the query string
        method="POST",
        headers={"Accept": "application/json", "Content-Length": "0"},
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SEC) as r:
            return r.status, r.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except Exception as e:
        return 0, f"ERROR: {e!r}"

def main() -> int:
    print(f"POST -> {ENDPOINT}")
    print(f"List : {LIST_NAME}")
    print(f"Count: {len(SYMBOLS)}\n")
    ok = err = 0
    for raw in SYMBOLS:
        sym = normalize(raw)
        status, body = post(sym)
        flag = "OK " if 200 <= status < 300 else "FAIL"
        print(f"[{flag}] {status:>3}  {sym:<6}  {body[:200]}")
        if 200 <= status < 300: ok += 1
        else: err += 1
    print(f"\nDone. ok={ok} fail={err}")
    return 0 if err == 0 else 1

if __name__ == "__main__":
    sys.exit(main())