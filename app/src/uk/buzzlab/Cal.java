package uk.buzzlab;
import java.util.*;

/** Self-calibration: plays candidates, measures them with the phone's own accelerometer, keeps the best. */
final class Cal {
  static final class M { double rms, flutter, depth, peakHz, floor; boolean ok; }
  interface Log { void line(String s); boolean cancelled(); }
  final Engine e; final int usage; final Log log; int runs = 0;
  Cal(Engine e, int usage, Log l) { this.e = e; this.usage = usage; log = l; }

  /** rms = carrier level; flutter = strength of the pulsing at the asked rate (m/s2); depth = flutter / level. */
  static M measure(Engine.Rec r, double rate) {
    M m = new M(); int n; long[] t; float[][] v;
    synchronized (r) { n = r.v.size(); t = new long[n]; v = new float[n][]; for (int i=0;i<n;i++){ t[i]=r.t.get(i); v[i]=r.v.get(i);} }
    if (n < 300) return m;
    double fs = (n-1)/((t[n-1]-t[0])/1e9), a1=(n-1)/fs; int s = (int)(fs*.75), q = (int)(n - fs*.35); if (q-s < fs*.6) return m;
    double[] pw = new double[q-s]; // power after a 90 Hz 4th-order high-pass
    for (int ax=0; ax<3; ax++) { double mean=0; for (int i=0;i<n;i++) mean+=v[i][ax]; mean/=n; double[] x=new double[n]; for(int i=0;i<n;i++) x[i]=v[i][ax]-mean; hp(x,fs,90,.541); hp(x,fs,90,1.307); for(int i=s;i<q;i++) pw[i-s]+=x[i]*x[i]; }
    double P0=0; for (double z: pw) P0+=z; P0/=pw.length; m.rms=Math.sqrt(P0);
    double best=0, bf=0, at=0; for (double f=4; f<=80; f+=.25) { double p=proj(pw,t,s,f,P0); if (p>best){best=p;bf=f;} if (Math.abs(f-rate)<.13) at=p; }
    m.peakHz=bf; m.flutter=at/(2*m.rms+1e-9); m.depth=m.flutter/(m.rms+1e-9); m.ok=Math.abs(bf-rate)<=.75; return m;
  }
  static double proj(double[] p, long[] t, int s, double f, double mean) { double a=0,b=0,w0=0; int n=p.length; for (int i=0;i<n;i++){ double w=.5-.5*Math.cos(2*Math.PI*i/(n-1)); double ph=2*Math.PI*f*((t[s+i]-t[s])/1e9); a+=w*(p[i]-mean)*Math.cos(ph); b+=w*(p[i]-mean)*Math.sin(ph); w0+=w; } return 2*Math.hypot(a,b)/w0; }
  static void hp(double[] x, double fs, double fc, double Q) { double w=2*Math.PI*fc/fs,c=Math.cos(w),al=Math.sin(w)/(2*Q),a0=1+al,b0=(1+c)/2/a0,b1=-(1+c)/a0,b2=b0,a1=-2*c/a0,a2=(1-al)/a0,x1=0,x2=0,y1=0,y2=0; for(int i=0;i<x.length;i++){ double y=b0*x[i]+b1*x1+b2*x2-a1*y1-a2*y2; x2=x1;x1=x[i];y2=y1;y1=y;x[i]=y; } }

  static Engine.Spec spec(double rate,double carrier,double duty,double brake,double bg,double gain,double sec){ Engine.Spec s=new Engine.Spec(); s.shape="pulse";s.rate=rate;s.carrier=carrier;s.duty=duty;s.brake=brake;s.bg=bg;s.gain=gain;s.sec=sec; return s; }
  M test(Engine.Spec s) throws Exception { if (log.cancelled()) throw new InterruptedException("cancelled"); runs++; return measure(e.run(s,usage), s.rate); }
  static String f(double d,int p){ return String.format(Locale.UK,"%."+p+"f",d); }

  static final class Best { double rate,carrier,duty,brake,bg,gain; M m; double score; Engine.Spec spec(double sec){ return Cal.spec(rate,carrier,duty,brake,bg,gain,sec);} }

