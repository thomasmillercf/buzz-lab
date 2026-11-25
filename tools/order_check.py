"""Speed-tracked order analysis + injection sensitivity. usage: order_check.py <folder> [tmax]"""
import sys, numpy as np
from scipy.signal import welch, coherence
d=sys.argv[1]; tmax=float(sys.argv[2]) if len(sys.argv)>2 else 1e9
a=np.genfromtxt(f'{d}/Accelerometer.csv',delimiter=',',skip_header=1); L=np.genfromtxt(f'{d}/Location.csv',delimiter=',',skip_header=1)
a=a[a[:,0]<tmax]; t=a[:,0]; dt=np.diff(t); fs=1/np.median(dt)
print('dt med %.3fms p99 %.3f max %.1f gaps>5ms %d'%(np.median(dt)*1e3,np.percentile(dt,99)*1e3,dt.max()*1e3,(dt>.005).sum()))
ok=~np.isnan(L[:,4]); C=2.48
# uniform resample
tu=np.arange(t[0],t[-1],1/fs); A=np.stack([np.interp(tu,t,a[:,i]) for i in (1,2,3)],1)
v=np.interp(tu,L[ok,0],L[ok,4])
def segs(lo,hi,w=8):
    n=int(w*fs); out=[]
    for i in range(0,len(tu)-n,n//2):
        vs=v[i:i+n]
        if vs.mean()*2.23694>=lo and vs.mean()*2.23694<hi and vs.std()<0.1*vs.mean(): out.append(slice(i,i+n))
    return out
def ospec(X,s,spr=128,nrev=10):
    ph=np.cumsum(v[s])/fs/C; th=np.arange(ph[0],ph[-1],1/spr)
    Y=np.stack([np.interp(th,ph,X[s][:,k]) for k in range(3)],1)
    if len(Y)<spr*nrev: return None
    f,p=welch(Y-Y.mean(0),fs=spr,nperseg=spr*nrev,axis=0); return f,p.sum(1)
def tracked(X,S):
    r=[q for q in (ospec(X,s) for s in S) if q]; 
    if not r: return None
    return r[0][0],np.mean([p for _,p in r],0),len(r)
for lo,hi in [(18,30),(30,40),(40,50),(50,62),(62,78)]:
    S=segs(lo,hi); R=tracked(A,S)
    if R is None: print(lo,hi,'no data'); continue
    f,p,n=R
    ratio=lambda o,p=p:p[np.argmin(abs(f-o))]/np.median(p[(abs(f-o)<.8)&(abs(f-o)>.15)])
    pk=[]
    for o in np.arange(.8,40,.1):
        x=ratio(o)
        if x>2.0: pk.append((round(x,1),round(o,1)))
    print(f'{lo}-{hi} mph n={n} vmean={np.mean([v[s].mean() for s in S])*2.237:.0f}: 1x {ratio(1):.2f} 2x {ratio(2):.2f} 3x {ratio(3):.2f}  orders>2x bg: {sorted(pk,reverse=True)[:6]}')
    # injection: find smallest 1x/2x amplitude giving ratio >=2
    for o in (1,2):
        for amp in (.02,.03,.05,.08,.12,.2,.3,.5):
            X=A.copy(); ph=2*np.pi*o*np.cumsum(v)/fs/C; X[:,1]+=amp*np.sin(ph)
            f2,p2,_=tracked(X,S)
            if ratio(o,p2)>=2: print(f'    {o}x detectable from {amp} m/s2 peak'); break
        else: print(f'    {o}x NOT detectable up to 0.5')
# 30 Hz band: amplitude dependence & cross-axis
m=v*2.23694; w=int(2*fs); rows=[]
for i in range(0,len(tu)-w,w):
    if m[i:i+w].min()<16: continue
    X=A[i:i+w]-A[i:i+w].mean(0); f,p=welch(X,fs=fs,nperseg=int(fs),axis=0); P=p.sum(1); b=(f>24)&(f<36)
    rows.append((np.sqrt(P[b].sum()*(f[1]-f[0])),f[b][np.argmax(P[b])],(f[b]*P[b]).sum()/P[b].sum()))
r=np.array(rows); q=np.quantile(r[:,0],[.25,.5,.75])
for k in range(4):
    z=r[np.digitize(r[:,0],q)==k]; print(f'24-36Hz level quartile {k+1}: rms {z[:,0].mean():.2f} centroid {z[:,2].mean():.2f}Hz peak median {np.median(z[:,1]):.1f}')
mv=m>16; f,c=coherence(A[mv,1],A[mv,2],fs=fs,nperseg=int(fs*2)); print('fore-aft/vertical coherence 25-35Hz %.2f'%c[(f>25)&(f<35)].mean())
f,p=welch(A[mv]-A[mv].mean(0),fs=fs,nperseg=int(fs*2),axis=0)
for k,n in enumerate('xyz'):
    b=(f>15)&(f<60); i=np.argsort(p[b,k])[::-1][:5]; print(n,'top 15-60Hz',np.sort(np.round(f[b][i],1)))
