#!/usr/bin/env python3
"""
backfill_daily_bars.py
======================
Backfills daily_bar_summary from Polygon's daily-aggregate endpoint.

Usage
-----
  # Backfill all symbols currently in daily_bar_summary
  python3 backfill_daily_bars.py

  # Backfill a specific list
  python3 backfill_daily_bars.py --symbols XLB URA MLPX COPX COPP

  # Override the start date (default: 2025-08-01)
  python3 backfill_daily_bars.py --from 2025-01-01

  # Dry-run: print what would be inserted without touching the DB
  python3 backfill_daily_bars.py --dry-run

Environment variables
---------------------
  MASSIVE_API_KEY   Polygon.io API key (required)
  DB_HOST           MariaDB host          (default: 127.0.0.1)
  DB_PORT           MariaDB port          (default: 3307)
  DB_USER           MariaDB user          (default: stock)
  DB_PASSWORD       MariaDB password      (default: stock)
  DB_NAME           MariaDB schema        (default: stock)
"""

import argparse
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import json
from datetime import date, datetime
from zoneinfo import ZoneInfo

try:
    import mysql.connector
except ImportError:
    print("ERROR: mysql-connector-python not installed.")
    print("       Run: pip install mysql-connector-python --break-system-packages")
    sys.exit(1)

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

POLYGON_BASE = "https://api.polygon.io"
DEFAULT_FROM = "2025-08-01"
ET           = ZoneInfo("America/New_York")
MARKET_OPEN  = "09:30:00.000"
MARKET_CLOSE = "16:00:00.000"
REQUEST_DELAY = 0.25   # seconds between Polygon calls
MAX_RETRIES   = 3


# ---------------------------------------------------------------------------
# Polygon helpers
# ---------------------------------------------------------------------------

def polygon_daily_bars(symbol: str, from_date: str, to_date: str, api_key: str) -> list:
    """Fetch daily aggregates from Polygon, handling pagination."""
    all_results = []
    url = (
        f"{POLYGON_BASE}/v2/aggs/ticker/{urllib.parse.quote(symbol)}/range/1/day"
        f"/{from_date}/{to_date}"
        f"?adjusted=true&sort=asc&limit=50000&apiKey={api_key}"
    )

    while url:
        for attempt in range(1, MAX_RETRIES + 1):
            try:
                req = urllib.request.Request(url, headers={"Accept": "application/json"})
                with urllib.request.urlopen(req, timeout=30) as resp:
                    payload = json.loads(resp.read().decode("utf-8"))
                break
            except urllib.error.HTTPError as e:
                if e.code == 429:
                    wait = 60 * attempt
                    print(f"    Rate-limited, waiting {wait}s ...")
                    time.sleep(wait)
                elif e.code == 404:
                    return []
                else:
                    print(f"    HTTP {e.code} on attempt {attempt}: {e.reason}")
                    if attempt == MAX_RETRIES:
                        raise
                    time.sleep(5 * attempt)
            except Exception as exc:
                print(f"    Request error on attempt {attempt}: {exc}")
                if attempt == MAX_RETRIES:
                    raise
                time.sleep(5 * attempt)

        results = payload.get("results") or []
        all_results.extend(results)

        url = payload.get("next_url")
        if url:
            sep = "&" if "?" in url else "?"
            url = f"{url}{sep}apiKey={api_key}"

    return all_results


def bar_to_row(symbol: str, bar: dict):
    """Convert a Polygon daily aggregate dict to a DB row dict."""
    ts_ms = bar.get("t")
    if ts_ms is None:
        return None

    trade_dt = datetime.fromtimestamp(ts_ms / 1000, tz=ET).date()

    if trade_dt.weekday() >= 5:   # skip weekends (guard)
        return None

    return {
        "symbol":      symbol,
        "trade_date":  trade_dt.isoformat(),
        "first_ts":    f"{trade_dt} {MARKET_OPEN}",
        "last_ts":     f"{trade_dt} {MARKET_CLOSE}",
        "open_price":  bar.get("o"),
        "high_price":  bar.get("h"),
        "low_price":   bar.get("l"),
        "close_price": bar.get("c"),
        "volume":      int(bar.get("v", 0)),
        "bar_count":   int(bar.get("n", 0)),
    }


# ---------------------------------------------------------------------------
# DB helpers
# ---------------------------------------------------------------------------

UPSERT_SQL = """
INSERT INTO daily_bar_summary
    (symbol, trade_date, first_ts, last_ts,
     open_price, high_price, low_price, close_price,
     volume, bar_count, created_at)
VALUES
    (%(symbol)s, %(trade_date)s, %(first_ts)s, %(last_ts)s,
     %(open_price)s, %(high_price)s, %(low_price)s, %(close_price)s,
     %(volume)s, %(bar_count)s, NOW(3))
ON DUPLICATE KEY UPDATE
    first_ts    = VALUES(first_ts),
    last_ts     = VALUES(last_ts),
    open_price  = VALUES(open_price),
    high_price  = VALUES(high_price),
    low_price   = VALUES(low_price),
    close_price = VALUES(close_price),
    volume      = VALUES(volume),
    bar_count   = VALUES(bar_count),
    created_at  = VALUES(created_at)
"""


