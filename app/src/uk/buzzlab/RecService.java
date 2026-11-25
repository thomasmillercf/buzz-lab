package uk.buzzlab;
import android.app.*; import android.content.*; import android.content.pm.ServiceInfo; import android.hardware.*; import android.location.*; import android.os.*; import android.util.Log; import org.json.*; import java.io.*; import java.util.*;
/**
 * Records accelerometer at full rate and GPS speed, with the screen off too.
 * It can also watch: GPS only, starting a drive by itself once moving and saving it after a stop.
 * One service does both, because Android won't let an app start location work from the background.
 */
public class RecService extends Service implements SensorEventListener, LocationListener {
  static final String RECORD="record", WATCH="watch", UNWATCH="unwatch";
  static final double START_MPH=15, STOP_MPH=3; static final int START_FIXES=3; static final long STOP_AFTER_MS=180_000, NO_GPS_MS=300_000;
  static volatile RecService live, svc; static volatile String lastError;
  // per drive
  volatile boolean discard, paused, auto; volatile long pausedMs, pauseAt; volatile double km, topMph; long lastFix; int pauses; volatile Live charts=new Live(); volatile long samples,fixes,startMs; DataOutputStream acc; PrintWriter gps,marks; JSONObject meta; volatile boolean closed=true; volatile String error;
  // service
  volatile double mph=Double.NaN; volatile boolean watch; Store st; SensorManager sm; LocationManager lm; HandlerThread th,lt; Handler h; PowerManager.WakeLock wl; int fast; long slowSince, lastMoving, lastAnyFix; long interval=-1;
  public IBinder onBind(Intent i){ return null; }

  public int onStartCommand(Intent i,int f,int id){
    String a=i==null?WATCH:i.getAction()==null?RECORD:i.getAction();
    lastError=null; try{ startForeground(1,note(),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION); }catch(Exception e){ lastError=""+e.getMessage(); Log.e("BUZZLAB","REC fg",e); stopSelf(); return START_NOT_STICKY; }
    if(i==null&&!getSharedPreferences("ui",0).getBoolean("auto",false)&&live==null){ stopSelf(); return START_NOT_STICKY; }
    if(th==null){ st=new Store(this); sm=getSystemService(SensorManager.class); lm=getSystemService(LocationManager.class); th=new HandlerThread("rec"); th.start(); h=new Handler(th.getLooper()); svc=this; }
    final Intent in=i; h.post(()->{
      if(WATCH.equals(a)){ watch=true; Log.i("BUZZLAB","AUTO watching"); }
      else if(UNWATCH.equals(a)){ watch=false; Log.i("BUZZLAB","AUTO off"); if(live==null){ stopSelf(); return; } }
      else if(RECORD.equals(a)&&live==null&&in!=null) startDrive(in.getStringExtra("car"),in.getStringExtra("place"),in.getStringExtra("tyre"),false);
      gpsRate(); refresh(); });
    return START_STICKY; }

