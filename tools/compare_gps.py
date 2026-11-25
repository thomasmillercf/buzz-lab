"""Two drives on the SAME stretches: match 2 s windows by position, heading and speed. usage: compare_gps.py A B [dist_m] [dmph]"""
import sys,numpy as np
from scipy.signal import welch
A,B=sys.argv[1:3]; ANY="anydir" in sys.argv; sys.argv=[x for x in sys.argv if x!="anydir"]; DM=float(sys.argv[3]) if len(sys.argv)>3 else 25; DV=float(sys.argv[4]) if len(sys.argv)>4 else 4
def load(d):
    a=np.genfromtxt(d+'/Accelerometer.csv',delimiter=',',skip_header=1);L=np.genfromtxt(d+'/Location.csv',delimiter=',',skip_header=1)
    ok=~np.isnan(L[:,4])&(L[:,6]<20); L=L[ok]; fs=1/np.median(np.diff(a[:,0])); t=np.arange(a[0,0],a[-1,0],1/fs)
    X=np.stack([np.interp(t,a[:,0],a[:,i]) for i in (1,2,3)],1); w=int(2*fs); out=[]
    lat0=float(np.nanmean(L[:,1])); k=111320*np.cos(np.radians(lat0))
    for i in range(0,len(t)-w,w):
        tm=t[i+w//2]; 
        if tm<L[0,0] or tm>L[-1,0]: continue
        v=np.interp(t[i:i+w],L[:,0],L[:,4])*2.23694
        if v.std()>2.5 or v.mean()<12: continue
        x=np.interp(tm,L[:,0],L[:,2])*k; y=np.interp(tm,L[:,0],L[:,1])*111320
        dx=np.interp(tm+2,L[:,0],L[:,2])*k-np.interp(tm-2,L[:,0],L[:,2])*k; dy=(np.interp(tm+2,L[:,0],L[:,1])-np.interp(tm-2,L[:,0],L[:,1]))*111320
        s=X[i:i+w]-X[i:i+w].mean(0); f,p=welch(s,fs=fs,nperseg=int(fs),axis=0)
        out.append((x,y,np.arctan2(dy,dx),v.mean(),p,tm))
    return f,out
f,a=load(A); _,b=load(B); print('windows',len(a),len(b))
pairs=[]
for p in a:
    c=[(np.hypot(p[0]-q[0],p[1]-q[1]),q) for q in b if abs(p[3]-q[3])<DV and (ANY or np.cos(p[2]-q[2])>.8)]
    c=[z for z in c if z[0]<DM]
    if c: pairs.append((p,min(c,key=lambda z:z[0])[1]))
print('matched pairs',len(pairs),'median speed A %.1f B %.1f'%(np.median([p[3] for p,q in pairs]),np.median([q[3] for p,q in pairs])))
bd=[(2,8),(8,20),(20,28),(25,35),(38,50),(50,80),(80,120),(120,230)]
r=lambda P,lo,hi:np.sqrt(P[(f>=lo)&(f<hi)].sum()*(f[1]-f[0]))
for ax,nm in [(None,'all axes'),(1,'y fore-aft'),(2,'z vert'),(0,'x lateral')]:
    print('--',nm)
    for lo,hi in bd:
        ra=np.array([r(p[4].sum(1) if ax is None else p[4][:,ax],lo,hi) for p,q in pairs]); rb=np.array([r(q[4].sum(1) if ax is None else q[4][:,ax],lo,hi) for p,q in pairs])
        lr=np.log(ra/rb); boot=[np.exp(np.median(lr[np.random.default_rng(i).integers(0,len(lr),len(lr))])) for i in range(400)]
        print(f'{lo:>3}-{hi:<3} Hz  A {np.median(ra):.3f}  B {np.median(rb):.3f}  A/B x{np.exp(np.median(lr)):.2f}  (95% {np.percentile(boot,2.5):.2f}-{np.percentile(boot,97.5):.2f}) higher in A on {np.mean(ra>rb)*100:.0f}% of pairs')
for nm,k in (('A',0),('B',1)):
    P=np.mean([z[k][4].sum(1) for z in pairs],0); s=(f>=14)&(f<120); i=np.argsort(P[s])[::-1][:8]; print(nm,'peaks 14-120',np.sort(np.round(f[s][i])), 'fore-aft share 25-35 %.0f%%'%(100*np.mean([z[k][4][(f>25)&(f<35),1].sum()/z[k][4][(f>25)&(f<35)].sum() for z in pairs])))
