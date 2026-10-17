"""Common constants and data for the 1M-players / 2-years calculation (docs/research/economy_1m_players_2y_2026-10-17.md).

Constants cite their source in comments (file:line of the code, or the doc).  Candles: CANDLES_DIR (default .local/econ-candles
if it holds the 730-day files, else .local/bots-candles, 14 days).  Run scripts with `python -E -s`.
"""
import os
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
CACHE = os.path.join(ROOT, ".local", "million-cache")
os.makedirs(CACHE, exist_ok=True)


def candles_dir():
    d = os.environ.get("CANDLES_DIR")
    if d:
        return d
    long_ = os.path.join(ROOT, ".local", "econ-candles")
    if all(os.path.exists(os.path.join(long_, f"{s}_1m.csv")) for s in ("BTC", "ETH", "SOL")):
        return long_
    return os.path.join(ROOT, ".local", "bots-candles")


# --- constants from the code ---
STRESS = {"BTC": .00817, "ETH": .01308, "SOL": .01601, "DOGE": .01456, "XRP": .01478, "HYPE": .01271,
          "NVDA": .00527, "TSLA": .00758, "BRENTOIL": .0043, "GOLD": .00438, "XYZ100": .00325, "SP500": .00161}  # V12__economy_stage_b.sql:12-30
LONG_FACTOR = 2.2                 # MarketEconomy.java:38
SIGMA_BP = {"BTC": 9.2, "ETH": 13.4, "SOL": 17.3, "DOGE": 17.2, "XRP": 15.3, "HYPE": 15.7,
            "NVDA": 7.5, "TSLA": 7.0, "BRENTOIL": 8.3, "GOLD": 4.8, "XYZ100": 4.5, "SP500": 2.4}  # game_economy_math.md (3-min sigma, table)
CRYPTO = ["BTC", "ETH", "SOL", "DOGE", "XRP", "HYPE"]
OTHER = ["NVDA", "TSLA", "BRENTOIL", "GOLD", "XYZ100", "SP500"]
MK = CRYPTO + OTHER
M = len(MK)
MARKET_SHARE = 0.0025             # EconomyLimits.java:25
BUCKET_SHARE = 0.01               # EconomyLimits.java:27
BREAKER_FRAC = 0.9                # LedgerBook.java:837-857: treasury*10 < max30*9
BREAKER_DAYS = 30                 # LedgerBook.java:76
FEE_R = 0.1                       # r_m = 0.1*sigma3 (game_economy_proposal.md 6.3); MarketEconomy.java:45-56
FEE_CAP = 0.01                    # EconomyRules.java:32
PACK = 10_000.0                   # LedgerBook.java:36
MIN_STAKE = 100.0                 # LedgerBook.java:38
NET_FEE = 0.1                     # EconomyRules.java:28
ISSUED = 100_000_000.0            # GameService.java:196
UNIT = 0.001                      # PlayAmount.java:24 (1 PLAY = 1000 mojo)
CAP3 = {"BTC": 1200, "ETH": 1000, "SOL": 600, "DOGE": 600, "XRP": 600, "HYPE": 600, "NVDA": 1500, "TSLA": 1500,
        "BRENTOIL": 1000, "GOLD": 2000, "XYZ100": 2000, "SP500": 4000}   # MarketEconomy.java:45-56 (capHourly)
RM = {"BTC": 0.92e-4, "ETH": 1e-4, "SOL": 1e-4, "DOGE": 1e-4, "XRP": 1e-4, "HYPE": 1e-4, "NVDA": .75e-4, "TSLA": .70e-4,
      "BRENTOIL": .83e-4, "GOLD": .48e-4, "XYZ100": .45e-4, "SP500": .24e-4}  # r_m, MarketEconomy.java:45-56

s3 = np.array([STRESS[m] for m in MK])
s15 = s3 * LONG_FACTOR
c_share = np.array([BUCKET_SHARE / 6] * 6 + [MARKET_SHARE] * 6)   # share of T per market at stress (as in tools/economy/scale/s2lib.py)
rm = np.array([RM[m] for m in MK])
W3 = c_share / s3        # net notional per market per unit T at lambda=1, 3-min round
W15 = c_share / s15      # 15-min round
PREM3 = float((rm * W3).sum())    # fee per unit "net/b" at lambda=1, per 3-min round, in T
PREM15 = float((rm * W15).sum())

STEPS15 = 96   # 15-minute super-steps per day (each holds five 3-min rounds and one 15-min round)

