package uk.buzzlab;
import android.content.Context;
import android.hardware.*;
import android.media.*;
import android.os.*;
import java.util.*;

/** Plays a waveform on the haptic channel (and optionally the speaker) and records the accelerometer. */
final class Engine {
  static final int SR = 48000, HAPTIC_A = 0x20000000;
  final SensorManager sm; final Sensor acc; final HandlerThread th = new HandlerThread("acc"); final Handler h;
  Engine(Context c) { sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE); acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER); th.start(); h = new Handler(th.getLooper()); }

  static final class Spec {
    String shape = "sine"; double rate = 30, carrier = 160, duty = .5, brake = 0, gain = 1, spk = 0, spkHz = 220, sec = 4, bg = 1;
    /** shape:rate:carrier:duty:brake:gain:spk:spkHz:sec:brakeGain */
    static Spec parse(String s) { String[] p = s.split(":"); Spec q = new Spec(); q.shape = p[0];
      double[] v = {30,160,.5,0,1,0,220,4,1}; for (int i = 1; i < p.length; i++) v[i-1] = Double.parseDouble(p[i]);
      q.rate=v[0];q.carrier=v[1];q.duty=v[2];q.brake=v[3];q.gain=v[4];q.spk=v[5];q.spkHz=v[6];q.sec=v[7];q.bg=v[8]; return q; }
    double haptic(double t) {
      double c = Math.sin(2*Math.PI*carrier*t), ph = rate*t - Math.floor(rate*t);
      switch (shape) {
        case "const": return gain*c;
        case "direct": return gain*Math.sin(2*Math.PI*rate*t);
        case "sine": return gain*c*(1-Math.cos(2*Math.PI*ph))/2;
        case "dsb": return gain*c*Math.sin(Math.PI*rate*t);
        case "pulse": { // on for duty, then optional reversed-phase drive to stop the motor faster
          double cr = Math.sin(2*Math.PI*carrier*ph/rate); if (ph < duty) return gain*cr; if (ph < duty + brake) return -gain*bg*cr; return 0; }
        case "sine2": { double w=(1-Math.cos(2*Math.PI*ph))/2; return gain*Math.sin(2*Math.PI*carrier*ph/rate)*w*w; }
        default: return 0; }
    }
    double speaker(double t) { double ph = rate*t - Math.floor(rate*t); return spk*Math.sin(2*Math.PI*spkHz*t)*(1-Math.cos(2*Math.PI*ph))/2; }
  }

  static short[] render(Spec s) {
    int n = (int)(s.sec*SR); short[] b = new short[n*3];
    for (int i = 0; i < n; i++) { double t = i/(double)SR, e = Math.min(1, Math.min(t, s.sec-t)/.005);
      short a = (short)(32000*s.speaker(t)*e); b[i*3]=a; b[i*3+1]=a; b[i*3+2]=(short)(32000*Math.max(-1,Math.min(1,s.haptic(t)))*e); }
    return b;
  }
  /** One second, whole cycles, no fades: loops without a seam (rates are whole numbers). */
  static short[] renderLoop(Spec s) { int n=SR; short[] b=new short[n*3]; for(int i=0;i<n;i++){ double t=i/(double)SR; short a=(short)(32000*s.speaker(t)); b[i*3]=a;b[i*3+1]=a;b[i*3+2]=(short)(32000*Math.max(-1,Math.min(1,s.haptic(t)))); } return b; }
  AudioTrack track(short[] pcm, int usage) {
    AudioTrack t = new AudioTrack.Builder()
      .setAudioAttributes(new AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setHapticChannelsMuted(false).build())
      .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO|HAPTIC_A).build())
      .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length*2).build();
    t.write(pcm, 0, pcm.length); return t;
  }

  static final class Rec { final ArrayList<float[]> v = new ArrayList<>(); final ArrayList<Long> t = new ArrayList<>(); }
  /** Blocking: play one spec, return samples. Call off the main thread. */
  Rec run(Spec s, int usage) throws Exception {
    final Rec r = new Rec();
    SensorEventListener l = new SensorEventListener() { public void onSensorChanged(SensorEvent e) { synchronized (r) { r.v.add(e.values.clone()); r.t.add(e.timestamp); } } public void onAccuracyChanged(Sensor x, int a) {} };
    AudioTrack t = track(render(s), usage);
    sm.registerListener(l, acc, 0, 0, h);
    try { Thread.sleep(300); t.play(); Thread.sleep((long)(s.sec*1000)+250); } finally { sm.unregisterListener(l); try { t.stop(); } catch (Exception e) {} t.release(); }
    return r;
  }
}
