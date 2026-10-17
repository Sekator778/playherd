"""Money supply over 24 months for 1,000,000 registered players: sources and sinks (Castronova 2001 style accounting).
Identity: ISSUED = T (treasury) + K (players) + HERD + out (withdrawals).  Packs/top-ups move T -> K, bets move T <-> K (zero mean),
fees move K -> HERD (or K -> T if the fees go to the treasury), withdrawals move K -> out.

Retention: GameAnalytics 2026 benchmark (published 2026-06-04): median D1 ~20%, D7 <4% (~3.9), D30 0.69-0.79%; top quartile D1 >30%, D7 6-7%, D30 ~1.6-1.8%.
Beyond D30 a power law R(d)=R1*d^-b through D1 and D30 is extrapolated (UNVERIFIED).
Player behaviour (bets per active day, share of balance per bet, leverage, active days per month) is UNVERIFIED - three personas.
Usage: python -E -s m3_supply.py > out_m3.txt
"""
import sys
import numpy as np
import mcommon as mc

NSIM = 20000
NTARGET = 1_000_000
MONTHS = 24

RET = {"median": dict(D1=0.20, D7=0.039, D30=0.0075), "top25": dict(D1=0.30, D7=0.065, D30=0.017)}
# persona: (share, bets per active day, stake share of balance, leverage, active days per month)  UNVERIFIED
PERSONAS = {"casual": (0.60, 2, 0.02, 5, 5), "regular": (0.30, 8, 0.05, 10, 15), "aggressive": (0.10, 20, 0.20, 50, 25)}
REG = {"uniform12": None, "spike": None}


def reg_weights(kind):
    w = np.zeros(MONTHS)
    if kind == "uniform12":
        w[:12] = 1.0
    elif kind == "uniform6":
        w[:6] = 1.0
    elif kind == "spike":      # half in month 0, then halving
        w[:12] = 0.5 ** np.arange(12)
    return w / w.sum()


def p_active(ret):
    b = np.log(ret["D1"] / ret["D30"]) / np.log(30.0)
    R = lambda d: ret["D1"] * d ** (-b)
    pa = np.zeros(MONTHS)
    a0 = sum(R(d) for d in range(1, 31))
    for k in range(1, MONTHS):
        A = sum(R(d) for d in range(30 * k + 1, 30 * k + 31))
        pa[k] = 1 - np.exp(-A)
    return pa, a0, b, R


def pooled_returns():
    R3, _, _ = mc.build_rows()
    return R3[:, :, :3].ravel()     # BTC, ETH, SOL 3-min returns (real candles)


def herd_fee(stake, L, q):
    share = np.minimum(mc.FEE_CAP, 0.92e-4 * L)
    return np.maximum(np.ceil(stake * share * 1000 - 1e-9) / 1000, mc.UNIT)


