package uk.buzzlab;
import org.json.*; import java.io.*; import java.util.*;
/** Vibration by speed. Sums are stored, so drives combine exactly. Method matches analyse.py: steady 2 s windows, 1 s Hann segments. */
final class Dsp {
  static final int F0=2,F1=230,NF=F1-F0+1,NB=16; static final double MPH=2.23694;
  final double[][] psd=new double[NB][NF]; final double[][] ax=new double[NB][3]; final int[] n=new int[NB]; double maxMph,km; 
  static int bin(double mph){ return mph<0?-1:(int)(mph/5)<NB?(int)(mph/5):-1; }
  void add(Dsp o){ for(int b=0;b<NB;b++){ n[b]+=o.n[b]; for(int f=0;f<NF;f++)psd[b][f]+=o.psd[b][f]; for(int a=0;a<3;a++)ax[b][a]+=o.ax[b][a]; } maxMph=Math.max(maxMph,o.maxMph); km+=o.km; }
  JSONObject json() throws JSONException { JSONObject o=new JSONObject(); JSONArray N=new JSONArray(),P=new JSONArray(),A=new JSONArray(); for(int b=0;b<NB;b++){ N.put(n[b]); JSONArray p=new JSONArray(),q=new JSONArray(); if(n[b]>0){ for(double v:psd[b])p.put((double)(float)v); for(double v:ax[b])q.put((double)(float)v);} P.put(p);A.put(q);} return o.put("v",2).put("n",N).put("psd",P).put("ax",A).put("maxMph",maxMph).put("km",km); }
  static Dsp from(JSONObject o) throws JSONException { Dsp d=new Dsp(); for(int b=0;b<NB;b++){ d.n[b]=o.getJSONArray("n").getInt(b); JSONArray p=o.getJSONArray("psd").getJSONArray(b),q=o.getJSONArray("ax").getJSONArray(b); if(d.n[b]>0&&p.length()!=NF)throw new JSONException("older stats"); for(int f=0;f<p.length();f++)d.psd[b][f]=p.getDouble(f); for(int a=0;a<q.length();a++)d.ax[b][a]=q.getDouble(a);} d.maxMph=o.optDouble("maxMph");d.km=o.optDouble("km"); return d; }

