package com.example.localmovielibrary.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

class SherpaOnnxSubtitleRecognizer(
    private val context: Context
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recognizerMutex = Mutex()
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var recordJob: Job? = null
    private var recognizer: OfflineRecognizer? = null
    private var processorJob: Job? = null
    private var playerPcmInput: PlayerPcmInput? = null

    fun hasModelAssets(): Boolean =
        runCatching {
            val files = appContext.assets.list(MODEL_DIR)?.toSet().orEmpty()
            files.contains(MODEL_FILE) && files.contains(TOKENS_FILE)
        }.getOrDefault(false)

    fun startFromMicrophone(
        onStatus: (String) -> Unit,
        onText: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (running.getAndSet(true)) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            running.set(false)
            onError("缺少麦克风权限，无法启动本地实时字幕")
            return
        }
        if (!hasModelAssets()) {
            running.set(false)
            onError("缺少 sherpa-onnx SenseVoice 模型文件")
            return
        }

        recordJob = scope.launch {
            runCatching {
                ensureRecognizer()
                recordAndRecognize(
                    audioRecord = createMicrophoneAudioRecord(),
                    startMessage = "麦克风 ASR 已启动，正在聆听日语语音...",
                    idleMessage = "正在聆听日语语音...",
                    onStatus = onStatus,
                    onText = onText,
                    onError = onError
                )
            }.onFailure { error ->
                running.set(false)
                onError(error.message ?: "本地 ASR 启动失败")
            }
        }
    }

    fun startFromPlayerPcm(
        onStatus: (String) -> Unit,
        onText: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (running.getAndSet(true)) return
        if (!hasModelAssets()) {
            running.set(false)
            onError("缺少 sherpa-onnx SenseVoice 模型文件")
            return
        }
        val input = PlayerPcmInput()
        playerPcmInput = input

        processorJob = scope.launch {
            runCatching {
                ensureRecognizer()
                processPlayerPcm(
                    input = input,
                    onStatus = onStatus,
                    onText = onText,
                    onError = onError
                )
            }.onFailure { error ->
                running.set(false)
                onError(error.message ?: "播放器音频捕获启动失败")
            }
        }
    }

    fun stop() {
        running.set(false)
        recordJob?.cancel()
        recordJob = null
        processorJob?.cancel()
        processorJob = null
        recorder?.runCatchingRelease()
        recorder = null
        playerPcmInput = null
    }

    fun release() {
        stop()
        recognizer?.release()
        recognizer = null
        scope.cancel()
    }

    private fun ensureRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$MODEL_DIR/$MODEL_FILE",
                    language = "ja",
                    useInverseTextNormalization = true
                ),
                tokens = "$MODEL_DIR/$TOKENS_FILE",
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
        )
        return OfflineRecognizer(appContext.assets, config).also { recognizer = it }
    }

    private fun createMicrophoneAudioRecord(): AudioRecord {
        val minBufferSize = minAudioBufferSize()
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBufferSize, SAMPLE_RATE)
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.runCatchingRelease()
            error("AudioRecord 初始化失败")
        }
        return audioRecord
    }

    private fun minAudioBufferSize(): Int {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) error("无法创建录音缓冲区")
        return minBufferSize
    }

    private suspend fun recordAndRecognize(
        audioRecord: AudioRecord,
        startMessage: String,
        idleMessage: String,
        onStatus: (String) -> Unit,
        onText: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        recorder = audioRecord
        val shortBuffer = ShortArray(SAMPLE_RATE / 10)
        val samples = FloatSampleBuffer(SAMPLE_RATE * CHUNK_SECONDS)
        audioRecord.startRecording()
        onStatus(startMessage)

        while (running.get()) {
            val read = audioRecord.read(shortBuffer, 0, shortBuffer.size)
            if (read <= 0) {
                delay(30)
                continue
            }
            samples.append(shortBuffer, read)
            if (samples.size >= SAMPLE_RATE * CHUNK_SECONDS) {
                val chunk = samples.takeAndClear()
                if (chunk.averageAbs() < MIN_AUDIO_LEVEL) {
                    onStatus(idleMessage)
                    continue
                }
                decodeChunk(chunk, onText, onError)
            }
        }
    }

    fun handlePlayerPcm(buffer: ByteBuffer, sampleRate: Int, channelCount: Int, encoding: Int) {
        playerPcmInput?.append(buffer, sampleRate, channelCount, encoding)
    }

    fun flushPlayerPcmFormat(sampleRate: Int, channelCount: Int, encoding: Int) {
        playerPcmInput?.setFormat(sampleRate, channelCount, encoding)
    }

    private suspend fun processPlayerPcm(
        input: PlayerPcmInput,
        onStatus: (String) -> Unit,
        onText: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val samples = FloatSampleBuffer(SAMPLE_RATE * CHUNK_SECONDS)
        onStatus("播放器内录 ASR 已启动，正在识别影片声音...")
        while (running.get()) {
            val chunk = input.poll()
            if (chunk == null) {
                delay(60)
                continue
            }
            samples.append(chunk, chunk.size)
            if (samples.size >= SAMPLE_RATE * CHUNK_SECONDS) {
                val segment = samples.takeAndClear()
                if (segment.averageAbs() < MIN_AUDIO_LEVEL) {
                    onStatus("正在监听播放器声音...")
                    continue
                }
                decodeChunk(segment, onText, onError)
            }
        }
    }

    private suspend fun decodeChunk(
        samples: FloatArray,
        onText: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        recognizerMutex.withLock {
            runCatching {
                val currentRecognizer = ensureRecognizer()
                val stream = currentRecognizer.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    currentRecognizer.decode(stream)
                    currentRecognizer.getResult(stream).text.cleanSenseVoiceText()
                } finally {
                    stream.release()
                }
            }.onSuccess { text ->
                if (text.isNotBlank()) onText(text)
            }.onFailure { error ->
                onError(error.message ?: "本地 ASR 识别失败")
            }
        }
    }

    private fun AudioRecord.runCatchingRelease() {
        runCatching { stop() }
        runCatching { release() }
    }

    private class FloatSampleBuffer(initialCapacity: Int) {
        private var values = FloatArray(initialCapacity)
        var size: Int = 0
            private set

        fun append(shorts: ShortArray, count: Int) {
            ensureCapacity(size + count)
            for (index in 0 until count) {
                values[size + index] = shorts[index] / 32768.0f
            }
            size += count
        }

        fun append(floats: FloatArray, count: Int) {
            ensureCapacity(size + count)
            floats.copyInto(values, destinationOffset = size, startIndex = 0, endIndex = count)
            size += count
        }

        fun takeAndClear(): FloatArray {
            val result = values.copyOf(size)
            size = 0
            return result
        }

        private fun ensureCapacity(target: Int) {
            if (target <= values.size) return
            values = values.copyOf(max(target, values.size * 2))
        }
    }

    private class PlayerPcmInput {
        private val lock = Any()
        private val queue = ArrayDeque<FloatArray>()
        private var sampleRate: Int = SAMPLE_RATE
        private var channelCount: Int = 2
        private var encoding: Int = AudioFormat.ENCODING_PCM_16BIT

        fun setFormat(sampleRate: Int, channelCount: Int, encoding: Int) {
            synchronized(lock) {
                this.sampleRate = sampleRate
                this.channelCount = channelCount.coerceAtLeast(1)
                this.encoding = encoding
                queue.clear()
            }
        }

        fun append(buffer: ByteBuffer, sampleRate: Int, channelCount: Int, encoding: Int) {
            if (encoding != AudioFormat.ENCODING_PCM_16BIT) return
            val duplicate = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
            val currentChannelCount = channelCount.coerceAtLeast(1)
            val frameCount = duplicate.remaining() / (2 * currentChannelCount)
            if (frameCount <= 0) return
            val mono = FloatArray(frameCount)
            for (frame in 0 until frameCount) {
                var mixed = 0f
                for (channel in 0 until currentChannelCount) {
                    mixed += duplicate.short / 32768f
                }
                mono[frame] = mixed / currentChannelCount
            }
            val normalized = if (sampleRate == SAMPLE_RATE) mono else mono.resampleLinear(sampleRate, SAMPLE_RATE)
            synchronized(lock) {
                this.sampleRate = sampleRate
                this.channelCount = currentChannelCount
                this.encoding = encoding
                if (queue.size > 20) queue.removeFirst()
                queue.addLast(normalized)
            }
        }

        fun poll(): FloatArray? =
            synchronized(lock) {
                if (queue.isEmpty()) null else queue.removeFirst()
            }

        private fun FloatArray.resampleLinear(fromRate: Int, toRate: Int): FloatArray {
            if (fromRate <= 0 || fromRate == toRate || isEmpty()) return this
            val outputSize = ((size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
            val output = FloatArray(outputSize)
            val ratio = fromRate.toDouble() / toRate.toDouble()
            for (index in output.indices) {
                val source = index * ratio
                val left = source.toInt().coerceIn(0, lastIndex)
                val right = (left + 1).coerceIn(0, lastIndex)
                val frac = (source - left).toFloat()
                output[index] = this[left] * (1f - frac) + this[right] * frac
            }
            return output
        }
    }

    private fun FloatArray.averageAbs(): Float {
        if (isEmpty()) return 0f
        var total = 0f
        forEach { total += abs(it) }
        return total / size
    }

    private fun String.cleanSenseVoiceText(): String =
        replace(Regex("<\\|[^>]+\\|>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        private const val MODEL_DIR = "sherpa-onnx-sense-voice-ja"
        private const val MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_SECONDS = 4
        private const val MIN_AUDIO_LEVEL = 0.0035f
    }
}
