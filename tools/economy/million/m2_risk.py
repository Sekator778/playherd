"""Treasury risk over 365/730 days: stationary block bootstrap (Politis-Romano, arch sampler, block length from m1_tails_blocks.py),
multiplicative treasury (limits scale with the LIVE treasury, audit 2026-10-17 point 4), breaker as in LedgerBook.java:837-857.

State: T (units of the starting treasury).  Per round: T *= 1 + p, p = -sum_m eps_m * lam*W_m * r_m  (+ fee premium lam*sum r_m*W_m / b if fees go to TREASURY).
`lam` = share of the limit-saturated net notional actually carried (the audit's lambda times the saturation factor).
Optional daily outflow vector O (units of the STARTING treasury, applied at the start of the day: packs, top-ups) from m3_supply.py.
"""
import numpy as np
import multiprocessing as mp
import mcommon as mc

_R3 = _R15 = None


def _init():
    global _R3, _R15
    _R3, _R15, _ = mc.build_rows()


def sides(scn, a, R, rng):
    """R: [..., M] returns. Returns eps in {-1,+1}: the side of the heavier crowd."""
    shp = R.shape
    if scn == "S1":
        return np.where(rng.random(shp) < 0.5, -1.0, 1.0)
    if scn == "S2":   # herd: one coin for the 6 crypto, one for the 6 others, per round
        e = np.empty(shp)
        e[..., :6] = np.where(rng.random(shp[:-1] + (1,)) < 0.5, -1.0, 1.0)
        e[..., 6:] = np.where(rng.random(shp[:-1] + (1,)) < 0.5, -1.0, 1.0)
        return e
    if scn == "S3":   # informed, independent: side = sign(r) w.p. a
        return np.sign(R) * np.where(rng.random(shp) < a, 1.0, -1.0)
    raise ValueError(scn)


def chunk(args):
    (seed, P, ndays, block, scn, a, lam, bpremium, sigmult, outflow) = args
    rng = np.random.default_rng(seed)
    n = len(_R15)
    idx = mc.stationary_paths(n, ndays * mc.STEPS15, block, P, rng)
    T = np.ones(P)
    hist = np.ones((P, mc.BREAKER_DAYS))          # daily maxima of the last 30 days
    first_br = np.full(P, 10 ** 6); first_50 = np.full(P, 10 ** 6); first_0 = np.full(P, 10 ** 6)
    br_days = np.zeros(P); minT = np.ones(P)
    dayret = np.empty((P, ndays), dtype=np.float32)
    Tend = np.empty((P, ndays), dtype=np.float32)
    lam_arr = np.broadcast_to(np.asarray(lam, dtype=float), (ndays,))
    for d in range(ndays):
        lam = float(lam_arr[d])
        prem_step = np.zeros(5)
        if bpremium:
            prem_step += lam * mc.PREM3 / bpremium
            prem_step[4] += lam * mc.PREM15 / bpremium
        ix = idx[:, d * mc.STEPS15:(d + 1) * mc.STEPS15]
        R3 = _R3[ix] * sigmult           # [P,96,5,M]
        R15 = _R15[ix] * sigmult         # [P,96,M]
        p3 = -(sides(scn, a, R3, rng) * (lam * mc.W3) * R3).sum(-1)       # [P,96,5]
        p15 = -(sides(scn, a, R15, rng) * (lam * mc.W15) * R15).sum(-1)   # [P,96]
        p3[:, :, 4] += p15
        f = (1.0 + p3 + prem_step).reshape(P, mc.STEPS15 * 5)
        if outflow is not None:
            T = np.maximum(T - outflow[d], 0.0)
        Tstart = T.copy()
        Tp = Tstart[:, None] * np.cumprod(f, axis=1)
        run = np.maximum.accumulate(Tp, axis=1)
        prev = hist[:, -(mc.BREAKER_DAYS - 1):].max(1)
        Mx = np.maximum(prev[:, None], run)
        br = (Tp * 10.0 < Mx * 9.0)
        any_br = br.any(1)
        br_days += any_br
        first_br = np.where(any_br & (first_br > 10 ** 5), d + 1, first_br)
        mn = Tp.min(1)
        minT = np.minimum(minT, mn)
        first_50 = np.where((mn <= 0.5) & (first_50 > 10 ** 5), d + 1, first_50)
        first_0 = np.where((mn <= 1e-9) & (first_0 > 10 ** 5), d + 1, first_0)
        T = np.where(Tstart > 0, Tp[:, -1], 0.0)
        hist = np.concatenate([hist[:, 1:], np.maximum(run[:, -1], Tstart)[:, None]], axis=1)
        dayret[:, d] = np.where(Tstart > 0, T / np.maximum(Tstart, 1e-300) - 1.0, 0.0)
        Tend[:, d] = T
    return dict(first_br=first_br, first_50=first_50, first_0=first_0, br_days=br_days, minT=minT, dayret=dayret, Tend=Tend)


def run(scn, lam, a=0.5, ndays=730, block=20, paths=840, bpremium=None, sigmult=1.0, outflow=None, procs=14, seed=1, per=60):
    nchunks = max(1, paths // per)
    tasks = [(seed * 100003 + i, per, ndays, block, scn, a, lam, bpremium, sigmult, outflow) for i in range(nchunks)]
    with mp.Pool(procs, initializer=_init) as pool:
        res = pool.map(chunk, tasks)
    out = {k: np.concatenate([r[k] for r in res]) for k in res[0]}
    return summarize(out, ndays)


def summarize(o, ndays):
    P = len(o["first_br"])
    s = {"paths": P}
    for H in (365, 730):
        if H > ndays:
            continue
        s[f"P_breaker_{H}"] = float((o["first_br"] <= H).mean())
        s[f"P_T<=50%_{H}"] = float((o["first_50"] <= H).mean())
        s[f"P_T=0_{H}"] = float((o["first_0"] <= H).mean())
        s[f"T_end_med_{H}"] = float(np.median(o["Tend"][:, H - 1]))
        s[f"T_end_q05_{H}"] = float(np.quantile(o["Tend"][:, H - 1], 0.05))
    s["breaker_day_share"] = float(o["br_days"].mean() / ndays)
    s["day_loss_q999"] = float(np.quantile(o["dayret"], 0.001))
    s["day_mean"] = float(o["dayret"].mean())
    s["day_sd"] = float(o["dayret"].std())
    return s


def fmt(s):
    keys = [k for k in s if k.startswith("P_") or k in ("day_loss_q999", "day_mean", "day_sd", "T_end_med_365", "T_end_med_730", "breaker_day_share")]
    return " ".join(f"{k}={s[k]:.4g}" for k in keys)
