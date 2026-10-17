import numpy as np, math
from common import *
T = 1e8
E = {m: MARKET_SHARE*T/STRESS[m] for m in STRESS}
side_total = 521.9e6   # from s1
print("== funds ceiling (stakes <= K0 per round, each PLAY staked once per 3-min round)")
for k0 in [0.01, 0.10, 0.50]:
    K0 = k0*T
    print(f" K0={k0*100:.0f}% T = {K0/1e6:.0f}M: min bets (100) {K0/100:,.0f}/round = {K0/100/180:,.0f}/s ; bets of 1000: {K0/1000:,.0f}/round = {K0/1000/180:,.1f}/s; users at starter 10000: {K0/STARTER:,.0f}; PLAY per user at 1M users: {K0/1e6:.2f}")
print("   Ville bound P(DD>=10% T) <= K0/(K0+0.1T):", {f"{k*100:.0f}%": round(k/(k+0.1),3) for k in [0.01,0.1,0.5]})
print("== exposure ceiling for bets 1000x10 (N=10k), all 18 markets, bets per round, at heavy-side share p=(1+b)/2")
N = 10_000
for b in [0.0, 0.01, 0.05, 0.2, 0.6, 1.0]:
    p = (1+b)/2
    today = side_total/p/N
    row = f"  b={b}: today {today:,.0f}"
    for lam in [1.0, 0.1]:
        net = float('inf') if b == 0 else lam*side_total/b/N
        row += f" | net lambda={lam}: {net:,.0f}" + (f" (x{net/today:,.1f})" if b > 0 else "")
    print(row)
print("== iid-random-sided crowd, 1000x10: n_max per market s.t. z*N*sqrt(n) <= lambda*E_m (z=3), summed over 12 priced markets (bucket ignored) + 6 odds")
for lam in [1.0, 0.1]:
    tot = 0; btc = 0
    for m in STRESS:
        n = (lam*E[m]/(3*N))**2; tot += n
        if m == "BTC": btc = n
    tot += N_ODDS*(lam*(MARKET_SHARE*T/ODDS_STRESS)/(3*N))**2
    print(f"  lambda={lam}: BTC {btc:,.0f} bets/round, all 18 markets {tot:,.0f}; today: BTC {2*E['BTC']/N:,.0f}, all {2*side_total/N:,.0f}")
print("== required b for x100 (net rule vs today at same b): (1+b)/(2b)*lambda>=100")
for lam in [1, .1]:
    b = lam/(200-lam); print(f"  lambda={lam}: b<= {b:.5f}; iid crowd needs n >= {1/b**2:,.0f} bets for b~1/sqrt(n)")
print("== skew theta needed: theta >= 2/(1+b) - lambda/(k b)")
for k in [10, 100, 1000]:
    for b in [0.05, 0.2, 0.6, 1.0]:
        th = 2/(1+b) - 0.1/(k*b); print(f"  k={k} b={b}: theta={th:.3f} (pool-equivalent 2/(1+b)={2/(1+b):.3f}); heavy m_U=1-theta*b={1-th*b:.3f} (D/U={(1-b)/(1+b):.3f})")
print("== fees / RTP of archetypes on BTC (r_m=0.1*9.2bp)")
rm = FEE_R*SIGMA_BP['BTC']*1e-4
for name, S, L in [("min 100x2",100,2),("ordinary 1000x10",1000,10),("1000x240",1000,240)]:
    fh = math.ceil(S*min(0.01, rm*L)*1000)/1000; tot = fh+NET_FEE
    print(f"  {name}: herd fee {fh:.3f} + net {NET_FEE} = {tot:.3f} PLAY = {tot/S*100:.3f}% -> RTP(fees) {100-tot/S*100:.3f}%")
print("   breakeven accuracy 0.5+0.5*r_m/E|r| (E|r|=5.01bp empirical BTC):", 0.5+0.5*rm/5.01e-4)
print("== fee-retention (b): F/day = k0*tau*f_s ; lambda_leak = F/ell, ell=5.48%T/day at a=0.52")
fs = 0.00092
for k0, tau in [(0.01,10),(0.1,10),(0.5,45),(0.5,10)]:
    F = k0*tau*fs; print(f"  K0={k0*100:.0f}%T tau={tau}: F={F*100:.4f}%T/day -> lambda_leak={F/0.0548:.4f}; multiple vs today at b=0.2: {F/0.0548*(1.2)/(0.4):.4f}")
