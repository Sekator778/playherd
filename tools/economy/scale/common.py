"""Shared data and constants. Constants cite their source in comments."""
import numpy as np, csv, os

CANDLES = os.environ.get("CANDLES_DIR", ".local/bots-candles")  # was an absolute path; set CANDLES_DIR to override

# stress_move, 3-min round, 99.9th pct of |r|: server/src/main/resources/db/migration/V12__economy_stage_b.sql:12-30
STRESS = {"BTC": .00817, "ETH": .01308, "SOL": .01601, "DOGE": .01456, "XRP": .01478, "HYPE": .01271,
          "NVDA": .00527, "TSLA": .00758, "BRENTOIL": .0043, "GOLD": .00438, "XYZ100": .00325, "SP500": .00161}
ODDS_STRESS = 0.20  # V12:30, UNVERIFIED in the migration itself
N_ODDS = 6          # architecture_review_independent_2026-10-16.md 6.2 (six odds markets)
LONG_FACTOR = 2.2   # MarketEconomy.java:38
# sigma of the 3-min log-return, basis points: docs/research/game_economy_math.md summary line 1
SIGMA_BP = {"BTC": 9.2, "ETH": 13.4, "SOL": 17.3, "DOGE": 17.2, "XRP": 15.3, "HYPE": 15.7,
            "NVDA": 7.5, "TSLA": 7.0, "BRENTOIL": 8.3, "GOLD": 4.8, "XYZ100": 4.5, "SP500": 2.4}
CRYPTO = ["BTC", "ETH", "SOL", "DOGE", "XRP", "HYPE"]
OTHER = ["NVDA", "TSLA", "BRENTOIL", "GOLD", "XYZ100", "SP500"]
MARKET_SHARE = 0.0025   # EconomyLimits.java:25
BUCKET_SHARE = 0.01     # EconomyLimits.java:27
MIN_STAKE, MIN_LEV = 100.0, 2          # LedgerBook.java:38, Leverage.MIN (architecture review 6.2)
STARTER = 10_000.0                       # LedgerBook.java:35
FEE_R = 0.1                              # r_m = 0.1*sigma3 (game_economy_proposal.md 6.3)
NET_FEE = 0.1                            # EconomyRules.NETWORK_FEE
KAPPA = 0.1                              # MarketSpec.DEFAULT_KAPPA
CAP3 = {"BTC": 1200, "ETH": 1000, "SOL": 600}   # game_economy_math.md summary line 4 (3-min caps)


def load(sym):
    rows = []
    with open(os.path.join(CANDLES, f"{sym}_1m.csv")) as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            p = line.strip().split(",")
            rows.append((int(p[0]), float(p[1]), float(p[2]), float(p[3]), float(p[4])))
    return rows


def grid(rows, t0, t1):
    n = (t1 - t0) // 60000 + 1
    a = np.full((n, 4), np.nan)
    for ts, o, h, l, c in rows:
        i = (ts - t0) // 60000
        if 0 <= i < n:
            a[i] = (o, h, l, c)
    return a  # columns o,h,l,c


def ret(close, k, stride=1):
    r = close[k:] / close[:-k] - 1.0
    r = r[::stride]
    return r
