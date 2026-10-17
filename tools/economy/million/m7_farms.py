"""Wallet farms (Douceur 2002: without a central authority Sybil attacks cannot be excluded, so cap the damage per account).
1. K0 growth with accounts per person: packs paid = accounts * pack.
2. Can a farmer turn treasury money into withdrawable winnings by betting two own accounts against each other (long on A, short on B, same market, same round)?
   Pair EV per unit stake = E[X_long + X_short - 2] - 2*fee, on real 3-minute windows with the engine payout rule.  Positive = exploit.
3. Registration throttle arithmetic: herd.registration.max-per-ip-per-day = 3 (application.properties:32; GameService.java:397).
Code facts used (no turnover limit on withdrawals): GameService.requestWithdrawal (GameService.java:1700-1760): minimum 100 PLAY (:209), amount <= balance, amount <= withdrawable;
LedgerBook.withdrawable (LedgerBook.java:807-814) = balance - packCredited (all packs ever credited), only when economy is active; herd.withdrawal.enabled defaults to false (application.properties:133).
Usage: python -E -s m7_farms.py > out_m7.txt
"""
import numpy as np
import mcommon as mc
from m5_informed import windows3, payoff, fee_share

N = 1_000_000
print("== 1. packs paid for N=1,000,000 PEOPLE with g accounts each (all accounts registered; pack 10000/q PLAY) ==")
print("g accounts/person | accounts | packs PLAY at q=1 | share of T=1e8 | T=1e10 | T=1e12 (q=1)")
for g in (1.0, 1.1, 2, 10):
    tot = N * g * mc.PACK
    print(f"{g} | {N*g:,.0f} | {tot:.3e} | {tot/1e8:.1f}x | {tot/1e10:.3f} | {tot/1e12:.4f}")
print("Throttle: 3 new players per IP per 24h -> registering 9,000,000 extra accounts takes 3,000,000 IP-days (e.g. 8,200 addresses for a year). The limit is per address, not per person; wallets are free (testnet).")

print("\n== 2. hedged pair (own account long + own account short) ==")
for sym, cap, rmk in (("BTC", 1200, 0.92e-4), ("ETH", 1000, 1e-4), ("SOL", 600, 1e-4)):
    r, lo, hi = windows3(sym)
    print(f"-- {sym}: {len(r)} windows")
    print("   L | pair gross EV % of one stake (long+short-2) | 2 fees % | pair net EV % | P(both lose)")
    for L in (2, 10, 50, 100, 240, cap // 5, cap // 2, cap):
        if L < 2 or L > cap:
            continue
        XL = payoff(1.0, r, lo, hi, L, cap); XS = payoff(-1.0, r, lo, hi, L, cap)
        gross = (XL + XS - 2).mean()
        fee = 2 * fee_share(L, rmk)
        both = ((XL == 0) & (XS == 0)).mean()
        print(f"   {L} | {gross*100:+.4f} | {fee*100:.3f} | {(gross-fee)*100:+.4f} | {both:.4f}")

print("\n== 3. expected cost of moving X PLAY from one own account to the other by a random walk of pair bets (fees only) ==")
print("To make account B end X above the pack by repeated opposite bets of stake S at leverage L on BTC: rounds n ~ (X/(S*L*E|r|))^2 (random walk step S*L*E|r|), fees 2*n*fee(S,L).")
r, lo, hi = windows3("BTC"); Eabs = np.abs(r).mean()
for X, S, L in ((10_000, 10_000, 10), (10_000, 10_000, 100), (10_000, 250, 1200), (5_000, 250, 240)):
    step = S * min(L * Eabs, 1.0)
    n = (X / step) ** 2
    fee = 2 * (np.ceil(S * min(0.01, 0.92e-4 * L) * 1000) / 1000 + 0.1)
    print(f"X={X} S={S} L={L}: step {step:.1f} PLAY/round, rounds ~ {n:.3g}, fees ~ {n*fee:.3g} PLAY = {n*fee/X:.2f}x the amount moved  (cost/gain >1 means the farm loses money)")