  /** Coordinate search, two passes. Score favours strong pulsing at exactly the asked rate. */
  Best tune(double rate) throws Exception {
    Best b=new Best(); b.rate=rate; b.carrier=150; b.duty=.35; b.brake=.15; b.bg=1; b.gain=.6; eval(b);
    for (int pass=0; pass<2; pass++) {
      double[][] grid = pass==0 ? new double[][]{{135,140,145,155,160,165},{.25,.30,.45,.55},{0,.08,.12,.2,.25},{.4,.7},{.35,.45,.75,.9}}
                                : new double[][]{{b.carrier-3,b.carrier+3},{b.duty-.05,b.duty+.05},{Math.max(0,b.brake-.04),b.brake+.04},{Math.max(.2,b.bg-.2),Math.min(1,b.bg+.2)},{Math.max(.2,b.gain-.1),Math.min(1,b.gain+.1)}};
      for (int dim=0; dim<5; dim++) for (double val: grid[dim]) { Best c=copy(b); if(dim==0)c.carrier=val; else if(dim==1)c.duty=val; else if(dim==2)c.brake=val; else if(dim==3)c.bg=val; else c.gain=val; if(c.duty+c.brake>.95||c.duty<.1) continue; eval(c);
        if (c.score>b.score*1.02) { log.line(f(rate,0)+"/s better: "+desc(c)); b=c; } }
    }
    M m1=test(b.spec(2.5)), m2=test(b.spec(2.5)); b.m=avg(m1,m2); b.m.ok=m1.ok&&m2.ok; log.line("RESULT "+desc(b)); return b;
  }
  void eval(Best b) throws Exception { b.m=test(b.spec(1.9)); b.score=b.m.ok ? b.m.flutter*Math.sqrt(Math.min(1,b.m.depth/.5)) : b.m.flutter*.2; }
  static Best copy(Best a){ Best c=new Best(); c.rate=a.rate;c.carrier=a.carrier;c.duty=a.duty;c.brake=a.brake;c.bg=a.bg;c.gain=a.gain; return c; }
  static M avg(M a,M b){ M m=new M(); m.rms=(a.rms+b.rms)/2;m.flutter=(a.flutter+b.flutter)/2;m.depth=(a.depth+b.depth)/2;m.peakHz=(a.peakHz+b.peakHz)/2; return m; }
  static String desc(Best b){ return f(b.rate,0)+"/s carrier "+f(b.carrier,0)+" duty "+f(b.duty,2)+" brake "+f(b.brake,2)+"x"+f(b.bg,1)+" gain "+f(b.gain,2)+" -> got "+f(b.m.peakHz,2)+"/s level "+f(b.m.rms,3)+" flutter "+f(b.m.flutter,3)+" depth "+f(b.m.depth,2); }
  /** Measured level at each drive, forced to rise, so the intensity slider can work in m/s2. */
  org.json.JSONArray curve(Best b) throws Exception { org.json.JSONArray a=new org.json.JSONArray(); double top=0; for(double g: new double[]{.02,.04,.08,.15,.3,.5,b.gain}){ if(g>b.gain)continue; M m=test(new Object(){ Engine.Spec s(){ Best c=copy(b); c.gain=g; return c.spec(1.9);} }.s()); top=Math.max(top,m.rms); a.put(new org.json.JSONArray().put(g).put(top)); log.line("CURVE "+f(b.rate,0)+"/s gain "+f(g,2)+" level "+f(m.rms,3)); } return a; }
  static double gainFor(org.json.JSONArray a,double level){ try{ double g0=0,l0=0; for(int i=0;i<a.length();i++){ double g=a.getJSONArray(i).getDouble(0),l=a.getJSONArray(i).getDouble(1); if(l>=level)return l-l0<1e-6?g:g0+(g-g0)*(level-l0)/(l-l0); g0=g;l0=l; } return g0; }catch(Exception e){ return .1; } }
  static double top(org.json.JSONArray a){ try{ return a.getJSONArray(a.length()-1).getDouble(1);}catch(Exception e){return 0;} }
  /** Set strength by gain so the measured level is near target, never above what was found. */
  Best toLevel(Best src,double rate,double target) throws Exception { Best b=copy(src); b.rate=rate; double lo=.05, hi=src.gain; eval(b); Best best=b; for(int i=0;i<5;i++){ double g=(lo+hi)/2; Best c=copy(b); c.gain=g; eval(c); if(Math.abs(c.m.rms-target)<Math.abs(best.m.rms-target)) best=c; if(c.m.rms>target) hi=g; else lo=g; } log.line("LEVEL target "+f(target,3)+" "+desc(best)); return best; }
}
