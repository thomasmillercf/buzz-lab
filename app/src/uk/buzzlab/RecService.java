package uk.buzzlab;
import android.app.*; import android.content.*; import android.content.pm.ServiceInfo; import android.hardware.*; import android.location.*; import android.os.*; import android.util.Log; import org.json.*; import java.io.*; import java.util.*;
/** Records accelerometer at full rate and GPS speed until stopped. Keeps going with the screen off. */
public class RecService extends Service implements SensorEventListener, LocationListener {
  static volatile RecService live; static volatile String lastError;  volatile long samples,fixes,startMs; volatile double mph=Double.NaN; DataOutputStream acc; PrintWriter gps,marks; JSONObject meta; Store st; SensorManager sm; LocationManager lm; HandlerThread th; PowerManager.WakeLock wl; volatile boolean closed; volatile String error;
  public IBinder onBind(Intent i){ return null; }
  public int onStartCommand(Intent i,int f,int id){
    if(live!=null||i==null){ if(i==null)stopSelf(); return START_NOT_STICKY; }
    NotificationManager nm=getSystemService(NotificationManager.class); nm.createNotificationChannel(new NotificationChannel("rec","Recording",NotificationManager.IMPORTANCE_LOW));
    Notification n=new Notification.Builder(this,"rec").setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("Recording drive").setContentText("Vibration and speed. Tap to open.").setOngoing(true).setContentIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE)).build();
    lastError=null; try{ startForeground(1,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION); }catch(Exception e){ lastError=""+e.getMessage(); Log.e("BUZZLAB","REC fg",e); stopSelf(); return START_NOT_STICKY; }
    th=new HandlerThread("rec"); th.start(); final Handler h=new Handler(th.getLooper()); final Intent in=i;
    h.post(()->{ try{ st=new Store(this); startMs=System.currentTimeMillis(); String did=""+startMs; File d=st.driveDir(did); d.mkdirs();
      meta=new JSONObject().put("id",did).put("start",startMs).put("car",in.getStringExtra("car")).put("place",in.getStringExtra("place")).put("note",in.getStringExtra("note")).put("tyre",in.getStringExtra("tyre")).put("recording",true); st.saveMeta(meta);
      acc=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(d,"acc.bin")),1<<16)); gps=new PrintWriter(new BufferedWriter(new FileWriter(new File(d,"gps.csv")))); gps.println("t_ns,lat,lon,speed_ms,acc_m,bearing"); marks=new PrintWriter(new FileWriter(new File(d,"marks.csv")),true); marks.println("t_ns,label");
      wl=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"buzzlab:rec"); wl.acquire(4*3600*1000L);
      sm=getSystemService(SensorManager.class); sm.registerListener(this,sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),0,0,h); lm=getSystemService(LocationManager.class); lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this,th.getLooper()); live=this; Log.i("BUZZLAB","REC start "+did);
    }catch(Exception e){ error=""+e; lastError=""+e.getMessage(); Log.e("BUZZLAB","REC fail",e); stopSelf(); } });
    return START_NOT_STICKY; }
  public void onSensorChanged(SensorEvent e){ if(closed)return; try{ acc.writeLong(e.timestamp); for(int k=0;k<3;k++)acc.writeFloat(e.values[k]); if(++samples%4740==0){ acc.flush(); gps.flush(); } }catch(IOException x){ error="Storage: "+x.getMessage(); } }
  public void onAccuracyChanged(Sensor s,int a){}
  public void onLocationChanged(Location l){ if(closed)return; fixes++; if(l.hasSpeed())mph=l.getSpeed()*Dsp.MPH; gps.println(l.getElapsedRealtimeNanos()+","+l.getLatitude()+","+l.getLongitude()+","+(l.hasSpeed()?l.getSpeed():"")+","+l.getAccuracy()+","+(l.hasBearing()?l.getBearing():"")); }
  public void onProviderDisabled(String p){} public void onProviderEnabled(String p){} public void onStatusChanged(String p,int s,Bundle b){}
  void mark(String s){ if(!closed)marks.println(SystemClock.elapsedRealtimeNanos()+","+s); }
  /** Returns at once; the files are finished on the recording thread, and live is cleared only when they are closed. */
  public void onDestroy(){ final Context app=getApplicationContext(); final HandlerThread t=th; if(t!=null)new Handler(t.getLooper()).post(()->{ finish(app); t.quitSafely(); }); super.onDestroy(); }
  void finish(Context app){ closed=true; try{ if(sm!=null)sm.unregisterListener(this); if(lm!=null)lm.removeUpdates(this); if(acc!=null)acc.close(); if(gps!=null)gps.close(); if(marks!=null)marks.close(); if(meta!=null){ meta.put("recording",false).put("end",System.currentTimeMillis()).put("samples",samples).put("fixes",fixes); if(error!=null)meta.put("error",error); st.saveMeta(meta);} }catch(Exception e){ lastError=""+e.getMessage(); Log.e("BUZZLAB","REC close",e);} if(wl!=null&&wl.isHeld())wl.release(); if(live==this)live=null; try{ SyncJob.schedule(app); }catch(Exception e){ Log.w("BUZZLAB","sched "+e); } Log.i("BUZZLAB","REC stop "+samples+" "+fixes); }
}
