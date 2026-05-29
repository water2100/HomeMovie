package com.example.localmovielibrary.asr

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer

@UnstableApi
class PlayerPcmAudioBufferSink(
    private val recognizer: SherpaOnnxSubtitleRecognizer
) : TeeAudioProcessor.AudioBufferSink {
    @Volatile
    private var sampleRateHz: Int = 0

    @Volatile
    private var channelCount: Int = 0

    @Volatile
    private var encoding: Int = C.ENCODING_PCM_16BIT

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
        this.encoding = encoding
        recognizer.flushPlayerPcmFormat(sampleRateHz, channelCount, encoding)
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        recognizer.handlePlayerPcm(
            buffer = buffer,
            sampleRate = sampleRateHz,
            channelCount = channelCount,
            encoding = encoding
        )
    }
}
