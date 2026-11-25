package uk.buzzlab;
import android.Manifest; import android.content.*; import android.content.pm.PackageManager; import android.util.Log;
/** Starts watching again after a phone restart or an app update, when the user has turned that on. Android allows a location service to start from these two events only with "Allow all the time". */
public class Boot extends BroadcastReceiver {
  public void onReceive(Context c,Intent i){ String a=i.getAction(); if(!Intent.ACTION_BOOT_COMPLETED.equals(a)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(a))return; SharedPreferences ui=c.getSharedPreferences("ui",0);
    if(!ui.getBoolean("auto",false)||!ui.getBoolean("autoBoot",false))return; if(c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED){ Log.i("BUZZLAB","AUTO not restarted: needs Allow all the time"); return; }
    try{ c.startForegroundService(new Intent(c,RecService.class).setAction(RecService.WATCH)); Log.i("BUZZLAB","AUTO restarted after "+a); }catch(Exception e){ Log.w("BUZZLAB","AUTO restart refused: "+e); } }
}
