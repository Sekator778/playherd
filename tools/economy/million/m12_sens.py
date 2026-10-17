"""Sensitivity of the key cells of the grid (m9): block length, sigma multiplier (unmodelled odds markets: audit 2026-10-17 point 4: x1.3 - x2.3), adversary leverage.
Usage: python -E -s m12_sens.py > out_m12.txt"""
import numpy as np
import mcommon as mc
import m9_grid as g

def main():
    cells = [(1e12, 1, "uniform12", "top25", False), (1e11, 1, "uniform12", "top25", False), (1e12, 1, "spike", "top25", False), (1e11, 1, "spike", "top25", False)]
    print("cell(T,q,reg) | block rows | sigma x | Lbar | lam_pol | P(breaker365) | P(breaker730) | P(T<=50%) | 99.9% day loss | lam_eff_peak")
    for (T, q, reg, ret, ft) in cells:
        sup = g.supply(q, ret, reg, ft)
        for blk, sm, lb, lam in ((795, 1, 50, 1.0), (795, 1, 50, 0.05), (96, 1, 50, 1.0), (20, 1, 50, 1.0), (795, 1.5, 50, 1.0), (795, 2.0, 50, 1.0), (795, 2.0, 50, 0.05),
                                 (795, 1, 240, 1.0), (795, 1, 240, 0.05), (795, 2.0, 240, 0.05), (795, 3.0, 240, 0.05)):
            g.block, g.SIGMULT, g.LBAR = blk, sm, lb
            r = g.cell(T, q, reg, ret, ft, lam, sup, mc_paths=840)
            print(f"T={T:g} q={q} {reg} | {blk} | {sm} | {lb} | {lam} | {r['P_breaker_365']:.3f} | {r['P_breaker_730']:.3f} | {r['P_T50_730']:.3f} | {r['day_loss_q999']*100:.2f}% | {r.get('lam_eff_peak', float('nan')):.3f}", flush=True)


if __name__ == "__main__":
    main()
