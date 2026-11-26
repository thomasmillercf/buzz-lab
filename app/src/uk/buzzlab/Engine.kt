package uk.buzzlab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Plays a waveform on the haptic channel (and optionally the speaker) and records the accelerometer. */
class Engine(c: Context) {
    private val sm = c.getSystemService(SensorManager::class.java)
    private val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val handler = Handler(HandlerThread("acc").apply { start() }.looper)

    /** One test waveform. Written as text, shape:rate:carrier:duty:brake:gain:spk:spkHz:sec:brakeGain. */
    class Spec {
        @JvmField var shape = "sine"

        @JvmField var rate = 30.0

        @JvmField var carrier = 160.0

        @JvmField var duty = .5

        @JvmField var brake = 0.0

        @JvmField var gain = 1.0

        @JvmField var spk = 0.0

        @JvmField var spkHz = 220.0

        @JvmField var sec = 4.0

        @JvmField var bg = 1.0

        /** Motor drive at time t, -1 to 1. */
        fun haptic(t: Double): Double {
            val c = sin(2 * PI * carrier * t)
            val ph = rate * t - floor(rate * t)
            return when (shape) {
                "const" -> gain * c
                "direct" -> gain * sin(2 * PI * rate * t)
                "sine" -> gain * c * (1 - cos(2 * PI * ph)) / 2
                "dsb" -> gain * c * sin(PI * rate * t)
                // on for duty, then an optional reversed drive to stop the motor sooner
                "pulse" -> {
                    val cr = sin(2 * PI * carrier * ph / rate)
                    when {
                        ph < duty -> gain * cr
                        ph < duty + brake -> -gain * bg * cr
                        else -> 0.0
                    }
                }
                "sine2" -> {
                    val w = (1 - cos(2 * PI * ph)) / 2
                    gain * sin(2 * PI * carrier * ph / rate) * w * w
                }
                else -> 0.0
            }
        }

        fun speaker(t: Double): Double {
            val ph = rate * t - floor(rate * t)
            return spk * sin(2 * PI * spkHz * t) * (1 - cos(2 * PI * ph)) / 2
        }

        companion object {
            @JvmStatic fun parse(s: String): Spec {
                val p = s.split(":")
                val v = doubleArrayOf(30.0, 160.0, .5, 0.0, 1.0, 0.0, 220.0, 4.0, 1.0)
                for (i in 1 until p.size) v[i - 1] = p[i].toDouble()
                return Spec().apply {
                    shape = p[0]
                    rate = v[0]
                    carrier = v[1]
                    duty = v[2]
                    brake = v[3]
                    gain = v[4]
                    spk = v[5]
                    spkHz = v[6]
                    sec = v[7]
                    bg = v[8]
                }
            }
        }
    }

    /** Accelerometer readings taken while a waveform played. */
    class Rec {
        @JvmField val v = ArrayList<FloatArray>()

        @JvmField val t = ArrayList<Long>()
    }

    fun track(pcm: ShortArray, usage: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setHapticChannelsMuted(false).build())
        .setAudioFormat(
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SR).setChannelMask(
                AudioFormat.CHANNEL_OUT_STEREO or HAPTIC_A,
            ).build(),
        )
        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
        .also { it.write(pcm, 0, pcm.size) }

    /** Blocking: play one waveform and return what the accelerometer saw. Call off the main thread. */
    @Throws(Exception::class)
    fun run(s: Spec, usage: Int): Rec {
        val r = Rec()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                synchronized(r) {
                    r.v.add(e.values.clone())
                    r.t.add(e.timestamp)
                }
            }
            override fun onAccuracyChanged(x: Sensor, a: Int) {}
        }
        val t = track(render(s), usage)
        sm.registerListener(listener, acc, 0, 0, handler)
        try {
            Thread.sleep(300)
            t.play()
            Thread.sleep((s.sec * 1000).toLong() + 250)
        } finally {
            sm.unregisterListener(listener)
            runCatching { t.stop() }
            t.release()
        }
        return r
    }

    companion object {
        const val SR = 48000

        /** The haptic channel, which Android leaves out of its public list. */
        const val HAPTIC_A = 0x20000000

        private fun pcm(x: Double) = (32000 * x).toInt().toShort()

        /** Stereo sound plus the motor, interleaved, with 5 ms fades. */
        @JvmStatic fun render(s: Spec): ShortArray {
            val n = (s.sec * SR).toInt()
            val b = ShortArray(n * 3)
            for (i in 0 until n) {
                val t = i / SR.toDouble()
                val e = min(1.0, min(t, s.sec - t) / .005)
                val a = pcm(s.speaker(t) * e)
                b[i * 3] = a
                b[i * 3 + 1] = a
                b[i * 3 + 2] = pcm(max(-1.0, min(1.0, s.haptic(t))) * e)
            }
            return b
        }

        /** One second of whole cycles with no fades, so it loops without a seam (rates are whole numbers). */
        @JvmStatic fun renderLoop(s: Spec): ShortArray {
            val b = ShortArray(SR * 3)
            for (i in 0 until SR) {
                val t = i / SR.toDouble()
                val a = pcm(s.speaker(t))
                b[i * 3] = a
                b[i * 3 + 1] = a
                b[i * 3 + 2] = pcm(max(-1.0, min(1.0, s.haptic(t))))
            }
            return b
        }

        /** A recording: each positive half-wave gates the carrier, so the pulses follow the real waveform. All clips share one scale. */
        @JvmStatic
        @Throws(Exception::class)
        fun renderClip(c: JSONObject, carrier: Double, gain: Double, spk: Double): ShortArray {
            val raw = Base64.decode(c.getString("b64"), 0)
            val fs = c.getDouble("fs")
            val full = c.getDouble("full")
            val m = raw.size / 2
            val y = DoubleArray(m) { ((raw[2 * it].toInt() and 255) or (raw[2 * it + 1].toInt() shl 8)).toShort() / 32767.0 * full }
            val n = (m / fs * SR).toInt()
            val b = ShortArray(n * 3)
            for (i in 0 until n) {
                val t = i / SR.toDouble()
                val p = t * fs
                val j = p.toInt()
                val q = p - j
                val x = if (j + 1 < m) y[j] * (1 - q) + y[j + 1] * q else y[m - 1]
                val w = min(1.0, max(0.0, x) / 2.0)
                val e = min(1.0, min(t, (n - i) / SR.toDouble()) / .01)
                val a = pcm(spk * w * e * sin(2 * PI * 220 * t))
                b[i * 3] = a
                b[i * 3 + 1] = a
                b[i * 3 + 2] = pcm(gain * sqrt(w) * e * sin(2 * PI * carrier * t))
            }
            return b
        }
    }
}
