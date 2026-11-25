package uk.buzzlab;
/** Rolling one-second spectra while recording. Reads a copy of the latest samples on its own thread; display only, nothing here is saved. */
final class Live {
  static final int RN=4096; final float[][] ring=new float[3][RN]; final long[] rt=new long[RN]; volatile long count; // written by the recording thread
  final Dsp sum=new Dsp(); volatile double[] now,avg; volatile int peak; volatile double band; volatile long version; double[][] co,si; double[] win; double sw; int S; double fs; double lastMph=Double.NaN; double[] total=new double[Dsp.NF]; int totalN;
  void put(long t,float x,float y,float z){ int i=(int)(count&(RN-1)); ring[0][i]=x; ring[1][i]=y; ring[2][i]=z; rt[i]=t; count++; }
  /** Call about once a second. */
  void step(double mph){ long n=count; if(n<1200)return; if(S==0){ double d=(rt[(int)((n-1)&(RN-1))]-rt[(int)((n-1001)&(RN-1))])/1e9; fs=1000/d; if(fs<300||fs>1000)return; S=(int)Math.round(fs); win=new double[S]; for(int i=0;i<S;i++){win[i]=.5-.5*Math.cos(2*Math.PI*i/S);sw+=win[i]*win[i];} co=new double[Dsp.NF][S]; si=new double[Dsp.NF][S]; for(int f=0;f<Dsp.NF;f++)for(int i=0;i<S;i++){double p=2*Math.PI*(Dsp.F0+f)*i/fs;co[f][i]=Math.cos(p);si[f][i]=Math.sin(p);} }
    double[][] x=new double[3][S]; for(int a=0;a<3;a++){ double m=0; for(int i=0;i<S;i++){ x[a][i]=ring[a][(int)((n-S+i)&(RN-1))]; m+=x[a][i]; } m/=S; for(int i=0;i<S;i++)x[a][i]=(x[a][i]-m)*win[i]; }
    double[] p=new double[Dsp.NF],ax=new double[3]; for(int a=0;a<3;a++)for(int f=0;f<Dsp.NF;f++){ double re=0,im=0; double[] c=co[f],s=si[f],y=x[a]; for(int i=0;i<S;i++){re+=y[i]*c[i];im+=y[i]*s[i];} double P=2*(re*re+im*im)/(fs*sw); p[f]+=P; int hz=Dsp.F0+f; if(hz>=25&&hz<35)ax[a]+=P; }
    double[] o=now,e=new double[Dsp.NF]; for(int f=0;f<Dsp.NF;f++)e[f]=o==null?p[f]:.65*o[f]+.35*p[f]; now=e; peak=Dsp.peak(e,15,200); band=Dsp.rms(e,25,35);
    boolean steady=mph==mph&&lastMph==lastMph&&Math.abs(mph-lastMph)<2; lastMph=mph; int b=steady?Dsp.bin(mph):-1; if(b>=1){ for(int f=0;f<Dsp.NF;f++){sum.psd[b][f]+=p[f];total[f]+=p[f];} for(int a=0;a<3;a++)sum.ax[b][a]+=ax[a]; sum.n[b]++; totalN++; double[] v=new double[Dsp.NF]; for(int f=0;f<Dsp.NF;f++)v[f]=total[f]/totalN; avg=v; }
    version++; }
}
