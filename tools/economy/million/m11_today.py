"""What breaks first with today's rules (pack 10000 PLAY from the treasury, issue/treasury opening 99,998,100 PLAY) when 1,000,000 players register.
Deterministic: only the packs (new players + monthly top-ups) leave the treasury; registrations spread evenly inside each month.  Treasury opening: GameConfig.java:45 and application.properties:30
(herd.economy.treasury-opening-play=99998100); what the players hold at the cutover is subtracted there (unknown here -> taken as 0, UNVERIFIED).
First failure A: the circuit breaker opens (LedgerBook.java:837-857) when treasury < 90% of its 30-day maximum.  First failure B: grantPack -> moveTreasury throws
'TREASURY would go negative' (LedgerBook.java:667-672) i.e. a new player gets NO pack.   Usage: python -E -s m11_today.py > out_m11.txt
"""
import numpy as np
import mcommon as mc
import m3_supply as m3

T0 = 99_998_100.0
for ret in ("top25", "median"):
    for reg in ("uniform12", "uniform6", "spike"):
        rows = m3.simulate(ret=ret, reg=reg, q=1)
        reg_m = np.array([r["reg"] for r in rows]); pk = np.array([r["pack_new"] + r["topup"] for r in rows])
        daily_pk = np.repeat(pk / 30.0, 30)
        daily_reg = np.repeat(reg_m / 30.0, 30)
        T = T0 - np.cumsum(daily_pk)
        cumreg = np.cumsum(daily_reg)
        mx = np.maximum.accumulate(T)
        # 30-day window maximum
        win = np.array([T[max(0, i - 29):i + 1].max() for i in range(len(T))])
        win = np.maximum(win, T0 if True else 0)
        br = np.where(T * 10 < np.array([max(T0, T[max(0, i - 29):i + 1].max()) for i in range(len(T))]) * 9)[0]
        neg = np.where(T < mc.PACK)[0]
        print(f"retention {ret}, registrations {reg}: breaker opens day {br[0]+1 if len(br) else None} (players registered so far {cumreg[br[0]]:.0f}); "
              f"first pack that cannot be paid: day {neg[0]+1 if len(neg) else None} (players registered so far {cumreg[neg[0]]:.0f}); "
              f"theory: N_max = T/pack = {T0/mc.PACK:.0f} players if nobody tops up")
