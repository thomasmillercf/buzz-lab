package uk.buzzlab;
import android.content.Context; import android.graphics.*; import android.view.*;
/** Heat map (speed by frequency) and spectrum, drawn to scale. */
final class Charts {
  // one orange ramp on navy: dark is near zero, bright is strong
  static final int[] LT={0xFF062B3F,0xFF4A2430,0xFF8E2A1A,0xFFD4320F,0xFFF95A26,0xFFFF8F4D,0xFFFFD9B8}, DK=LT;
  static float ly(double f,float T,float H){ return (float)(T+H-Math.log(Math.max(f,2)/2)/Math.log(115)*H); }
  /** Under 0.01, then each step doubles, up to 0.32 and above. */
  static int step(double a){ return a<.01?0:Math.min(6,1+(int)Math.floor(Math.log(a/.01)/Math.log(2))); }
  static class Heat extends View { Dsp d; double circ; boolean dark,log; int mu; Heat(Context c,Dsp d,double circ,boolean dark,int mu){super(c);this.d=d;this.circ=circ;this.dark=dark;this.mu=mu;}
    protected void onMeasure(int w,int h){ int W=MeasureSpec.getSize(w); setMeasuredDimension(W,(int)(W*.85)); }
    protected void onDraw(Canvas g){ float D=getResources().getDisplayMetrics().density,L=30*D,T=6*D,W=getWidth()-L-6*D,H=getHeight()-T-38*D; Paint p=new Paint(1); p.setTextSize(12*D); int hi=8; for(int b=0;b<Dsp.NB;b++)if(d.n[b]>=3)hi=Math.max(hi,b+1); int lo=1; float cw=W/(hi-lo); int[] R=dark?DK:LT;
      for(int b=lo;b<hi;b++){ if(d.n[b]<3)continue; for(int f=2;f<230;f++){ double a=Math.sqrt(d.psd[b][f-Dsp.F0]/d.n[b]); p.setColor(R[log?step(a):Math.min(6,(int)(a/.35*7))]); g.drawRect(L+(b-lo)*cw+D,ly(f+1,T,H),L+(b-lo+1)*cw-D,ly(f,T,H)+1,p);} }
      p.setColor(mu); for(int f:new int[]{2,5,10,20,30,50,100,200}){p.setTextAlign(Paint.Align.RIGHT); g.drawText(""+f,L-4*D,ly(f,T,H)+4*D,p);} p.setTextAlign(Paint.Align.CENTER); for(int m=10;m<=hi*5;m+=10) g.drawText(""+m,L+(m/5f-lo)*cw,T+H+15*D,p); g.drawText("mph (Hz up the side, log scale)",L+W/2,T+H+32*D,p);
      if(circ>0){ p.setColor(0xFFF2F0EA); p.setStrokeWidth(2.5f*D); double f1=lo*5/Dsp.MPH/circ,f2=hi*5/Dsp.MPH/circ; Path q=new Path(); for(int k=0;k<=40;k++){ double f=f1+(f2-f1)*k/40; float X=L+W*k/40,Y=Math.min(T+H,ly(f,T,H)); if(f<2)continue; if(q.isEmpty())q.moveTo(X,Y); else q.lineTo(X,Y);} p.setStyle(Paint.Style.STROKE); g.drawPath(q,p);} } }
  static class Line extends View { double[] a,b; boolean dark,log; int mu; Line(Context c,double[] a,double[] b,boolean dark,int mu,boolean log){super(c);this.a=a;this.b=b;this.dark=dark;this.mu=mu;this.log=log;}
    protected void onMeasure(int w,int h){ int W=MeasureSpec.getSize(w); setMeasuredDimension(W,(int)(W*.65)); }
    protected void onDraw(Canvas g){ float D=getResources().getDisplayMetrics().density,L=42*D,T=8*D,W=getWidth()-L-16*D,H=getHeight()-T-36*D; double mx=.1; for(double[] q:new double[][]{a,b})if(q!=null)for(int f=5;f<=230;f++)mx=Math.max(mx,Math.sqrt(q[f-Dsp.F0])*1.08); final double mn=.001,M=mx; Paint p=new Paint(1); p.setTextSize(12*D); double step=mx>.4?.2:mx>.2?.1:.05; java.util.List<Double> tk=new java.util.ArrayList<>(); if(log)for(double v:new double[]{.001,.01,.1,1}){ if(v<=mx)tk.add(v); } else for(double v=0;v<mx;v+=step)tk.add(v);
      for(double v:tk){ float y=yv(v,M,mn,T,H); p.setColor(mu); p.setAlpha(60); p.setStrokeWidth(D); g.drawLine(L,y,L+W,y,p); p.setAlpha(255); p.setTextAlign(Paint.Align.RIGHT); g.drawText(String.format(java.util.Locale.UK,log?"%.3f":"%.2f",v),L-4*D,y+4*D,p);} p.setTextAlign(Paint.Align.CENTER); for(int f:new int[]{5,10,20,30,50,100,200})g.drawText(""+f,lx(f,L,W),T+H+15*D,p); g.drawText("Hz, log scale",L+W/2,T+H+30*D,p);
      p.setStyle(Paint.Style.STROKE); int k=0; for(double[] q:new double[][]{b,a}){ k++; if(q==null)continue; Path P=new Path(); for(int f=5;f<=230;f++){float X=lx(f,L,W),Y=yv(Math.sqrt(q[f-Dsp.F0]),M,mn,T,H); if(f==5)P.moveTo(X,Y); else P.lineTo(X,Y);} p.setColor(k==2?0xFFFF803A:0xFF9DB4C0); p.setStrokeWidth((k==2?2.5f:1.8f)*D); g.drawPath(P,p);} }
    float lx(double f,float L,float W){ return (float)(L+Math.log(f/5)/Math.log(46)*W); }
    float yv(double v,double mx,double mn,float T,float H){ return (float)(T+H-(log?Math.log(Math.max(v,mn)/mn)/Math.log(mx/mn):v/mx)*H); } }
}
