package uk.buzzlab;
import android.content.Context; import org.json.*; import java.io.*; import java.nio.file.*; import java.util.*;
/** Cars and drives as files in app-private storage. */
final class Store {
  final File dir, drives, carsF;
  Store(Context c){ dir=c.getFilesDir(); drives=new File(dir,"drives"); drives.mkdirs(); carsF=new File(dir,"cars.json"); }
  static String read(File f){ try{ return new String(Files.readAllBytes(f.toPath())); }catch(Exception e){ return null; } }
  static void write(File f,String s) throws IOException { File t=new File(f.getPath()+".tmp"); Files.write(t.toPath(),s.getBytes()); Files.move(t.toPath(),f.toPath(),StandardCopyOption.REPLACE_EXISTING); }
  JSONArray cars(){ try{ return new JSONArray(read(carsF)); }catch(Exception e){ return new JSONArray(); } }
  JSONObject car(String id){ JSONArray a=cars(); for(int i=0;i<a.length();i++){ JSONObject o=a.optJSONObject(i); if(o.optString("id").equals(id)) return o; } return null; }
  void putCar(JSONObject c) throws Exception { JSONArray a=cars(),b=new JSONArray(); boolean done=false; for(int i=0;i<a.length();i++){ JSONObject o=a.getJSONObject(i); if(o.getString("id").equals(c.getString("id"))){ b.put(c); done=true; } else b.put(o); } if(!done)b.put(c); write(carsF,b.toString()); }
  void delCar(String id) throws Exception { JSONArray a=cars(),b=new JSONArray(); for(int i=0;i<a.length();i++) if(!a.getJSONObject(i).getString("id").equals(id)) b.put(a.get(i)); write(carsF,b.toString()); }
  List<JSONObject> list(){ List<JSONObject> l=new ArrayList<>(); File[] fs=drives.listFiles(); if(fs!=null) for(File f:fs) try{ l.add(new JSONObject(read(new File(f,"meta.json")))); }catch(Exception e){} l.sort((x,y)->Long.compare(y.optLong("start"),x.optLong("start"))); return l; }
  File driveDir(String id){ return new File(drives,id); }
  void saveMeta(JSONObject m) throws Exception { write(new File(driveDir(m.getString("id")),"meta.json"),m.toString()); }
  void delDrive(String id){ File d=driveDir(id); File[] fs=d.listFiles(); if(fs!=null) for(File f:fs) f.delete(); d.delete(); }
  /** "275/50R20" -> rolling distance in metres (unloaded circumference x 0.97); 0 when not understood. */
  static double circ(String s){ java.util.regex.Matcher m=java.util.regex.Pattern.compile("(\\d{3})\\s*/\\s*(\\d{2})\\s*[Zz]?[Rr]\\s*(\\d{2})").matcher(s==null?"":s); if(!m.find()) return 0; double w=Integer.parseInt(m.group(1)),a=Integer.parseInt(m.group(2)),d=Integer.parseInt(m.group(3)); return Math.PI*(d*25.4+2*w*a/100)/1000*.97; }
}
