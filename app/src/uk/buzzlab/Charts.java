package uk.buzzlab;
import android.content.Context; import android.graphics.*; import android.view.*;
/** Heat map (speed by frequency) and spectrum, drawn to scale. */
final class Charts {
  // one orange ramp on navy: dark is near zero, bright is strong
  static final int[] LT={0xFF062B3F,0xFF4A2430,0xFF8E2A1A,0xFFD4320F,0xFFF95A26,0xFFFF8F4D,0xFFFFD9B8}, DK=LT;
  static class Heat extends View { Dsp d; double circ; boolean dark; int mu; Heat(Context c,Dsp d,double circ,boolean dark,int mu){super(c);this.d=d;this.circ=circ;this.dark=dark;this.mu=mu;}
    protected void onMeasure(int w,int h){ int W=MeasureSpec.getSize(w); setMeasuredDimension(W,(int)(W*.85)); }
    protected void onDraw(Canvas g){ float D=getResources().getDisplayMetrics().density,L=30*D,T=6*D,W=getWidth()-L-6*D,H=getHeight()-T-38*D; Paint p=new Paint(1); p.setTextSize(12*D); int hi=8; for(int b=0;b<Dsp.NB;b++)if(d.n[b]>=3)hi=Math.max(hi,b+1); int lo=1; float cw=W/(hi-lo); int[] R=dark?DK:LT;
      for(int b=lo;b<hi;b++){ if(d.n[b]<3)continue; for(int f=2;f<=60;f++){ double a=Math.sqrt(d.psd[b][f-Dsp.F0]/d.n[b]); p.setColor(R[Math.min(6,(int)(a/.35*7))]); float y=T+H-(f+.5f-1.5f)/59*H; g.drawRect(L+(b-lo)*cw+D,y,L+(b-lo+1)*cw-D,y+H/59+1,p);} }
      p.setColor(mu); for(int f=10;f<=60;f+=10){p.setTextAlign(Paint.Align.RIGHT); g.drawText(""+f,L-4*D,T+H-(f-1.5f)/59*H+4*D,p);} p.setTextAlign(Paint.Align.CENTER); for(int m=10;m<=hi*5;m+=10) g.drawText(""+m,L+(m/5f-lo)*cw,T+H+15*D,p); g.drawText("mph (Hz up the side)",L+W/2,T+H+32*D,p);
      if(circ>0){ p.setColor(0xFFF2F0EA); p.setStrokeWidth(2.5f*D); double f1=lo*5/Dsp.MPH/circ,f2=hi*5/Dsp.MPH/circ; g.drawLine(L,(float)(T+H-(f1-1.5)/59*H),L+W,(float)(T+H-(f2-1.5)/59*H),p);} } }
  static class Line extends View { double[] a,b; boolean dark; int mu; Line(Context c,double[] a,double[] b,boolean dark,int mu){super(c);this.a=a;this.b=b;this.dark=dark;this.mu=mu;}
    protected void onMeasure(int w,int h){ int W=MeasureSpec.getSize(w); setMeasuredDimension(W,(int)(W*.6)); }
    protected void onDraw(Canvas g){ float D=getResources().getDisplayMetrics().density,L=36*D,T=8*D,W=getWidth()-L-16*D,H=getHeight()-T-36*D; double mx=.1; for(double[] q:new double[][]{a,b})if(q!=null)for(int f=5;f<=120;f++)mx=Math.max(mx,Math.sqrt(q[f-Dsp.F0])*1.08); Paint p=new Paint(1); p.setTextSize(12*D); double step=mx>.4?.2:mx>.2?.1:.05; for(double v=0;v<mx;v+=step){ float y=(float)(T+H-v/mx*H); p.setColor(mu); p.setAlpha(60); p.setStrokeWidth(D); g.drawLine(L,y,L+W,y,p); p.setAlpha(255); p.setTextAlign(Paint.Align.RIGHT); g.drawText(String.format(java.util.Locale.UK,"%.2f",v),L-4*D,y+4*D,p);} p.setTextAlign(Paint.Align.CENTER); for(int f=20;f<=120;f+=20)g.drawText(""+f,L+(f-5)/115f*W,T+H+15*D,p); g.drawText("Hz",L+W/2,T+H+30*D,p);
      p.setStyle(Paint.Style.STROKE); int k=0; for(double[] q:new double[][]{b,a}){ k++; if(q==null)continue; Path P=new Path(); for(int f=5;f<=120;f++){float X=L+(f-5)/115f*W,Y=(float)(T+H-Math.sqrt(q[f-Dsp.F0])/mx*H); if(f==5)P.moveTo(X,Y); else P.lineTo(X,Y);} p.setColor(k==2?0xFFFF803A:0xFF9DB4C0); p.setStrokeWidth((k==2?2.5f:1.8f)*D); g.drawPath(P,p);} } }
}