def get_db_connection():
    return mysql.connector.connect(
        host=os.environ.get("DB_HOST", "127.0.0.1"),
        port=int(os.environ.get("DB_PORT", "3307")),
        user=os.environ.get("DB_USER", "stock"),
        password=os.environ.get("DB_PASSWORD", "stock"),
        database=os.environ.get("DB_NAME", "stock"),
    )


def get_symbols_from_db(conn) -> list:
    cursor = conn.cursor()
    cursor.execute("SELECT DISTINCT symbol FROM daily_bar_summary ORDER BY symbol")
    rows = cursor.fetchall()
    cursor.close()
    return [r[0] for r in rows]


def get_existing_dates(conn, symbol: str, from_date: str) -> set:
    cursor = conn.cursor()
    cursor.execute(
        "SELECT trade_date FROM daily_bar_summary WHERE symbol = %s AND trade_date >= %s",
        (symbol, from_date),
    )
    rows = cursor.fetchall()
    cursor.close()
    return {str(r[0]) for r in rows}


def upsert_rows(conn, rows: list, dry_run: bool) -> int:
    if not rows:
        return 0
    if dry_run:
        for r in rows:
            print(f"      [DRY-RUN] {r['symbol']} {r['trade_date']}  "
                  f"O={r['open_price']} H={r['high_price']} "
                  f"L={r['low_price']} C={r['close_price']}  vol={r['volume']}")
        return len(rows)
    cursor = conn.cursor()
    cursor.executemany(UPSERT_SQL, rows)
    conn.commit()
    n = cursor.rowcount
    cursor.close()
    return n


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Backfill daily_bar_summary from Polygon")
    parser.add_argument("--symbols", nargs="+", metavar="SYM",
                        help="Symbols to backfill (default: all in daily_bar_summary)")
    parser.add_argument("--from", dest="from_date", default=DEFAULT_FROM,
                        help=f"Start date YYYY-MM-DD (default: {DEFAULT_FROM})")
    parser.add_argument("--to", dest="to_date", default=date.today().isoformat(),
                        help="End date YYYY-MM-DD (default: today)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Print rows without writing to DB")
    parser.add_argument("--no-skip-existing", dest="skip_existing",
                        action="store_false", default=True,
                        help="Overwrite rows that already exist in the DB")
    args = parser.parse_args()

    api_key = os.environ.get("MASSIVE_API_KEY", "").strip()
    if not api_key:
        print("ERROR: MASSIVE_API_KEY environment variable is not set.")
        sys.exit(1)

    conn = get_db_connection()
    print(f"DB connected  {os.environ.get('DB_HOST','127.0.0.1')}:{os.environ.get('DB_PORT','3307')}")

    symbols = args.symbols or get_symbols_from_db(conn)
    if not symbols:
        print("No symbols found. Pass --symbols or populate daily_bar_summary first.")
        sys.exit(0)

    print(f"Backfilling {len(symbols)} symbol(s)  [{args.from_date} -> {args.to_date}]")
    print(f"Dry-run: {args.dry_run}  |  Skip existing: {args.skip_existing}\n")

    total_upserted = 0
    total_skipped  = 0
    failed_symbols = []

    for idx, symbol in enumerate(symbols, 1):
        print(f"[{idx}/{len(symbols)}] {symbol} ...", end=" ", flush=True)

        try:
            existing = get_existing_dates(conn, symbol, args.from_date) if args.skip_existing else set()
            bars     = polygon_daily_bars(symbol, args.from_date, args.to_date, api_key)

            if not bars:
                print("no data from Polygon")
                continue

            rows = []
            skipped = 0
            for bar in bars:
                row = bar_to_row(symbol, bar)
                if row is None:
                    continue
                if args.skip_existing and row["trade_date"] in existing:
                    skipped += 1
                    continue
                rows.append(row)

            n = upsert_rows(conn, rows, args.dry_run)
            total_upserted += n
            total_skipped  += skipped
            print(f"{len(bars)} Polygon bars -> {n} upserted, {skipped} already existed")

        except Exception as exc:
            print(f"FAILED -- {exc}")
            failed_symbols.append(symbol)

        time.sleep(REQUEST_DELAY)

    conn.close()

    print(f"\n{'='*60}")
    print(f"Done.  Upserted: {total_upserted}  |  Skipped: {total_skipped}  |  Failed: {len(failed_symbols)}")
    if failed_symbols:
        print(f"Failed symbols: {', '.join(failed_symbols)}")

    sys.exit(1 if failed_symbols else 0)


if __name__ == "__main__":
    main()
