#!/usr/bin/env python3
"""Fetch BTC perp candles from the Hyperliquid public "info" API and write CSV fixtures.

Endpoint (public, read-only, no auth):
    POST https://api.hyperliquid.xyz/info
    Body: {"type": "candleSnapshot",
           "req": {"coin": "BTC", "interval": <interval>,
                    "startTime": <epoch_ms>, "endTime": <epoch_ms>}}

Doc for the endpoint and the "only the most recent 5000 candles are available" cap:
    https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint.md
    (section "Candle snapshot", fetched 2026-09-25)

Response row shape (per Hyperliquid docs, same section):
    {"t": open_time_ms, "T": close_time_ms, "s": coin, "i": interval,
     "o": open, "h": high, "l": low, "c": close, "v": volume, "n": trades}

This script is stdlib-only (urllib), does read-only POSTs to a public info endpoint
(no keys, no order placement, no state-changing action), and paginates forward by
startTime so that intervals whose true history exceeds the 5000-candle cap are still
covered in full. Empirically (2026-09-25) the 1d interval returns its entire history
in one call (2229 rows, well under the cap) and the 1h interval is capped to the most
recent ~5000 hours (data starts 2026-03-01T04:00:00Z, ~208 days before the fetch date);
the pagination loop below does not depend on either fact and will keep fetching forward
in 5000-candle-sized windows until it catches up to "now" regardless of how much history
a given coin/interval turns out to have.

Output CSV columns (ascending by open_time_ms, de-duplicated by open_time_ms):
    open_time_ms,close_time_ms,open,high,low,close,volume,trades

Prices/volume are written exactly as the API returned them (decimal strings, not
re-formatted through float) so no precision is lost or invented.

The currently-open (incomplete) candle is excluded: a candle is kept only if its
close_time_ms already elapsed relative to the wall-clock time at fetch.

Usage:
    python3 fetch_hl_candles.py [--coin BTC] [--out-dir DIR]

With no arguments, fetches BTC "1d" (full history) and "1h" (full available history)
and writes hl_btc_1d.csv / hl_btc_1h.csv into
<repo_root>/engine/src/test/resources/fixtures/.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

INFO_URL = "https://api.hyperliquid.xyz/info"

# Interval -> nominal duration in milliseconds. Only the two intervals this project
# uses are listed; extend if another interval from the documented supported list
# ("1m","3m","5m","15m","30m","1h","2h","4h","8h","12h","1d","3d","1w","1M") is needed.
INTERVAL_MS = {
    "1h": 60 * 60 * 1000,
    "1d": 24 * 60 * 60 * 1000,
}

REQUEST_TIMEOUT_S = 30
MAX_RETRIES = 3
RETRY_SLEEP_S = 2


def post_info(body: dict) -> object:
    """POST a JSON body to the public /info endpoint and return the parsed JSON response."""
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        INFO_URL,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    last_err = None
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_S) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as exc:
            last_err = exc
            if attempt < MAX_RETRIES:
                time.sleep(RETRY_SLEEP_S)
    raise RuntimeError(f"candleSnapshot request failed after {MAX_RETRIES} attempts: {last_err}")


def fetch_candle_snapshot(coin: str, interval: str, start_ms: int, end_ms: int) -> list[dict]:
    body = {
        "type": "candleSnapshot",
        "req": {"coin": coin, "interval": interval, "startTime": start_ms, "endTime": end_ms},
    }
    result = post_info(body)
    if not isinstance(result, list):
        raise RuntimeError(f"unexpected candleSnapshot response shape: {result!r}")
    return result


def fetch_all_candles(coin: str, interval: str, now_ms: int) -> list[dict]:
    """Fetch the full available history for coin/interval by paginating forward.

    Starts at epoch 0 and repeatedly asks for [cursor, now_ms]. Hyperliquid caps each
    response to (approximately) the most recent 5000 candles relative to the request
    window, so for a history longer than the cap the first call effectively returns the
    tail end of [cursor, now_ms]; advancing the cursor to just after the last row we
    have not yet seen and re-querying walks forward until the response reaches "now",
    at which point every candle has been collected exactly once (duplicates, if any,
    are still de-duplicated by the caller).
    """
    interval_ms = INTERVAL_MS[interval]
    by_open_time: dict[int, dict] = {}
    cursor = 0
    seen_max_open_time = -1

    while True:
        batch = fetch_candle_snapshot(coin, interval, cursor, now_ms)
        if not batch:
            break

        for row in batch:
            by_open_time[row["t"]] = row

        batch_max_open_time = max(row["t"] for row in batch)
        if batch_max_open_time <= seen_max_open_time:
            # No forward progress: the API is returning the same tail window again.
            break
        seen_max_open_time = batch_max_open_time

        if batch_max_open_time + interval_ms >= now_ms:
            # This batch already reaches the current, still-open candle -> we have
            # caught up to "now" and there is nothing further to fetch.
            break

        cursor = batch_max_open_time + interval_ms

    return list(by_open_time.values())


def to_rows(raw_candles: list[dict], now_ms: int) -> list[dict]:
    """Filter out the currently-open (incomplete) candle and sort ascending."""
    complete = [c for c in raw_candles if c["T"] < now_ms]
    complete.sort(key=lambda c: c["t"])
    rows = []
    for c in complete:
        rows.append(
            {
                "open_time_ms": c["t"],
                "close_time_ms": c["T"],
                "open": c["o"],
                "high": c["h"],
                "low": c["l"],
                "close": c["c"],
                "volume": c["v"],
                "trades": c["n"],
            }
        )
    return rows


def write_csv(rows: list[dict], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(
            f,
            fieldnames=[
                "open_time_ms",
                "close_time_ms",
                "open",
                "high",
                "low",
                "close",
                "volume",
                "trades",
            ],
        )
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def default_fixtures_dir() -> Path:
    repo_root = Path(__file__).resolve().parent.parent
    return repo_root / "engine" / "src" / "test" / "resources" / "fixtures"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--coin", default="BTC")
    parser.add_argument("--out-dir", default=None, help="Output directory for CSV fixtures")
    parser.add_argument(
        "--intervals",
        default="1d,1h",
        help="Comma-separated list of intervals to fetch (subset of INTERVAL_MS keys)",
    )
    args = parser.parse_args(argv)

    out_dir = Path(args.out_dir) if args.out_dir else default_fixtures_dir()
    intervals = [i.strip() for i in args.intervals.split(",") if i.strip()]

    for interval in intervals:
        if interval not in INTERVAL_MS:
            raise SystemExit(f"unsupported interval {interval!r}; known: {sorted(INTERVAL_MS)}")

        now_ms = int(time.time() * 1000)
        print(f"[fetch] coin={args.coin} interval={interval} now_ms={now_ms}", file=sys.stderr)
        raw = fetch_all_candles(args.coin, interval, now_ms)
        rows = to_rows(raw, now_ms)
        out_path = out_dir / f"hl_{args.coin.lower()}_{interval}.csv"
        write_csv(rows, out_path)
        first = rows[0]["open_time_ms"] if rows else None
        last = rows[-1]["open_time_ms"] if rows else None
        print(
            f"[fetch] wrote {out_path} rows={len(rows)} first_open_ms={first} last_open_ms={last}",
            file=sys.stderr,
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
