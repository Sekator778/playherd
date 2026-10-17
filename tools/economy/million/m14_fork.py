"""Treasury risk with the exposure SATURATED at lambda of the limits (the audit's stress, not a forecast), 730 days, stationary bootstrap, fees leave to HERD (as in the code now).
Scenarios: S2 herd without skill; S3 informed crowd with accuracy a (the gross edge (2a-1)*E|r| is taken from the treasury).
The same runs with the herd fee paid INTO the treasury at the saturated exposure were tried and dropped: fee income at saturation (14.9% of T per day at b=1) is far above what the players'
money can pay (a player staking everything at L=50 pays 0.46% per bet, 265% of the stake per day) so the premium compounds to absurd values; the fork is shown per bet instead (m5_informed.py)
and by the Lundberg exponent (m4_wille_lundberg.py).   Usage: python -E -s m14_fork.py > out_m14.txt"""
import os
import numpy as np
import mcommon as mc
import m2_risk as r2


def main():
    block = float(np.load(f"{mc.CACHE}/block_len.npy")[0])
    print(f"candles {mc.candles_dir()}, block {block} rows, 840 paths x 730 days")
    print("scenario | lambda | fees | P(breaker 365) | P(breaker 730) | P(T<=50% 730) | P(T=0 730) | median T(730)/T0 | 99.9% one-day loss")
    cfg = [("S2", .5, 1.0), ("S2", .5, .5), ("S2", .5, .25), ("S2", .5, .1), ("S2", .5, .05), ("S3", .52, .1), ("S3", .52, .05), ("S3", .52, .02), ("S3", .52, .01),
           ("S3", .56, .02), ("S3", .56, .01), ("S3", .56, .005), ("S3", .56, .003)]
    for scn, a, lam in cfg:
        for lab, bp in (("HERD", None),):
            s = r2.run(scn, lam, a=a, ndays=730, block=block, paths=840, bpremium=bp, per=30)
            print(f"{scn}{'' if scn=='S2' else ' a='+str(a)} | {lam} | {lab} | {s['P_breaker_365']:.3f} | {s['P_breaker_730']:.3f} | {s['P_T<=50%_730']:.3f} | {s['P_T=0_730']:.3f} | {s['T_end_med_730']:.3f} | {s['day_loss_q999']*100:.2f}%", flush=True)


if __name__ == "__main__":
    main()