  /** acc: t seconds, xyz; gps: t seconds, lat, lon, m/s, accuracy. */
  static Dsp analyse(double[] t,float[][] v,double[][] gps){
    Dsp d=new Dsp(); int m=t.length; if(m<2000||gps.length<3) return d; double[] df=new double[Math.min(m-1,20000)]; for(int i=0;i<df.length;i++)df[i]=t[i+1]-t[i]; Arrays.sort(df); double fs=1/df[df.length/2]; int W=(int)(2*fs),S=(int)Math.round(fs);
    double[] win=new double[S]; double sw=0; for(int i=0;i<S;i++){win[i]=.5-.5*Math.cos(2*Math.PI*i/S);sw+=win[i]*win[i];}
    double[][] co=new double[NF][S],si=new double[NF][S]; for(int f=0;f<NF;f++)for(int i=0;i<S;i++){double p=2*Math.PI*(F0+f)*i/fs;co[f][i]=Math.cos(p);si[f][i]=Math.sin(p);}
    for(int i=1;i<gps.length;i++){ if(gps[i][3]==gps[i][3])d.maxMph=Math.max(d.maxMph,gps[i][3]*MPH); double dt=gps[i][0]-gps[i-1][0]; if(gps[i][3]==gps[i][3]&&dt<5)d.km+=gps[i][3]*dt/1000; }
    double t0=Math.max(t[0],gps[0][0]),t1=Math.min(t[m-1],gps[gps.length-1][0]); int gi=0; double[][] x=new double[3][W]; int ai=0; double[] gv=new double[W/8+1];
    for(double s=t0;s+2<t1;s+=2){
      double sum=0,sq=0;int k=0,q=0; boolean bad=false; for(double u=s;u<s+2;u+=.1){ while(q<gps.length-2&&gps[q+1][0]<u)q++; double a=gps[q][3],b=gps[q+1][3]; if(a!=a||b!=b||gps[q+1][0]-gps[q][0]>4){bad=true;break;} double w=(u-gps[q][0])/(gps[q+1][0]-gps[q][0]),z=(a+(b-a)*Math.max(0,Math.min(1,w)))*MPH; sum+=z;sq+=z*z;k++; }
      if(bad)continue; double mean=sum/k,sd=Math.sqrt(Math.max(0,sq/k-mean*mean)); if(sd>2)continue; int b=bin(mean); if(b<0)continue;
      while(ai<m-2&&t[ai+1]<s)ai++; int j=ai; boolean gap=false; for(int i=0;i<W;i++){ double u=s+i/fs; while(j<m-2&&t[j+1]<u)j++; double h=t[j+1]-t[j]; if(h>.05){gap=true;break;} double w=(u-t[j])/h; for(int a=0;a<3;a++)x[a][i]=v[j][a]+(v[j+1][a]-v[j][a])*w; } if(gap)continue;
      double[] p=new double[NF],pa=new double[3]; int seg=0; for(int o=0;o+S<=W;o+=S/2){ seg++; for(int a=0;a<3;a++){ double me=0; for(int i=0;i<S;i++)me+=x[a][o+i]; me/=S; for(int f=0;f<NF;f++){ double re=0,im=0; for(int i=0;i<S;i++){double z=(x[a][o+i]-me)*win[i];re+=z*co[f][i];im+=z*si[f][i];} double P=2*(re*re+im*im)/(fs*sw); p[f]+=P; int hz=F0+f; if(hz>=25&&hz<35)pa[a]+=P; } } }
      for(int f=0;f<NF;f++)d.psd[b][f]+=p[f]/seg; for(int a=0;a<3;a++)d.ax[b][a]+=pa[a]/seg; d.n[b]++;
    } return d;
  }
  /** Mean PSD over a speed range, or null. */
  double[] mean(int loMph,int hiMph){ double[] s=new double[NF];int c=0; for(int b=0;b<NB;b++) if(b*5>=loMph&&b*5+5<=hiMph&&n[b]>0){c+=n[b]; for(int f=0;f<NF;f++)s[f]+=psd[b][f];} if(c==0)return null; for(int f=0;f<NF;f++)s[f]/=c; return s; }
  int count(int lo,int hi){int c=0; for(int b=0;b<NB;b++) if(b*5>=lo&&b*5+5<=hi)c+=n[b]; return c;}
  double foreAft(int lo,int hi){ double y=0,t=0; for(int b=0;b<NB;b++) if(b*5>=lo&&b*5+5<=hi){y+=ax[b][1];t+=ax[b][0]+ax[b][1]+ax[b][2];} return t>0?y/t:0; }
  static double rms(double[] p,int a,int b){ double s=0; for(int h=a;h<b;h++) if(h>=F0&&h<=F1)s+=p[h-F0]; return Math.sqrt(s); }
  static int peak(double[] p,int a,int b){ int k=a; for(int h=a;h<=b;h++) if(p[h-F0]>p[k-F0])k=h; return k; }

  static Dsp load(File dir) throws Exception {
    File c=new File(dir,"stats.json"); String s=Store.read(c); if(s!=null) try{ return from(new JSONObject(s)); }catch(Exception e){}
    Object[] r=raw(dir); Dsp d=analyse((double[])r[0],(float[][])r[1],(double[][])r[2]); Store.write(c,d.json().toString()); return d; }
  static Object[] raw(File dir) throws Exception {
    File f=new File(dir,"acc.bin"); int n=(int)(f.length()/20); double[] t=new double[n]; float[][] v=new float[n][3]; long base=0; try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(f),1<<16))){ for(int i=0;i<n;i++){ long ns=in.readLong(); if(i==0)base=ns; t[i]=(ns-base)/1e9; for(int a=0;a<3;a++)v[i][a]=in.readFloat(); } }
    List<double[]> g=new ArrayList<>(); try(BufferedReader r=new BufferedReader(new FileReader(new File(dir,"gps.csv")))){ r.readLine(); String l; while((l=r.readLine())!=null){ String[] p=l.split(",",-1); if(p.length<5||p[3].isEmpty())continue; g.add(new double[]{(Long.parseLong(p[0])-base)/1e9,0,0,Double.parseDouble(p[3]),Double.parseDouble(p[4])}); } }
    return new Object[]{t,v,g.toArray(new double[0][])}; }
}
