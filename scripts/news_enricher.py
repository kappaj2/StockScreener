#!/usr/bin/env python3
"""
news_enricher.py
────────────────
Polls GET /api/signals/pending every 60 seconds.
For any BreakoutSignal whose `news` field is null:
  1. Queries NewsAPI.org for recent headlines about the ticker symbol.
  2. Builds a short (≤2 sentence) summary.
  3. PUTs the summary back via /api/signals/{id}/news.

Environment variables
─────────────────────
  SIGNAL_SERVER_BASE   Base URL of the Spring signal server  (default: http://localhost:8000)
  NEWSAPI_KEY          Your NewsAPI.org API key              (required for news fetching)
  POLL_INTERVAL_S      Seconds between polls                 (default: 60)
  NEWS_LOOKBACK_HOURS  How far back to search for articles   (default: 24)

Usage
─────
  Daemon mode (runs forever, polls every 60s):
      python3 scripts/news_enricher.py

  Single-pass mode (one poll then exit — used by cron / scheduled task):
      python3 scripts/news_enricher.py --once
"""

import os
import sys
import time
import logging
from datetime import datetime, timedelta, timezone

import requests

# ── Configuration ─────────────────────────────────────────────────────────────

SIGNAL_SERVER_BASE  = os.getenv("SIGNAL_SERVER_BASE",  "http://localhost:8000")
NEWSAPI_KEY         = os.getenv("NEWSAPI_KEY",          "")
NEWSAPI_BASE_URL    = "https://newsapi.org/v2/everything"
POLL_INTERVAL_S     = int(os.getenv("POLL_INTERVAL_S",  "60"))
NEWS_LOOKBACK_HOURS = int(os.getenv("NEWS_LOOKBACK_HOURS", "24"))

# ── Logging ───────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)-8s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("news_enricher")


# ── API helpers ───────────────────────────────────────────────────────────────

def fetch_pending() -> list[dict]:
    """Return all BreakoutSignals currently queued on the signal server."""
    url = f"{SIGNAL_SERVER_BASE}/api/signals/pending"
    resp = requests.get(url, timeout=10)
    resp.raise_for_status()
    signals = resp.json()
    log.debug("GET %s → %d signal(s)", url, len(signals))
    return signals


def fetch_news_summary(symbol: str) -> str | None:
    """
    Query NewsAPI for recent headlines related to `symbol`.

    Returns a compact 1–2 line summary string, or None when nothing useful
    is found (no key, no results, or all articles were removed/empty).
    """
    if not NEWSAPI_KEY:
        log.warning("NEWSAPI_KEY is not set — skipping news enrichment for %s", symbol)
        return None

    since = (
        datetime.now(timezone.utc) - timedelta(hours=NEWS_LOOKBACK_HOURS)
    ).strftime("%Y-%m-%dT%H:%M:%S")

    params = {
        "q":        f'"{symbol}" stock OR shares OR earnings OR analyst',
        "from":     since,
        "sortBy":   "publishedAt",
        "language": "en",
        "pageSize": 5,
        "apiKey":   NEWSAPI_KEY,
    }

    try:
        resp = requests.get(NEWSAPI_BASE_URL, params=params, timeout=10)
        resp.raise_for_status()
        data = resp.json()
    except requests.RequestException as exc:
        log.error("NewsAPI request failed for %s: %s", symbol, exc)
        return None

    if data.get("status") != "ok":
        log.warning("NewsAPI returned status '%s' for %s", data.get("status"), symbol)
        return None

    articles = data.get("articles", [])
    if not articles:
        log.info("No recent news found for %s", symbol)
        return None

    # Collect clean, non-placeholder titles (NewsAPI uses "[Removed]" for deleted articles)
    headlines = [
        a["title"].strip()
        for a in articles
        if a.get("title") and "[Removed]" not in a["title"]
    ]

    if not headlines:
        log.info("All articles for %s were removed/empty", symbol)
        return None

    # Primary headline always included; add a second for context when available
    summary = headlines[0]
    if len(headlines) > 1:
        summary += f" | {headlines[1]}"

    log.info("Summary for %-6s → %s", symbol, summary)
    return summary


def put_news(signal_id: str, summary: str) -> bool:
    """
    PUT the news summary string to /api/signals/{id}/news.
    The Spring endpoint expects a plain text request body.
    """
    url = f"{SIGNAL_SERVER_BASE}/api/signals/{signal_id}/news"
    try:
        resp = requests.put(
            url,
            data=summary.encode("utf-8"),
            headers={"Content-Type": "text/plain;charset=UTF-8"},
            timeout=10,
        )
        resp.raise_for_status()
        log.info("✔ Signal %s — news updated", signal_id)
        return True
    except requests.RequestException as exc:
        log.error("PUT news failed for signal %s: %s", signal_id, exc)
        return False


# ── Main loop ─────────────────────────────────────────────────────────────────

def run_once() -> None:
    """Single poll-and-enrich pass."""
    try:
        signals = fetch_pending()
    except requests.RequestException as exc:
        log.error("Could not reach signal server: %s", exc)
        return

    if not signals:
        log.debug("No pending signals — nothing to do.")
        return

    needs_news = [s for s in signals if not s.get("news")]

    log.info(
        "%d pending signal(s) — %d need news enrichment",
        len(signals),
        len(needs_news),
    )

    for sig in needs_news:
        symbol    = sig.get("symbol", "UNKNOWN")
        signal_id = sig.get("id")

        if not signal_id:
            log.warning("Signal missing id field — skipping: %s", sig)
            continue

        log.info("Enriching signal %s (symbol: %s, pattern: %s)",
                 signal_id, symbol, sig.get("pattern"))

        summary = fetch_news_summary(symbol)
        if summary:
            put_news(signal_id, summary)
        else:
            log.info("No usable news for %s — leaving news field null", symbol)


def main() -> None:
    log.info("=" * 60)
    log.info("News Enricher starting up")
    log.info("  Signal server : %s", SIGNAL_SERVER_BASE)
    log.info("  Poll interval : %ds", POLL_INTERVAL_S)
    log.info("  News lookback : %dh", NEWS_LOOKBACK_HOURS)
    log.info("  NewsAPI key   : %s", "set ✔" if NEWSAPI_KEY else "NOT SET ✗")
    log.info("=" * 60)

    if not NEWSAPI_KEY:
        log.warning(
            "NEWSAPI_KEY environment variable is not set.\n"
            "  Export it before running:  export NEWSAPI_KEY=your_key_here\n"
            "  Get a free key at https://newsapi.org/register"
        )

    while True:
        run_once()
        log.debug("Sleeping %ds…", POLL_INTERVAL_S)
        time.sleep(POLL_INTERVAL_S)


if __name__ == "__main__":
    # --once  →  single poll-and-enrich pass, then exit (used by scheduled task)
    # (no args) → daemon mode: runs every POLL_INTERVAL_S seconds forever
    if "--once" in sys.argv:
        log.info("Running in single-pass mode (--once)")
        run_once()
    else:
        try:
            main()
        except KeyboardInterrupt:
            log.info("News Enricher stopped.")
            sys.exit(0)
