import numpy as np
from s2lib import *
rng = np.random.default_rng(5)
n = 400000
for mode in ["joint", "indep"]:
    for scn, a in [("S1", .5), ("S2", .5), ("S3", .52)]:
        R = draw(mode, 3, n, rng)
        d = direction(scn, a, R, rng)
        mk = -(d * (c / s3) * R)
        p = mk.sum(1); pc = mk[:, :6].sum(1); po = mk[:, 6:].sum(1)
        q = lambda x: [np.quantile(x, z) * 100 for z in (0.001, 0.01, 0.5, 0.99, 0.999)]
        print(f"[{mode}] {scn}: per 3-min round, % of T: sd all {p.std()*100:.4f}, crypto-only sd {pc.std()*100:.4f}, other sd {po.std()*100:.4f}; q0.1/1/50/99/99.9 = " + " ".join(f"{x:+.3f}" for x in q(p)) + f"; min {p.min()*100:.3f}; mean {p.mean()*100:+.5f}")
# diversification check: crypto-only sd if the 6 crypto markets were independent vs the correlated pool (S2)
R = draw("joint", 3, n, rng)[:, :6]
d = rng.choice([-1., 1.], size=(n, 1))
cor = (-(d * (c[:6] / s3[:6]) * R)).sum(1).std()
ind = np.sqrt(((c[:6] / s3[:6] * R.std(0))**2).sum())
print(f"crypto bucket sd herd (correlated, pool): {cor*100:.4f}% T ; if independent: {ind*100:.4f}% T ; ratio {cor/ind:.2f}")
print("max single-round loss within pools at lambda=1 (S2): see min above")
