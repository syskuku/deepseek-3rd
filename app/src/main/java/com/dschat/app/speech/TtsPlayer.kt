package com.dschat.app.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.dschat.app.net.SpeechApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * TTS 朗读：请求裸 PCM 流，边收边用 AudioTrack 播放（24kHz 单声道 16-bit）。
 * 同一时间只读一条；再次点击同一条或 stop() 停止。对照 demo readAloud.ts。
 */
class TtsPlayer(private val scope: CoroutineScope) {
    enum class Status { IDLE, LOADING, PLAYING }

    var messageId: String? = null
        private set
    var status: Status = Status.IDLE
        private set
    var onState: (() -> Unit)? = null

    private var job: Job? = null
    private var track: AudioTrack? = null

    fun toggle(apiKey: String, mid: String, text: String, voice: String, onError: (String) -> Unit) {
        val wasThis = this.messageId == mid && status != Status.IDLE
        stop()
        if (wasThis || text.isBlank()) return
        this.messageId = mid
        status = Status.LOADING
        onState?.invoke()
        job = scope.launch(Dispatchers.IO) {
            val minBuf = AudioTrack.getMinBufferSize(
                SpeechApi.TTS_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SpeechApi.TTS_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuf * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            t.play()
            var started = false
            // PCM 16-bit 小端：网络分块可能为奇数字节，跨块错位会产生爆音。
            // 用 carry 保存不足一个采样的尾字节，保证每次写入都是偶数字节（采样对齐）。
            var carry = ByteArray(0)
            try {
                SpeechApi.synthesize(apiKey, text, voice).collect { chunk ->
                    val data = if (carry.isEmpty()) chunk else carry + chunk
                    val even = data.size - (data.size % 2)
                    carry = if (even < data.size) data.copyOfRange(even, data.size) else ByteArray(0)
                    if (even <= 0) return@collect
                    if (!started) {
                        started = true
                        status = Status.PLAYING
                        onState?.invoke()
                    }
                    var off = 0
                    while (off < even) {
                        val written = t.write(data, off, even - off)
                        if (written <= 0) break
                        off += written
                    }
                }
                // 等待缓冲播完
                t.stop()
            } catch (_: Throwable) {
                // 中止或失败
            } finally {
                runCatching { t.release() }
                if (track === t) {
                    track = null
                    messageId = null
                    status = Status.IDLE
                    onState?.invoke()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        val t = track
        track = null
        runCatching { t?.pause() }
        runCatching { t?.flush() }
        runCatching { t?.release() }
        messageId = null
        status = Status.IDLE
        onState?.invoke()
    }
}