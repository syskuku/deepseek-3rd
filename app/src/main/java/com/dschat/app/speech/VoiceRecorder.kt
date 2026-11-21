package com.dschat.app.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 录音：AudioRecord 16kHz 单声道 16-bit PCM，对照 demo recorder.ts。
 * 按住说话期间持续采集，松手停止并编码 WAV；上滑取消丢弃。
 */
class VoiceRecorder {
    val sampleRate = 16000
    private val minBuf = AudioRecord.getMinBufferSize(
        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(4096)

    @SuppressLint("MissingPermission")
    private var record: AudioRecord? = null
    private val buffer = ByteArrayOutputStream()
    @Volatile private var recording = false
    private var thread: Thread? = null

    /** 开始录音；返回是否成功（权限/设备占用时 false） */
    fun start(): Boolean {
        return try {
            val r = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
            record = r
            buffer.reset()
            recording = true
            r.startRecording()
            thread = Thread {
                val buf = ByteArray(minBuf)
                while (recording) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) synchronized(buffer) { buffer.write(buf, 0, n) }
                }
            }.apply { start() }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 停止并返回 WAV；无声音返回 null */
    fun stop(): ByteArray? {
        recording = false
        thread?.join(500)
        thread = null
        val r = record ?: return null
        runCatching { r.stop() }
        r.release()
        record = null
        val pcm = synchronized(buffer) { buffer.toByteArray() }
        return if (pcm.isEmpty()) null else WavEncoder.encode(pcm, sampleRate)
    }

    /** 放弃录音 */
    fun cancel() {
        recording = false
        thread?.join(300)
        thread = null
        val r = record
        runCatching { r?.stop() }
        r?.release()
        record = null
        synchronized(buffer) { buffer.reset() }
    }

    /** 当前已录时长（秒），用于 120s 上限 */
    fun seconds(): Int = synchronized(buffer) { buffer.size() / (sampleRate * 2) }
}