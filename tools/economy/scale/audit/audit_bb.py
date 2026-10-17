import numpy as np, os, sys
from scipy.ndimage import maximum_filter1d
D=os.environ.get("CANDLES_DIR", ".local/bots-candles")
def load(s):
    a=[]
    for l in open(f"{D}/{s}_1m.csv"):
        if l.startswith("#") or not l.strip(): continue
        p=l.split(","); a.append((int(p[0]),float(p[4])))
    return a
ST=dict(BTC=.00817,ETH=.01308,SOL=.01601,DOGE=.01456,XRP=.01478,HYPE=.01271,NVDA=.00527,TSLA=.00758,BRENTOIL=.0043,GOLD=.00438,XYZ100=.00325,SP500=.00161)
SG=dict(BTC=9.2,ETH=13.4,SOL=17.3,DOGE=17.2,XRP=15.3,HYPE=15.7,NVDA=7.5,TSLA=7.0,BRENTOIL=8.3,GOLD=4.8,XYZ100=4.5,SP500=2.4)
MK=["BTC","ETH","SOL","DOGE","XRP","HYPE","NVDA","TSLA","BRENTOIL","GOLD","XYZ100","SP500"]
raw={s:load(s) for s in ["BTC","ETH","SOL","NVDA","GOLD"]}
t0=min(r[0][0] for r in raw.values()); t1=max(r[-1][0] for r in raw.values())
n=(t1-t0)//60000+1
cl={}
for s,r in raw.items():
    a=np.full(n,np.nan)
    for ts,c in r: a[(ts-t0)//60000]=c
    cl[s]=a
def ret(a,k): 
    out=np.full(len(a),np.nan); out[:len(a)-k]=a[k:]/a[:-k]-1; return out
def cols(k):
    r={s:ret(cl[s],k) for s in cl}
    so=r["SOL"]; nv=r["NVDA"]; go=r["GOLD"]
    f=lambda m,b: SG[m]/SG[b]
    return np.column_stack([r["BTC"],r["ETH"],so,so*f("DOGE","SOL"),so*f("XRP","SOL"),so*f("HYPE","SOL"),nv,nv*f("TSLA","NVDA"),go*f("BRENTOIL","GOLD"),go,nv*f("XYZ100","NVDA"),nv*f("SP500","NVDA")])
R3=cols(3); R15=cols(15)
ok=~(np.isnan(R3).any(1)|np.isnan(R15).any(1))
okc=np.convolve(ok.astype(int),np.ones(1),'same')
s3=np.array([ST[m] for m in MK]); s15=2.2*s3
c=np.array([.01/6]*6+[.0025]*6)
w3=c/s3; w15=c/s15
def lagged_ok(blockmin):
    # valid starts: all rows b..b+blockmin ok
    cs=np.concatenate([[0],np.cumsum(~ok)])
    L=blockmin+1
    st=np.arange(0,n-L)
    bad=cs[st+L]-cs[st]
    return st[bad==0]
def day_path(rng, scn, blockmin, ndays, mult=None):
    """returns per-step P&L array (480*ndays) in units of T for lambda=1"""
    steps=480*ndays
    per_block=blockmin//3
    nb=int(np.ceil(steps/per_block))
    starts=lagged_ok(blockmin)
    sidx=rng.choice(starts,size=nb)
    p=np.zeros(nb*per_block)
    # 3-min steps
    t=(sidx[:,None]+3*np.arange(per_block)[None,:]).ravel()
    r=R3[t]
    # 15-min: every 5th step: use the same time index
    if scn=="S1": d=rng.choice([-1.,1.],size=r.shape)
    else:
        d=np.empty(r.shape); d[:,:6]=rng.choice([-1.,1.],size=(len(t),1)); d[:,6:]=rng.choice([-1.,1.],size=(len(t),1))
    p=-(d*w3*r).sum(1)
    # 15-min flow: steps where index%5==0 inside block
    m=(np.arange(len(t))%5==0)
    t15=t[m]; r15=R15[t15]
    if scn=="S1": d15=rng.choice([-1.,1.],size=r15.shape)
    else:
        d15=np.empty(r15.shape); d15[:,:6]=rng.choice([-1.,1.],size=(len(t15),1)); d15[:,6:]=rng.choice([-1.,1.],size=(len(t15),1))
    p15=-(d15*w15*r15).sum(1)
    p[m]+=p15
    p=p[:steps]
    if mult is not None:
        dm=np.repeat(mult(rng,ndays),480); p=p*dm
    return p
def breaker(cum,W=14400):
    T=1+cum; Wn=min(W,len(T)); M=maximum_filter1d(T,size=Wn,mode="nearest",origin=(Wn-1)//2); return ((M-T)/M).max()
def run(label,blockmin,mult=None,n30=1500,n365=150):
    rng=np.random.default_rng(1)
    for scn in ["S1","S2"]:
        P=np.array([day_path(rng,scn,blockmin,30,mult) for _ in range(n30)])
        dsum=P.reshape(n30*30,480).sum(1)
        out=f"{label} {scn}: day sd {dsum.std()*100:.3f}% q0.1% {np.quantile(dsum,.001)*100:.2f}% kurt {((dsum-dsum.mean())**4).mean()/dsum.var()**2:.2f} |"
        for lam in [1,.5,.25,.1]:
            out+=f" lam{lam}: {np.mean([breaker(np.cumsum(lam*p))>=.1 for p in P]):.4f}"
        out+=" | 365d:"
        P2=np.array([day_path(rng,scn,blockmin,365,mult) for _ in range(n365)])
        for lam in [.25,.1,.05]:
            out+=f" lam{lam}: {np.mean([breaker(lam*np.cumsum(p))>=.1 for p in P2]):.3f}"
        print(out,flush=True)
if __name__=="__main__":
    bm=int(sys.argv[1]); kind=sys.argv[2] if len(sys.argv)>2 else "none"
    if kind=="sv":
        sd=float(sys.argv[3]); mu=-sd**2  # E[m^2]=exp(2mu+2sd^2)=1 -> mu=-sd^2
        mult=lambda rng,nd: np.exp(mu+sd*rng.standard_normal(nd))
        run(f"block{bm} SV sd{sd}",bm,mult)
    else: run(f"block{bm}",bm)
