package uk.buzzlab;
import org.json.*; import java.io.*; import java.util.*;
/** Where and how fast a drive went, from its GPS file. Kept as trip.json so lists stay quick. */
final class Trip {
  double[] lat,lon,mph,t; double miles,movingS,topMph; double[] atSpeed=new double[16]; long start;
  static final double MPH=2.23694;
  static Trip load(File dir,long start) throws Exception {
    File c=new File(dir,"trip.json"); String s=Store.read(c); if(s!=null) try{ return from(new JSONObject(s),start); }catch(Exception e){}
    Trip t=build(dir); t.start=start; Store.write(c,t.json().toString()); return t; }
  static Trip build(File dir) throws IOException {
    List<double[]> p=new ArrayList<>(); long base=-1; try(BufferedReader r=new BufferedReader(new FileReader(new File(dir,"gps.csv")))){ r.readLine(); String l; while((l=r.readLine())!=null){ String[] q=l.split(",",-1); if(q.length<5||q[1].isEmpty()||q[2].isEmpty())continue; double la=Double.parseDouble(q[1]),lo=Double.parseDouble(q[2]); if(la!=la||lo!=lo||(la==0&&lo==0))continue; double acc=q[4].isEmpty()?0:Double.parseDouble(q[4]); if(acc>40)continue; long ns=Long.parseLong(q[0]); if(base<0)base=ns; double v=q[3].isEmpty()?Double.NaN:Double.parseDouble(q[3])*MPH; p.add(new double[]{(ns-base)/1e9,la,lo,v}); } }
    Trip t=new Trip(); int n=p.size(); t.lat=new double[n]; t.lon=new double[n]; t.mph=new double[n]; t.t=new double[n];
    for(int i=0;i<n;i++){ double[] q=p.get(i); t.t[i]=q[0]; t.lat[i]=q[1]; t.lon[i]=q[2]; t.mph[i]=q[3]; if(i>0){ double dt=q[0]-p.get(i-1)[0]; if(dt>0&&dt<5&&q[3]==q[3]){ t.miles+=q[3]*dt/3600; if(q[3]>=2){ t.movingS+=dt; t.atSpeed[Math.min(15,(int)(q[3]/5))]+=dt; } } } if(q[3]==q[3])t.topMph=Math.max(t.topMph,q[3]); }
    return t; }
  double avgMoving(){ return movingS>0?miles/(movingS/3600):0; }
  JSONObject json() throws JSONException { JSONObject o=new JSONObject().put("v",1).put("miles",miles).put("moving",movingS).put("top",topMph); JSONArray a=new JSONArray(); for(double v:atSpeed)a.put(Math.round(v)); o.put("at",a); JSONArray la=new JSONArray(),lo=new JSONArray(),v=new JSONArray(),tt=new JSONArray(); for(int i=0;i<lat.length;i++){ la.put(Math.round(lat[i]*1e5)/1e5); lo.put(Math.round(lon[i]*1e5)/1e5); v.put(mph[i]==mph[i]?Math.round(mph[i]*10)/10.0:-1); tt.put(Math.round(t[i]*10)/10.0); } return o.put("lat",la).put("lon",lo).put("mph",v).put("t",tt); }
  static Trip from(JSONObject o,long start) throws JSONException { if(o.optInt("v")!=1)throw new JSONException("old"); Trip t=new Trip(); t.start=start; t.miles=o.getDouble("miles"); t.movingS=o.getDouble("moving"); t.topMph=o.getDouble("top"); JSONArray a=o.getJSONArray("at"); for(int i=0;i<16;i++)t.atSpeed[i]=a.getDouble(i); JSONArray la=o.getJSONArray("lat"),lo=o.getJSONArray("lon"),v=o.getJSONArray("mph"),tt=o.getJSONArray("t"); int n=la.length(); t.lat=new double[n]; t.lon=new double[n]; t.mph=new double[n]; t.t=new double[n]; for(int i=0;i<n;i++){ t.lat[i]=la.getDouble(i); t.lon[i]=lo.getDouble(i); double m=v.getDouble(i); t.mph[i]=m<0?Double.NaN:m; t.t[i]=tt.getDouble(i); } return t; }
}