def simulate(ret="top25", reg="uniform12", q=1, skill_share=0.0, skill_acc=0.56, withdraw_share=0.0, fees_to_treasury=False, seed=3, verbose=False):
    rng = np.random.default_rng(seed)
    rr = pooled_returns()
    pa, a0, b, Rfun = p_active(RET[ret])
    pack = mc.PACK / q; minstake = mc.MIN_STAKE / q; netfee = mc.NET_FEE / q
    w = reg_weights(reg)
    regm = rng.choice(MONTHS, size=NSIM, p=w)
    names = list(PERSONAS)
    pers = rng.choice(len(names), size=NSIM, p=[PERSONAS[n][0] for n in names])
    nb = np.array([PERSONAS[n][1] for n in names])[pers]
    f = np.array([PERSONAS[n][2] for n in names])[pers]
    L = np.array([PERSONAS[n][3] for n in names], dtype=float)[pers]
    dact = np.array([PERSONAS[n][4] for n in names])[pers]
    skilled = rng.random(NSIM) < skill_share
    B = np.zeros(NSIM); registered = np.zeros(NSIM, bool)
    scale = NTARGET / NSIM
    rows = []
    cum_pack = cum_fee = cum_wd = 0.0
    lmaint = 0.1 / 1200   # liquidation threshold l = kappa/cap (BTC), as in tools/economy/scale/s4_pool_skew.py
    for m in range(MONTHS):
        new = regm == m
        B[new] = pack; registered |= new
        pk_new = new.sum() * pack
        age = m - regm
        ret_mask = registered & ~new & (rng.random(NSIM) < pa[np.clip(age, 0, MONTHS - 1)])
        topup = np.where(ret_mask, np.maximum(pack - B, 0), 0.0)
        B += topup
        active = new | ret_mask
        days = np.where(new, np.minimum(dact, 1 + rng.poisson(a0, NSIM)), dact)
        nbets = np.where(active, nb * days, 0)
        fee_m = 0.0; notional_m = 0.0
        for k in range(int(nbets.max())):
            msk = (k < nbets)
            if not msk.any():
                break
            idx = np.where(msk)[0]
            Bi = B[idx]
            s = np.maximum(f[idx] * Bi, minstake)
            fh = herd_fee(s, L[idx], q); fee = fh + netfee
            ok = (s + fee) <= Bi
            if not ok.any():
                continue
            idx, s, fee, Bi = idx[ok], s[ok], fee[ok], Bi[ok]
            r = rr[rng.integers(0, len(rr), len(idx))]
            side = np.where(rng.random(len(idx)) < 0.5, -1.0, 1.0)
            sk = skilled[idx]
            side = np.where(sk, np.sign(r) * np.where(rng.random(len(idx)) < skill_acc, 1.0, -1.0), side)
            eq = 1.0 + side * L[idx] * r
            X = np.where(eq <= lmaint * L[idx], 0.0, np.minimum(eq, 20.0))
            B[idx] = Bi + s * (X - 1.0) - fee
            fee_m += fee.sum(); notional_m += (s * L[idx]).sum()
        wd = 0.0
        if withdraw_share > 0:   # only what is above the packs credited so far; packs credited ~ pack * (1 + topups) - approximated by one pack
            ex = np.maximum(B - pack, 0) * withdraw_share * active
            B -= ex; wd = ex.sum()
        cum_pack += (pk_new + topup.sum()); cum_fee += fee_m; cum_wd += wd
        rows.append(dict(month=m + 1, reg=new.sum() * scale, active=active.sum() * scale, pack_new=pk_new * scale, topup=topup.sum() * scale,
                         fees=fee_m * scale, K=B[registered].sum() * scale, K_per_reg=B[registered].mean(),
                         Kact_mean=B[active].mean() if active.any() else 0.0, cum_pack=cum_pack * scale, cum_fee=cum_fee * scale,
                         cum_wd=cum_wd * scale, nreg=registered.sum() * scale, notional=notional_m * scale,
                         Kact=B[active].sum() * scale, gains=(B[registered].sum() - cum_pack + cum_fee + cum_wd) * scale))
    return rows


def save_rows(rows, path):
    np.savez(path, **{k: np.array([r[k] for r in rows]) for k in rows[0]})


def show(rows, title, T0=None):
    print(f"\n### {title}")
    print("month | registered(cum) | active | new packs | top-ups | fees paid | K (players) | K per registered / pack | mean balance of active / pack")
    pk = rows[0]["K_per_reg"] if False else None
    for r in rows:
        if r["month"] in (1, 2, 3, 6, 9, 12, 18, 24):
            print(f"{r['month']:2d} | {r['nreg']:.0f} | {r['active']:.0f} | {r['pack_new']:.3g} | {r['topup']:.3g} | {r['fees']:.3g} | {r['K']:.4g} | {r['K_per_reg']:.3g} | {r['Kact_mean']:.3g}")
    last = rows[-1]
    print(f"24 months: packs paid (new+top-ups) {last['cum_pack']:.4g}, fees {last['cum_fee']:.4g}, K {last['K']:.4g}")


if __name__ == "__main__":
    for ret in ("top25", "median"):
        pa, a0, b, R = p_active(RET[ret])
        print(f"retention '{ret}': power-law exponent b={b:.3f}; R(7) fit {R(7):.4f} vs benchmark D7 {RET[ret]['D7']}; P(active) in month 2,3,6,12,24 = "
              f"{[round(float(pa[k]), 4) for k in (1, 2, 5, 11, 23)]}; mean extra days in month 0 {a0:.2f}")
    for q in (1, 100):
        for ret in ("top25", "median"):
            for reg in ("uniform12", "spike"):
                rows = simulate(ret=ret, reg=reg, q=q)
                show(rows, f"q={q} (pack {mc.PACK/q:g}), retention {ret}, registrations {reg}, no skill, no withdrawals, fees to HERD")
                save_rows(rows, f"{mc.CACHE}/supply_q{q}_{ret}_{reg}.npz")
    print("\n=== sensitivity: skilled players (1% of players accuracy 0.56), q=1, top25, uniform12 ===")
    show(simulate(skill_share=0.01), "skilled 1% a=0.56")
    show(simulate(skill_share=0.01, skill_acc=0.60), "skilled 1% a=0.60")
    print("\n=== sensitivity: withdrawals (50% of the excess over the pack leaves each active month), q=1, top25 ===")
    show(simulate(withdraw_share=0.5), "withdraw 50% of excess")
