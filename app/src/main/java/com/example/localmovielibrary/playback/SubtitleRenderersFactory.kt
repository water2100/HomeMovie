package com.example.localmovielibrary.playback

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.example.localmovielibrary.asr.PlayerPcmAudioBufferSink
import com.example.localmovielibrary.asr.SherpaOnnxSubtitleRecognizer

@UnstableApi
class SubtitleRenderersFactory(
    private val appContext: Context,
    private val subtitleRecognizer: SherpaOnnxSubtitleRecognizer
) : DefaultRenderersFactory(appContext) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setAudioProcessors(
                arrayOf<AudioProcessor>(
                    TeeAudioProcessor(PlayerPcmAudioBufferSink(subtitleRecognizer))
                )
            )
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
}
