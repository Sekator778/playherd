"""Ville's inequality (1939; Ramdas CMU 36-771 notes, Theorem 1) and Cramer-Lundberg / Lundberg inequality (Pham 2007, section 3.1, eq. 3.1-3.6).

Ville: players' money K is a non-negative supermartingale while nobody has skill and fees are positive.  The treasury loses >= x*T0 only if K grows from K0
to K0 + x*T0, so  P(sup_t drawdown >= x) <= K0 / (K0 + x*T0).

Lundberg (discrete time, per 15-min super-step): Z = c - Y, c = fee premium per step, Y = treasury claim of the step (herd/independent coins on the exposure
sitting at lambda=1 of the limits).  theta solves E exp(theta*(Y-c)) = 1 (Pham eq. 3.4, random-walk form).  Then the reflected walk (= drawdown from the running
maximum, what the breaker watches) has P(D_n >= x) <= exp(-theta*x) for every n, and  P(max_{k<=n} D_k >= x) <= n_eff * exp(-theta*x) (union bound, crude).
Everything scales with lambda: theta(lambda) = theta(1)/lambda because c is proportional to the exposure too.
Usage: python -E -s m4_wille_lundberg.py > out_m4.txt
"""
import numpy as np
from scipy.optimize import brentq
import mcommon as mc

print("== Ville bound: P(drawdown of the treasury >= x of T0 at any time) <= kappa/(kappa + x), kappa = K0/T0 ==")
xs = [0.10, 0.30, 0.50]
print("kappa(K0/T0) | x=10% (breaker) | x=30% | x=50%")
for kap in (1.0, 0.3, 0.1, 0.03, 0.01, 0.003, 0.001, 0.0001):
    print(f"{kap:g} | " + " | ".join(f"{kap/(kap+x):.4f}" for x in xs))
print("kappa needed for the bound < 1 pct: x=10 pct -> kappa < %.5f ; x=50 pct -> kappa < %.5f" % (0.01 * 0.10 / 0.99, 0.01 * 0.5 / 0.99))

R3, R15, info = mc.build_rows()
rng = np.random.default_rng(5)
NS = 400_000


def steps(scn):
    ix = rng.integers(0, len(R15), NS)
    r3, r15 = R3[ix], R15[ix]   # iid rows (Lundberg is a one-step statement)
    if scn == "S1":
        e3 = np.where(rng.random(r3.shape) < .5, -1.0, 1.0); e15 = np.where(rng.random(r15.shape) < .5, -1.0, 1.0)
    else:   # S2 herd, one coin per group per round
        e3 = np.ones(r3.shape); e15 = np.ones(r15.shape)
        c3 = np.where(rng.random(r3.shape[:2] + (2,)) < .5, -1.0, 1.0); c15 = np.where(rng.random((NS, 2)) < .5, -1.0, 1.0)
        e3[..., :6] = c3[..., :1]; e3[..., 6:] = c3[..., 1:]
        e15[:, :6] = c15[:, :1]; e15[:, 6:] = c15[:, 1:]
    Y = (e3 * mc.W3 * r3).sum((1, 2)) + (e15 * mc.W15 * r15).sum(1)   # claim (loss of treasury is +Y with sign convention p = -sum)
    return Y


Prem1 = 5 * mc.PREM3 + mc.PREM15     # premium per super-step at lambda=1, b=1 (net == gross), in T
print(f"\npremium per 15-min super-step at lambda=1 with exposure saturated: {Prem1:.3e} T   ({Prem1*96*100:.4f}% T per day) - far more than the players' money can pay (a player staking all at L=50 pays 265% of his stake per day), so the premium is also scanned at 10%, 1%, 0.1% of that")
print("\n== Lundberg: theta(1) [1/T-units], bound exp(-theta*0.10/lambda) per time, and horizon bound min(1, n*exp(-theta*0.1/lambda)), n = 96*365 steps ==")
print("fees go to: HERD (c=0): no theta>0 exists (E exp(theta*Y) >= 1 for symmetric Y) -> Lundberg gives nothing; zero drift random walk is recurrent")
for scn in ("S1", "S2"):
    Y = steps(scn)
    print(f"[{scn}] step sd = {Y.std():.3e} T, mean {Y.mean():.1e}")
    for b in (1.0, 0.1, 0.01, 0.001):   # b here = 1/(share of the saturated premium that the players' money can actually pay): 1, 10%, 1%, 0.1%
        c = Prem1 * b
        f = lambda th: np.log(np.mean(np.exp(th * (Y - c))))
        # f(0)=0, f'(0) = -c < 0; find th>0 with f(th)=0
        hi = 1.0
        while f(hi) < 0 and hi < 1e6:
            hi *= 2
        if f(hi) < 0:
            print(f"  fees->treasury, paid share {b}: no root up to {hi:g}"); continue
        lo = hi / 2 if f(hi / 2) < 0 else 1e-9
        th = brentq(f, max(lo, 1e-9), hi) if f(max(lo, 1e-9)) < 0 else float("nan")
        row = []
        for lam in (1.0, 0.5, 0.25, 0.1, 0.05, 0.03):
            per = np.exp(-th * 0.10 / lam)
            hz = min(1.0, 96 * 365 * per)
            row.append(f"lam={lam}: {per:.2e}/{hz:.2e}")
        print(f"  fees->treasury, paid share {b}: theta(1)={th:.1f}  " + " | ".join(row))