  Notification note(){ NotificationManager nm=getSystemService(NotificationManager.class); nm.createNotificationChannel(new NotificationChannel("rec","Recording",NotificationManager.IMPORTANCE_LOW));
    boolean r=live!=null; return new Notification.Builder(this,"rec").setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle(r?(auto?"Recording drive (started by itself)":"Recording drive"):"Watching for a drive").setContentText(r?"Vibration and speed. Tap to open.":"Starts recording once you're above "+(int)START_MPH+" mph. Turn off in Settings.").setOngoing(true).setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE)).build(); }
  void refresh(){ try{ getSystemService(NotificationManager.class).notify(1,note()); }catch(Exception e){} }
  /** Once a second while recording; every 5 s while watching and moving lately; every 20 s while still. */
  void gpsRate(){ long want=live!=null?1000:(System.currentTimeMillis()-lastMoving<120_000?5000:20000); if(want==interval)return; interval=want; try{ lm.removeUpdates(this); lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,want,0,this,th.getLooper()); }catch(SecurityException e){ lastError="Location permission is off"; Log.w("BUZZLAB","gps "+e); } }

  void startDrive(String car,String place,String tyre,boolean byItself){ try{ discard=false; paused=false; pausedMs=0; km=0; topMph=0; lastFix=0; pauses=0; samples=0; fixes=0; error=null; auto=byItself; charts=new Live(); slowSince=0;
      startMs=System.currentTimeMillis(); String did=""+startMs; File d=st.driveDir(did); d.mkdirs();
      meta=new JSONObject().put("id",did).put("start",startMs).put("car",car).put("place",place).put("note","").put("tyre",tyre).put("recording",true).put("auto",byItself); st.saveMeta(meta);
      acc=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(d,"acc.bin")),1<<16)); gps=new PrintWriter(new BufferedWriter(new FileWriter(new File(d,"gps.csv")))); gps.println("t_ns,lat,lon,speed_ms,acc_m,bearing"); marks=new PrintWriter(new FileWriter(new File(d,"marks.csv")),true); marks.println("t_ns,label");
      wl=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"buzzlab:rec"); wl.acquire(4*3600*1000L); closed=false;
      sm.registerListener(this,sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),0,0,h); live=this; Log.i("BUZZLAB","REC start "+did+(byItself?" auto":""));
      lt=new HandlerThread("live",android.os.Process.THREAD_PRIORITY_BACKGROUND); lt.start(); final Handler lh=new Handler(lt.getLooper()); final Live lv=charts; lh.post(new Runnable(){ public void run(){ if(closed)return; try{ if(!paused)lv.step(mph); }catch(Throwable x){ Log.w("BUZZLAB","live "+x); } lh.postDelayed(this,1000); } });
      gpsRate(); refresh();
    }catch(Exception e){ error=""+e; lastError=""+e.getMessage(); Log.e("BUZZLAB","REC fail",e); endDrive(true); } }

  public void onSensorChanged(SensorEvent e){ if(closed||paused)return; try{ acc.writeLong(e.timestamp); for(int k=0;k<3;k++)acc.writeFloat(e.values[k]); charts.put(e.timestamp,e.values[0],e.values[1],e.values[2]); if(++samples%4740==0){ acc.flush(); gps.flush(); } }catch(IOException x){ error="Storage: "+x.getMessage(); } }
  public void onAccuracyChanged(Sensor s,int a){}
  public void onLocationChanged(Location l){ long now=System.currentTimeMillis(); lastAnyFix=now; double v=l.hasSpeed()?l.getSpeed()*Dsp.MPH:Double.NaN; if(!paused||live==null)mph=v; if(v>=STOP_MPH)lastMoving=now;
    if(live!=null&&!closed&&!paused){ fixes++; if(l.hasSpeed()){ topMph=Math.max(topMph,v); long t=l.getElapsedRealtimeNanos(); if(lastFix>0&&t-lastFix<5_000_000_000L)km+=l.getSpeed()*(t-lastFix)/1e12; lastFix=t; } gps.println(l.getElapsedRealtimeNanos()+","+l.getLatitude()+","+l.getLongitude()+","+(l.hasSpeed()?l.getSpeed():"")+","+l.getAccuracy()+","+(l.hasBearing()?l.getBearing():""));
      if(auto){ if(v<STOP_MPH){ if(slowSince==0)slowSince=now; else if(now-slowSince>STOP_AFTER_MS){ Log.i("BUZZLAB","AUTO stopped moving, saving"); endDrive(false); } } else slowSince=0; } }
    else if(live==null&&watch){ fast=v>=START_MPH?fast+1:0; if(fast>=START_FIXES){ fast=0; SharedPreferences ui=getSharedPreferences("ui",0); String car=ui.getString("car",""); JSONObject c=st.car(car); int pl=ui.getInt("place",0); if(c==null){ JSONArray cs=st.cars(); c=cs.optJSONObject(0); } if(c!=null)startDrive(c.optString("id"),MainActivity.PLACES[Math.max(0,Math.min(pl,MainActivity.PLACES.length-1))],c.optString("tyre"),true); } }
    gpsRate(); }
  public void onProviderDisabled(String p){} public void onProviderEnabled(String p){} public void onStatusChanged(String p,int s,Bundle b){}

  /** Stop taking readings without ending the drive. */
  void pause(){ if(paused)return; pauseAt=System.currentTimeMillis(); paused=true; pauses++; lastFix=0; mph=Double.NaN; try{ acc.flush(); gps.flush(); }catch(IOException x){} }
  void resume(){ if(!paused)return; pausedMs+=System.currentTimeMillis()-pauseAt; paused=false; slowSince=0; }
  long activeMs(){ return System.currentTimeMillis()-startMs-pausedMs-(paused?System.currentTimeMillis()-pauseAt:0); }
  void mark(String s){ if(!closed)marks.println(SystemClock.elapsedRealtimeNanos()+","+s); }

  /** Save or discard the drive. Watching carries on; otherwise the service ends. Safe to call from any thread. */
  static void endNow(boolean discard){ final RecService s=svc; if(s==null||s.h==null)return; s.h.post(()->{ s.endDrive(discard); if(!s.watch)s.stopSelf(); }); }
  void endDrive(boolean dropIt){ if(live!=this)return; discard=dropIt; closed=true; if(lt!=null)lt.quitSafely(); final Context app=getApplicationContext();
    try{ sm.unregisterListener(this); if(acc!=null)acc.close(); if(gps!=null)gps.close(); if(marks!=null)marks.close(); if(meta!=null&&discard){ st.delDrive(meta.getString("id")); Log.i("BUZZLAB","REC discarded"); } else if(meta!=null){ meta.put("recording",false).put("end",System.currentTimeMillis()).put("pauses",pauses).put("paused_s",(pausedMs+(paused?System.currentTimeMillis()-pauseAt:0))/1000).put("samples",samples).put("fixes",fixes); if(error!=null)meta.put("error",error); st.saveMeta(meta);} }catch(Exception e){ lastError=""+e.getMessage(); Log.e("BUZZLAB","REC close",e); }
    if(wl!=null&&wl.isHeld())wl.release(); live=null; paused=false; if(!discard)try{ SyncJob.schedule(app,120000); }catch(Exception e){ Log.w("BUZZLAB","sched "+e); } Log.i("BUZZLAB","REC stop "+samples+" "+fixes); gpsRate(); refresh(); }

  /** Returns at once; the files are finished on the recording thread, and live is cleared only when they are closed. */
  public void onDestroy(){ final HandlerThread t=th; if(t!=null)h.post(()->{ endDrive(discard); try{ lm.removeUpdates(this); }catch(Exception e){} if(svc==this)svc=null; t.quitSafely(); }); else if(svc==this)svc=null; super.onDestroy(); }
}
