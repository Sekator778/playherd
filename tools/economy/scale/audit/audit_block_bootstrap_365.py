import numpy as np
from audit_bb import day_path, breaker
rng=np.random.default_rng(9)
sd=.7; mu=-sd**2
mult=lambda rng,nd: np.exp(mu+sd*rng.standard_normal(nd))
P=np.array([day_path(rng,"S2",1440,365,mult) for _ in range(500)])
for lam in [.1,.07,.05,.03]:
    print("block1440+SV0.7 S2 365d lam",lam, np.mean([breaker(lam*np.cumsum(p))>=.1 for p in P]))
P=np.array([day_path(rng,"S2",1440,365,None) for _ in range(500)])
for lam in [.1,.07,.05,.03]:
    print("block1440 S2 365d lam",lam, np.mean([breaker(lam*np.cumsum(p))>=.1 for p in P]))
