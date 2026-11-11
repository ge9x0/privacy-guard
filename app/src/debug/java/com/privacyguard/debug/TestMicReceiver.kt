package com.privacyguard.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.concurrent.thread

/**
 * Debug only. Starts the microphone after `delay` seconds for `secs` seconds, to test
 * MonitorService's screen-off notification. Audio isn't stored: it is read and discarded.
 *
 *   adb shell am broadcast -n com.privacyguard/.debug.TestMicReceiver --ei delay 8 --ei secs 6
 */
class TestMicReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val delay = intent.getIntExtra("delay", 8)
        val secs = intent.getIntExtra("secs", 6)
        val pending = goAsync()
        thread {
            try {
                Thread.sleep(delay * 1000L)
                val rate = 16000
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("MissingPermission")
                val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
                rec.startRecording()
                Log.i(TAG, "mikrofoni u nis (state=${rec.recordingState})")
                val buf = ShortArray(min)
                val end = System.currentTimeMillis() + secs * 1000L
                while (System.currentTimeMillis() < end) rec.read(buf, 0, buf.size)
                rec.stop(); rec.release()
                Log.i(TAG, "mikrofoni u ndal")
            } catch (e: Throwable) {
                Log.w(TAG, "prova deshtoi", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object { private const val TAG = "PG.Test" }
}
