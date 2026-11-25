package uk.buzzlab;
import android.content.Context; import android.graphics.*; import android.view.*;
/** Heat map (speed by frequency) and spectrum, drawn to scale. */
final class Charts {
  // one orange ramp on navy: dark is near zero, bright is strong
  static final int[] LT={0xFF062B3F,0xFF4A2430,0xFF8E2A1A,0xFFD4320F,0xFFF95A26,0xFFFF8F4D,0xFFFFD9B8}, DK=LT;
  static final int NK=82; // twelfth-octave bands from 2 to about 220 Hz
  static double centre(int k){ return 2*Math.pow(2,k/12.0); } static double edge(int k){ return Math.max(2,centre(k)*Math.pow(2,-1/24.0)); }
  /** Power averaged into twelfth-octave bands, then lightly blended with its neighbours in frequency and speed. Display only. */
  static double[][] smooth(Dsp d){ double[][] v=new double[Dsp.NB][NK],o=new double[Dsp.NB][NK]; for(int b=0;b<Dsp.NB;b++){ if(d.n[b]<3)continue; double[] q=new double[NK]; for(int k=0;k<NK;k++){ double s=0;int c=0; for(int f=(int)Math.ceil(edge(k));f<edge(k+1)&&f<=230;f++){s+=d.psd[b][f-Dsp.F0];c++;} if(c==0){ double f=Math.min(229,centre(k)); int i=(int)f; s=d.psd[b][i-Dsp.F0]*(i+1-f)+d.psd[b][i+1-Dsp.F0]*(f-i); c=1; } q[k]=s/c/d.n[b]; } for(int k=0;k<NK;k++)v[b][k]=.5*q[k]+.25*q[Math.max(0,k-1)]+.25*q[Math.min(NK-1,k+1)]; }
    for(int b=0;b<Dsp.NB;b++){ if(d.n[b]<3)continue; for(int k=0;k<NK;k++){ double s=0,w=0; for(int j=-1;j<=1;j++){ int c=b+j; if(c<0||c>=Dsp.NB||d.n[c]<3)continue; double u=(j==0?1:.35)*Math.min(30,d.n[c]); s+=u*v[c][k]; w+=u; } o[b][k]=s/w; } } return o; }
  /** Colours blend between steps instead of jumping. */
  static int col(int[] R,double a,boolean log){ double t=log?(a<=.005?0:Math.log(a/.005)/Math.log(2)):a/.35*6; t=Math.max(0,Math.min(6,t)); int i=Math.min(5,(int)t); double w=t-i; int x=R[i],y=R[i+1],r=0; for(int sh=0;sh<24;sh+=8)r|=((int)Math.round(((x>>sh)&255)*(1-w)+((y>>sh)&255)*w))<<sh; return 0xFF000000|r; }
  static float ly(double f,float T,float H){ return (float)(T+H-Math.log(Math.max(f,2)/2)/Math.log(115)*H); }
  static class Heat extends View { Dsp d; double circ; boolean dark,log; int mu; Heat(Context c,Dsp d,double circ,boolean dark,int mu){super(c);this.d=d;this.circ=circ;this.dark=dark;this.mu=mu;}
    protected void onMeasure(int w,int h){ int W=MeasureSpec.getSize(w); setMeasuredDimension(W,(int)(W*.85)); }
    protected void onDraw(Canvas g){ float D=getResources().getDisplayMetrics().density,L=30*D,T=6*D,W=getWidth()-L-6*D,H=getHeight()-T-38*D; Paint p=new Paint(1); p.setTextSize(12*D); int hi=8; for(int b=0;b<Dsp.NB;b++)if(d.n[b]>=3)hi=Math.max(hi,b+1); int lo=1; float cw=W/(hi-lo); int[] R=dark?DK:LT;
      double[][] z=smooth(d); for(int b=lo;b<hi;b++){ if(d.n[b]<3)continue; for(int k=0;k<NK;k++){ p.setColor(col(R,Math.sqrt(z[b][k]),log)); g.drawRect(L+(b-lo)*cw+D,ly(Math.min(230,edge(k+1)),T,H),L+(b-lo+1)*cw-D,ly(edge(k),T,H)+1,p);} }
      p.setColor(mu); for(int f:new int[]{2,5,10,20,30,50,100,200}){p.setTextAlign(Paint.Align.RIGHT); g.drawText(""+f,L-4*D,ly(f,T,H)+4*D,p);} p.setTextAlign(Paint.Align.CENTER); for(int m=10;m<=hi*5;m+=10) g.drawText(""+m,L+(m/5f-lo)*cw,T+H+15*D,p); g.drawText("mph (Hz up the side, log scale)",L+W/2,T+H+32*D,p);
      if(circ>0){ p.setColor(0xFFF2F0EA); p.setStrokeWidth(2.5f*D); double f1=lo*5/Dsp.MPH/circ,f2=hi*5/Dsp.MPH/circ; Path q=new Path(); for(int k=0;k<=40;k++){ double f=f1+(f2-f1)*k/40; float X=L+W*k/40,Y=Math.min(T+H,ly(f,T,H)); if(f<2)continue; if(q.isEmpty())q.moveTo(X,Y); else q.lineTo(X,Y);} p.setStyle(Paint.Style.STROKE); g.drawPath(q,p);} } }
  static class Line extends View { double[] a,b; boolean dark,log; int mu; Line(Context c,double[] a,double[] b,boolean dark,int mu,boolean log){super(c);this.a=a;this.b=b;this.dark=dark;this.mu=mu;this.log=log;}
    protected void onMeasure(int w,int h){ int W=MeasureSpec.getSize(w); setMeasuredDimension(W,(int)(W*.65)); }
    protected void onDraw(Canvas g){ float D=getResources().getDisplayMetrics().density,L=42*D,T=8*D,W=getWidth()-L-16*D,H=getHeight()-T-36*D; double mx=.1; for(double[] q:new double[][]{a,b})if(q!=null)for(int f=5;f<=230;f++)mx=Math.max(mx,Math.sqrt(q[f-Dsp.F0])*1.08); final double mn=.001,M=mx; Paint p=new Paint(1); p.setTextSize(12*D); double step=mx>.4?.2:mx>.2?.1:.05; java.util.List<Double> tk=new java.util.ArrayList<>(); if(log)for(double v:new double[]{.001,.01,.1,1}){ if(v<=mx)tk.add(v); } else for(double v=0;v<mx;v+=step)tk.add(v);
      for(double v:tk){ float y=yv(v,M,mn,T,H); p.setColor(mu); p.setAlpha(60); p.setStrokeWidth(D); g.drawLine(L,y,L+W,y,p); p.setAlpha(255); p.setTextAlign(Paint.Align.RIGHT); g.drawText(String.format(java.util.Locale.UK,log?"%.3f":"%.2f",v),L-4*D,y+4*D,p);} p.setTextAlign(Paint.Align.CENTER); for(int f:new int[]{5,10,20,30,50,100,200})g.drawText(""+f,lx(f,L,W),T+H+15*D,p); g.drawText("Hz, log scale",L+W/2,T+H+30*D,p);
      p.setStyle(Paint.Style.STROKE); int k=0; for(double[] q:new double[][]{b,a}){ k++; if(q==null)continue; Path P=new Path(); for(int f=5;f<=230;f++){float X=lx(f,L,W),Y=yv(Math.sqrt(q[f-Dsp.F0]),M,mn,T,H); if(f==5)P.moveTo(X,Y); else P.lineTo(X,Y);} p.setColor(k==2?0xFFFF803A:0xFF9DB4C0); p.setStrokeWidth((k==2?2.5f:1.8f)*D); g.drawPath(P,p);} }
    float lx(double f,float L,float W){ return (float)(L+Math.log(f/5)/Math.log(46)*W); }
    float yv(double v,double mx,double mn,float T,float H){ return (float)(T+H-(log?Math.log(Math.max(v,mn)/mn)/Math.log(mx/mn):v/mx)*H); } }

