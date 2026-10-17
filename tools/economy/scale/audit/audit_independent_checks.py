import numpy as np, math
from audit_bb import R3,R15,ok,w3,w15,MK,ST,SG,breaker
from scipy.ndimage import maximum_filter1d
X3=R3[ok]; X15=R15[ok]
print("pool rows",len(X3))
# step stds per scenario (independent daily sums)
def sd_step(X,w,scn):
    if scn=="S1": return np.sqrt(((w*X.std(0))**2).sum())          # indep coins
    if scn=="S2":
        a=(w[:6]*X[:,:6]).sum(1); b=(w[6:]*X[:,6:]).sum(1); return np.sqrt((a**2).mean()+(b**2).mean())
    if scn=="S5":
        a=(w*X).sum(1); return np.sqrt((a**2).mean())
for scn in ["S1","S2","S5"]:
    s3=sd_step(X3,w3,scn); s15=sd_step(X15,w15,scn)
    day=math.sqrt(480*s3**2+96*s15**2)
    print(scn,"step3 sd %.4f%% step15 sd %.4f%% day sd %.3f%%"%(s3*100,s15*100,day*100))
# leak
for a in [.52,.56]:
    l=(2*a-1)*(480*(w3*np.abs(X3).mean(0)).sum()+96*(w15*np.abs(X15).mean(0)).sum())
    print("leak a=%.2f: %.3f%% T/day"%(a,l*100))
# fee/leak ratio
sig=np.array([SG[m] for m in MK])*1e-4
F1=0.1*sig  # fee per unit notional
fee=(480*(w3*F1).sum()+96*(w15*F1).sum())
print("fee per day per unit 1/b (b=1): %.3f%% T ; ratio fee/leak a=.52: %.2f"%(fee*100, fee/((0.04)*(480*(w3*np.abs(X3).mean(0)).sum()+96*(w15*np.abs(X15).mean(0)).sum()))))
# Gaussian BM breaker
def pb(sd_day, days, n, rng, steps_per_day=48):
    s=sd_day/np.sqrt(steps_per_day)
    W=30*steps_per_day; hit=0
    for _ in range(n):
        cum=np.cumsum(rng.standard_normal(days*steps_per_day))*s
        hit+=breaker(cum,W)>=0.1
    return hit/n
rng=np.random.default_rng(3)
print("Gaussian BM check, 30d (n=2000) / 365d (n=200)")
for scn,sdd in [("S1",.02686),("S2",.05383)]:
    for lam in [1,.5,.25,.1]:
        print(scn,lam,"%.4f"%pb(lam*sdd,30,2000,rng),"%.3f"%pb(lam*sdd,365,200,rng))
# sigma multiplier sensitivity (odds markets, vol regime), S2 base
print("sigma multiplier m on S2 base 5.38%/day: P30/P365 for lam=.1,.05")
for m in [1,1.5,2,3]:
    r=[]
    for lam in [.1,.05]:
        r.append("lam%s: %.4f/%.3f"%(lam,pb(lam*m*.05383,30,3000,rng),pb(lam*m*.05383,365,300,rng)))
    print("m=",m,*r)