# groups for the proxies (UNVERIFIED, see doc section "входы и пробелы")
RHO_EQ, RHO_COM = 0.42, 0.33   # BTC-NVDA 0.423 and BTC-GOLD 0.334 on 3.5 days: economy_at_scale_math_2026-10-16.md section 3


def load_close(sym, d=None):
    d = d or candles_dir()
    ts, cl = [], []
    with open(os.path.join(d, f"{sym}_1m.csv")) as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            p = line.split(",")
            ts.append(int(p[0])); cl.append(float(p[4]))
    ts = np.array(ts); cl = np.array(cl)
    t0, t1 = ts[0], ts[-1]
    n = (t1 - t0) // 60000 + 1
    g = np.full(n, np.nan)
    g[(ts - t0) // 60000] = cl
    # forward fill gaps
    idx = np.where(~np.isnan(g), np.arange(n), 0)
    np.maximum.accumulate(idx, out=idx)
    return t0, g[idx]


def build_rows(cache=True):
    """Returns (R3 [n15,5,M], R15 [n15,M], info) built from the 1-minute closes; markets without candles are proxies."""
    d = candles_dir()
    os.makedirs(os.path.join(ROOT, ".local", "million-cache"), exist_ok=True)
    cpath = os.path.join(ROOT, ".local", "million-cache", "rows.npz")
    key = f"{d}|{os.path.getmtime(os.path.join(d, 'BTC_1m.csv'))}"
    if cache and os.path.exists(cpath):
        z = np.load(cpath, allow_pickle=True)
        if str(z["key"]) == key:
            return z["R3"], z["R15"], dict(days=float(z["days"]), key=key)
    raw = {s: load_close(s, d) for s in ("BTC", "ETH", "SOL")}
    t0 = max(v[0] for v in raw.values())
    t1 = min(v[0] + (len(v[1]) - 1) * 60000 for v in raw.values())
    n = (t1 - t0) // 60000      # number of 1-minute returns
    n -= n % 15
    lr = {}
    for s, (st, cl) in raw.items():
        o = (t0 - st) // 60000
        lr[s] = np.diff(np.log(cl[o:o + n + 1]))   # n log-returns per minute
    rng = np.random.default_rng(12345)
    z_btc = lr["BTC"] / lr["BTC"].std()
    sh1, sh2 = int(n * 0.37), int(n * 0.71)           # circular shifts, decorrelate from BTC (proxy)
    z_eq = RHO_EQ * z_btc + np.sqrt(1 - RHO_EQ ** 2) * np.roll(z_btc, sh1)
    z_com = RHO_COM * z_btc + np.sqrt(1 - RHO_COM ** 2) * np.roll(z_btc, sh2)
    z_eq /= z_eq.std(); z_com /= z_com.std()
    per_min = {}
    for m in MK:
        sig1 = SIGMA_BP[m] * 1e-4 / np.sqrt(3)       # table 3-min sigma -> 1-min sigma
        if m in ("BTC", "ETH", "SOL"):
            per_min[m] = lr[m]
        elif m in ("DOGE", "XRP", "HYPE"):
            per_min[m] = lr["SOL"] / lr["SOL"].std() * sig1          # perfectly correlated with SOL (as in scale/)
        elif m in ("BRENTOIL", "GOLD"):
            per_min[m] = z_com * sig1
        else:
            per_min[m] = z_eq * sig1
    X = np.column_stack([per_min[m] for m in MK])    # [n, M] log-returns per minute
    cs = np.vstack([np.zeros(M), np.cumsum(X, axis=0)])  # log price index
    k15 = n // 15
    base = np.arange(k15) * 15
    R15 = np.exp(cs[base + 15] - cs[base]) - 1.0
    R3 = np.stack([np.exp(cs[base + 3 * (q + 1)] - cs[base + 3 * q]) - 1.0 for q in range(5)], axis=1)
    days = n / 1440.0
    if cache:
        np.savez(cpath, R3=R3, R15=R15, key=key, days=days)
    return R3, R15, dict(days=days, key=key)


def stationary_paths(nrows, horizon_rows, block, reps, rng):
    """Politis-Romano stationary bootstrap index paths using arch's sampler (arch.bootstrap._samplers.stationary_bootstrap_sample)."""
    from arch.bootstrap._samplers import stationary_bootstrap_sample
    out = np.empty((reps, horizon_rows), dtype=np.int32)
    p = 1.0 / block
    for i in range(reps):
        ind = rng.integers(0, nrows, size=horizon_rows).astype(np.int64)
        u = rng.random(horizon_rows)
        out[i] = stationary_bootstrap_sample(ind, u, p) % nrows   # data treated as circular (Politis-Romano)
    return out
