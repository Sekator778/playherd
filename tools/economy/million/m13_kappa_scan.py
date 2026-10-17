"""Finer scan of kappa_total = N*pack/T (q=1, N=1e6 registered): where do the criteria C1-C5 of m9 break, per lambda policy.  T = 1e10/kappa.
Usage: python -E -s m13_kappa_scan.py > out_m13.txt"""
import m9_grid as g
def main():
    print("reg | ret | kappa_total | lam_pol | K_max/T | Ville P(dd>=10%) | packs/T | P(br365) | P(br730) | P(T<=50%) | 99.9% day loss | lam_eff_peak | C1..C4")
    for reg in ("uniform12", "spike"):
        sup = g.supply(1, "top25", reg, False)
        for kap in (0.003, 0.005, 0.01, 0.02, 0.03, 0.05, 0.07, 0.10, 0.15, 0.20, 0.30, 0.40):
            T = 1e10 / kap
            for lam in (1.0, 0.05, 0.03):
                r = g.cell(T, 1, reg, "top25", False, lam, sup, mc_paths=840)
                print(f"{reg} | top25 | {kap} | {lam} | {r['kappa_K']:.4f} | {r['ville10']:.3f} | {r['packs_share_T']:.4f} | {r['P_breaker_365']:.3f} | {r['P_breaker_730']:.3f} | {r['P_T50_730']:.3f} | {r['day_loss_q999']*100:.2f}% | {r.get('lam_eff_peak', float('nan')):.3f} | {[int(r.get(c, False)) for c in ('C1','C2','C3','C4')]}", flush=True)


if __name__ == "__main__":
    main()
