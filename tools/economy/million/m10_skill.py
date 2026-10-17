"""Skilled minority (Barber-Lee-Liu-Odean 2011/2014: <1% of Taiwanese day traders predictably profit net of costs; Chague-De-Losso-Giovannetti 2020: 97% lose) on top of
the 1M-player supply model: how much of the treasury do they take in 24 months, with the fees going to HERD (now) or to TREASURY.
T path = T0 - packs - gains (+ fees if routed to the treasury)  [identity derived in m3_supply.py].  Share of players skilled and their accuracy are parameters (UNVERIFIED).
Usage: python -E -s m10_skill.py > out_m10.txt
"""
import numpy as np
import mcommon as mc
import m3_supply as m3

print("share skilled | accuracy | fees | K/N/pack at m24 | gains of players (PLAY, 24 mo) | gains as share of N*pack | T24/T0 if kappa_total=1% (fees->HERD) | same, fees->TREASURY | T24/T0 if kappa_total=10% (HERD) | (TREASURY)")
for share in (0.0, 0.01, 0.03):
    for acc in (0.52, 0.56, 0.60, 0.65):
        if share == 0 and acc != 0.52:
            continue
        rows = m3.simulate(ret="top25", reg="uniform12", q=1, skill_share=share, skill_acc=acc, seed=9)
        g = rows[-1]["gains"]; fee = rows[-1]["cum_fee"]; pk = rows[-1]["cum_pack"]
        Npack = 1e6 * mc.PACK
        res = []
        for kap in (0.01, 0.10):
            T0 = Npack / kap
            res.append((1 - (pk + g) / T0, 1 - (pk + g - fee) / T0))
        print(f"{share:.2f} | {acc} | HERD/T | {rows[-1]['K_per_reg']/mc.PACK:.3f} | {g:.3e} | {g/Npack:+.4f} | {res[0][0]:.4f} | {res[0][1]:.4f} | {res[1][0]:.4f} | {res[1][1]:.4f}")