  /** Speed against time for one drive, coloured by speed. */
  static class Speed extends View { final Trip t; final int mu; Speed(Context c,Trip t,int mu){super(c);this.t=t;this.mu=mu;}
    protected void onMeasure(int w,int h){ int W=MeasureSpec.getSize(w); setMeasuredDimension(W,(int)(W*.45)); }
    protected void onDraw(Canvas g){ float D=getResources().getDisplayMetrics().density,L=34*D,T=8*D,W=getWidth()-L-12*D,H=getHeight()-T-30*D; int n=t.t.length; if(n<2)return; double end=t.t[n-1],top=Math.max(30,Math.ceil(t.topMph/10)*10); Paint p=new Paint(1); p.setTextSize(12*D);
      for(double v=0;v<=top;v+=top>60?20:10){ float y=(float)(T+H-v/top*H); p.setColor(mu); p.setAlpha(55); p.setStrokeWidth(D); g.drawLine(L,y,L+W,y,p); p.setAlpha(255); p.setTextAlign(Paint.Align.RIGHT); g.drawText(""+(int)v,L-5*D,y+4*D,p); }
      p.setTextAlign(Paint.Align.CENTER); int step=end>3600?900:end>1200?300:end>300?60:30; for(int s=0;s<=end;s+=step)g.drawText(s>=3600?String.format(java.util.Locale.UK,"%d:%02dh",s/3600,(s%3600)/60):(s/60)+"m",L+(float)(s/end)*W,T+H+16*D,p);
      p.setStrokeWidth(2.5f*D); p.setStrokeCap(Paint.Cap.ROUND); for(int i=1;i<n;i++){ if(t.mph[i]!=t.mph[i]||t.mph[i-1]!=t.mph[i-1]||t.t[i]-t.t[i-1]>6)continue; p.setColor(MapView.speedColour(t.mph[i])); g.drawLine(L+(float)(t.t[i-1]/end)*W,(float)(T+H-t.mph[i-1]/top*H),L+(float)(t.t[i]/end)*W,(float)(T+H-t.mph[i]/top*H),p); } } }
  /** Simple bars with a label under each. */
  static class Bars extends View { final double[] v; final String[] lab; final int col,mu; final String unit; Bars(Context c,double[] v,String[] lab,String unit,int col,int mu){super(c);this.v=v;this.lab=lab;this.unit=unit;this.col=col;this.mu=mu;}
    protected void onMeasure(int w,int h){ int W=MeasureSpec.getSize(w); setMeasuredDimension(W,(int)(W*.45)); }
    protected void onDraw(Canvas g){ float D=getResources().getDisplayMetrics().density,T=18*D,B=22*D,W=getWidth(),H=getHeight()-T-B; double m=1e-9; for(double x:v)m=Math.max(m,x); Paint p=new Paint(1); p.setTextSize(11*D); p.setTextAlign(Paint.Align.CENTER); float bw=W/v.length; int every=Math.max(1,(int)Math.ceil(v.length/8.0));
      for(int i=0;i<v.length;i++){ float h=(float)(v[i]/m*H),x=i*bw; p.setColor(col); g.drawRoundRect(x+bw*.14f,T+H-h,x+bw*.86f,T+H,3*D,3*D,p); p.setColor(mu); if(i%every==0)g.drawText(lab[i],x+bw/2,T+H+15*D,p); }
      int k=0; for(int i=1;i<v.length;i++)if(v[i]>v[k])k=i; if(v[k]>0){ p.setColor(0xFFF2F0EA); g.drawText(String.format(java.util.Locale.UK,v[k]>=10?"%.0f%s":"%.1f%s",v[k],unit),k*bw+bw/2,T+H-(float)(v[k]/m*H)-5*D,p); } } }
}
