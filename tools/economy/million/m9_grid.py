"""Sweep: treasury T in {1e8..1e12} x unit divisor q in {1,10,100} x limit multiplier lambda_policy x where the fees go x registration shape x retention.
For each cell: deterministic treasury path from the supply model (m3), then the 730-day block-bootstrap Monte Carlo (m2) of the herd adversary on top.
Criteria (thresholds are this document's choices, justified in the doc):
  C1 every new player gets a pack: min over months of T_det >= next pack and cumulative packs <= 50% of T0
  C2 P(breaker within 365 and 730 days) < 1%  (deterministic pack drain included)
  C3 P(T <= 50% of T0 within 730 days) < 1%
  C4 share of normal-crowd bets rejected by the exposure limits at the peak month < 1%
  C5 representable in 0.001 PLAY (pack, min stake, network fee are multiples of 0.001) and RTP at the smallest stake not worse than today by more than 0.01 pp
Adversary A2 (stress, not forecast): every ACTIVE player stakes his whole balance at average leverage Lbar=50 on the same side (herd, S2):
  lambda_eff(month) = min(lambda_policy, K_active/T_det * Lbar / sum_m W3_m)      [sum W3 = 5.07 = net notional of the 3-min limits in units of T]
Usage: python -E -s m9_grid.py [quick] > out_m9.txt
"""
import json, os, sys
import numpy as np
import mcommon as mc
import m2_risk as r2
import m3_supply as m3

SUMW = float(mc.W3.sum())
NDAYS = 730
LBAR = 50.0
SIGMULT = 1.0
BSKEW, GPEAK = 0.5, 5.0      # normal crowd: heavy-side share of gross notional, peak/mean factor per round (UNVERIFIED)
CACHE = os.path.join(mc.CACHE, "grid_cache.json")
cache = json.load(open(CACHE)) if os.path.exists(CACHE) else {}
block = float(np.load(f"{mc.CACHE}/block_len.npy")[0]) if os.path.exists(f"{mc.CACHE}/block_len.npy") else 20.0
dataid = f"{mc.candles_dir()}|{block}"


def supply(q, ret, reg, fees_to_t, skill=0.0):
    rows = m3.simulate(ret=ret, reg=reg, q=q, skill_share=skill, fees_to_treasury=fees_to_t)
    return {k: np.array([x[k] for x in rows]) for k in rows[0]}


def units_ok(q):
    pack, ms, nf = mc.PACK / q, mc.MIN_STAKE / q, mc.NET_FEE / q
    rep = all(abs(round(v * 1000) - v * 1000) < 1e-9 and v * 1000 >= 1 for v in (pack, ms, nf))
    def cost(q_, pct, L):
        s_ = max(mc.PACK / q_ * pct / 100.0, mc.MIN_STAKE / q_)
        herd = max(np.ceil(s_ * min(0.01, 0.92e-4 * L) * 1000 - 1e-9) / 1000, 0.001)
        return (herd + mc.NET_FEE / q_) / s_ * 100
    d = max(cost(q, p, L) - cost(1, p, L) for p in (1, 2, 5, 10, 50) for L in (2, 10, 50, 240))   # worst RTP loss, pp
    return rep, d     # representable, RTP change at the smallest stake in percentage points (positive = worse)


