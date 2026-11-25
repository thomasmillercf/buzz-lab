import sys, numpy as np
from scipy.signal import welch
d = sys.argv[1]
a = np.genfromtxt(f'{d}/Accelerometer.csv', delimiter=',', skip_header=1)
L = np.genfromtxt(f'{d}/Location.csv', delimiter=',', skip_header=1)
t = a[:, 0]; fs = 1/np.median(np.diff(t))
print('dur', round(t[-1]-t[0], 1), 'fs', round(fs, 1), 'gps pts', len(L))
lt, lv = L[:, 0], L[:, 4]
ok = ~np.isnan(lv)
v = np.interp(t, lt[ok], lv[ok]) * 2.23694  # mph
print('max mph', round(v.max(), 1), 'mean mph', round(v.mean(), 1))
# orientation: mean gravity vector
g = a[:, 1:4].mean(0); print('gravity vec', np.round(g, 2))
# 1s windows, high-pass via detrend
win = int(fs*2)
rows = []
for i in range(0, len(t)-win, win):
    seg = a[i:i+win, 1:4]
    vs = v[i:i+win]
    if vs.std() > 2: continue
    rows.append((vs.mean(), seg))
bins = [(0,3),(3,10),(10,16),(16,22),(22,28),(28,35),(35,45),(45,55),(55,65),(65,75)]
circ = 2.48
for lo, hi in bins:
    segs = [s for m, s in rows if lo <= m < hi]
    if not segs: continue
    sp = mphs = 0
    P = None
    for s in segs:
        f, p = welch(s - s.mean(0), fs=fs, nperseg=int(fs), axis=0)
        p = p.sum(1)
        P = p if P is None else P + p
    P /= len(segs)
    m = np.mean([mm for mm, s in rows if lo <= mm < hi])
    wf = m/2.23694/circ
    rms = lambda a_, b_: np.sqrt(np.trapezoid(P[(f >= a_) & (f < b_)], f[(f >= a_) & (f < b_)]))
    sel = f > 2
    top = f[sel][np.argsort(P[sel])[::-1][:5]]
    print(f'{lo}-{hi} mph n={len(segs)} avg={m:.1f} wheel={wf:.1f}Hz  rms 2-8={rms(2,8):.3f} 8-20={rms(8,20):.3f} 20-50={rms(20,50):.3f} 50-120={rms(50,120):.3f} 120-235={rms(120,235):.3f}  peaks={np.round(top,1)}')
print('--- order check & 30Hz peak width')
for lo,hi in [(16,22),(22,28),(28,35),(35,45),(45,55),(55,65),(65,75)]:
    segs=[s for m,s in rows if lo<=m<hi]; ms=[m for m,s in rows if lo<=m<hi]
    if not segs: continue
    P=None
    for s in segs:
        f,p=welch(s-s.mean(0),fs=fs,nperseg=int(fs*2),axis=0); p=p.sum(1); P=p if P is None else P+p
    P/=len(segs); wf=np.mean(ms)/2.23694/circ
    def amp(fr): i=np.argmin(abs(f-fr)); return P[i]
    nb=lambda fr: np.median(P[(f>fr-2)&(f<fr+2)])
    pk=f[(f>20)&(f<45)][np.argmax(P[(f>20)&(f<45)])]
    half=P[(f>20)&(f<45)].max()/2; band=f[(f>20)&(f<45)][P[(f>20)&(f<45)]>half]
    print(f'{lo}-{hi}: 1x {amp(wf)/nb(wf):.1f}x bg, 2x {amp(2*wf)/nb(2*wf):.1f}x bg, peak {pk:.1f}Hz halfwidth {band.min():.1f}-{band.max():.1f}')
# per-axis at 30Hz
segs=[s for m,s in rows if m>16]
f,p=welch(np.concatenate([s-s.mean(0) for s in segs]),fs=fs,nperseg=int(fs*2),axis=0)
sel=(f>25)&(f<35); print('axis share 25-35Hz', np.round(p[sel].sum(0)/p[sel].sum()*100,1))
