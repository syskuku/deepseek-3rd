package com.dschat.app.speech

import java.io.ByteArrayOutputStream

/** 16-bit 单声道 PCM 小端 → WAV（ASR 接受 16kHz） */
object WavEncoder {
    fun encode(pcm: ByteArray, sampleRate: Int): ByteArray {
        val dataLen = pcm.size
        val out = ByteArrayOutputStream(44 + dataLen)
        fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun u32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        str("RIFF"); u32(36 + dataLen); str("WAVE")
        str("fmt "); u32(16); u16(1); u16(1); u32(sampleRate); u32(sampleRate * 2); u16(2); u16(16)
        str("data"); u32(dataLen)
        out.write(pcm)
        return out.toByteArray()
    }
}