def cell(T, q, reg, ret, fees_to_t, lam_pol, sup, mc_paths=840):
    pk = sup["pack_new"] + sup["topup"]
    cumpk = np.cumsum(pk)
    gains = sup["gains"]            # cumulative net game result of the players (T pays these)
    fees = np.cumsum(sup["fees"]) if fees_to_t else np.zeros(len(pk))
    Tdet = T - cumpk - gains + fees
    out = dict(T=T, q=q, reg=reg, ret=ret, fees_to_treasury=fees_to_t, lam_pol=lam_pol)
    out["packs_share_T"] = float(cumpk[-1] / T)
    out["Tdet_min_over_T"] = float(Tdet.min() / T)
    out["peak_month_pack_share"] = float(pk.max() / T)
    nextpack = pk.max() / max(sup["nreg"].max(), 1) * 0 + mc.PACK / q
    kap = float(sup["K"].max() / T)
    out["kappa_K"] = kap; out["ville10"] = kap / (kap + 0.10); out["ville50"] = kap / (kap + 0.50)
    out["C1"] = bool(Tdet.min() >= 0.5 * T and (Tdet > nextpack).all())
    # demand of the normal crowd
    notional_round = sup["notional"] / (30 * 576)
    lam_dem = BSKEW * GPEAK * notional_round / (SUMW * np.maximum(Tdet, 1e-9))
    out["lam_dem_normal_peak"] = float(lam_dem.max())
    out["reject_share_peak"] = float(max(0.0, 1 - lam_pol / lam_dem.max())) if lam_dem.max() > 0 else 0.0
    out["C4"] = out["reject_share_peak"] < 0.01
    rep, d = units_ok(q)
    out["representable"] = bool(rep); out["rtp_loss_pp"] = float(d)
    out["C5"] = bool(rep and d <= 0.01)
    if not out["C1"]:
        out.update(P_breaker_365=1.0, P_breaker_730=1.0, P_T50_730=1.0, P_T0_730=1.0, day_loss_q999=float("nan"), mc="skipped (C1 failed)")
        out["C2"] = out["C3"] = False
        return out
    kact = sup["Kact"]
    lam_m = np.minimum(lam_pol, kact / np.maximum(Tdet, 1e-9) * LBAR / SUMW)
    lam_d = np.repeat(lam_m, 30)[:NDAYS]
    lam_d = np.concatenate([lam_d, np.full(max(0, NDAYS - len(lam_d)), lam_d[-1])])
    out_m = np.diff(np.concatenate([[0.0], cumpk + gains - fees])) / 30.0 / T
    O = np.repeat(out_m, 30)[:NDAYS]
    O = np.concatenate([O, np.zeros(max(0, NDAYS - len(O)))])
    import hashlib
    hh = hashlib.md5(f'{SIGMULT}|{LBAR}|{block}'.encode() + np.round(lam_d, 5).tobytes() + np.round(O * 1e6, 3).tobytes()).hexdigest()
    key = f"{dataid}|{hh}|{mc_paths}"
    if key not in cache:
        prem_b = 0.5 if fees_to_t else None     # fees to treasury: premium on gross = net/b with b=0.5 (adversary herd skew)
        # for the stress adversary the premium is on top of the real fee flow already inside Tdet; to stay conservative the MC uses no extra premium
        s = r2.run("S2", lam_d, ndays=NDAYS, block=block, paths=mc_paths, per=30, bpremium=None, outflow=O, sigmult=SIGMULT)
        cache[key] = s
        json.dump(cache, open(CACHE, "w"))
    s = cache[key]
    out.update(P_breaker_365=s["P_breaker_365"], P_breaker_730=s["P_breaker_730"], P_T50_730=s["P_T<=50%_730"], P_T0_730=s["P_T=0_730"],
               day_loss_q999=s["day_loss_q999"], lam_eff_peak=float(lam_m.max()), mc="ok")
    out["C2"] = bool(s["P_breaker_365"] < 0.01 and s["P_breaker_730"] < 0.01)
    out["C3"] = bool(s["P_T<=50%_730"] < 0.01)
    return out


def main(quick=False):
    Ts = [1e8, 1e9, 1e10, 1e11, 1e12]
    qs = [1, 10, 100]
    lams = [1.0, 0.05, 0.03]
    rets = ["top25"] if quick else ["top25", "median"]
    regs = ["uniform12", "spike"]
    rows = []
    for ret in rets:
        for reg in regs:
            for fees_to_t in (False, True):
                for q in qs:
                    sup = supply(q, ret, reg, fees_to_t)
                    for T in Ts:
                        for lam in lams:
                            res = cell(T, q, reg, ret, fees_to_t, lam, sup)
                            rows.append(res)
                            ok = all(res.get(c, False) for c in ("C1", "C2", "C3", "C4", "C5"))
                            res["ALL"] = ok
                            print(f"ret={ret} reg={reg} fees->{'T' if fees_to_t else 'HERD'} q={q} T={T:g} lam={lam}: kappaK={res['kappa_K']:.3g} ville10={res['ville10']:.3f} packs/T={res['packs_share_T']:.3g} minTdet/T={res['Tdet_min_over_T']:.3g} "
                                  f"Pbr365={res['P_breaker_365']:.3f} Pbr730={res['P_breaker_730']:.3f} P(T<=50%)={res['P_T50_730']:.3f} "
                                  f"lam_eff_peak={res.get('lam_eff_peak', float('nan')):.3g} lam_dem={res['lam_dem_normal_peak']:.3g} reject={res['reject_share_peak']:.3f} "
                                  f"rtp_loss_pp={res['rtp_loss_pp']:.3f} rep={res['representable']} C1..C5={[int(res.get(c, False)) for c in ('C1','C2','C3','C4','C5')]} ALL={ok}", flush=True)
    json.dump(rows, open(os.path.join(mc.CACHE, "grid_rows.json"), "w"))


if __name__ == "__main__":
    main(quick=("quick" in sys.argv))
