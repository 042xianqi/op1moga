package com.ai.assistance.operit.api.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitPaths
import com.k2fsa.sherpa.ncnn.*
import com.ai.assistance.operit.api.speech.SpeechPrerollStore
import java.io.File
import com.ai.assistance.operit.util.AssetCopyUtils
import com.ai.assistance.operit.util.HttpMultiPartDownloader
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * 基于sherpa-ncnn的本地语音识别实现 sherpa-ncnn是一个轻量级、高性能的语音识别引擎，比Whisper更适合移动端 参考:
 * https://github.com/k2-fsa/sherpa-ncnn
 */
@SuppressLint("MissingPermission")
class SherpaSpeechProvider(private val context: Context) : SpeechService {
    companion object {
        private const val TAG = "SherpaSpeechProvider"

        // op1moga: STT 模型改为联网下载，不再打包进 APK（省约 136MB）
        /** 模型文件清单（与 sherpa-ncnn 模型目录内容一致） */
        private val MODEL_FILE_NAMES =
                listOf(
                        "encoder_jit_trace-pnnx.ncnn.param",
                        "encoder_jit_trace-pnnx.ncnn.bin",
                        "decoder_jit_trace-pnnx.ncnn.param",
                        "decoder_jit_trace-pnnx.ncnn.bin",
                        "joiner_jit_trace-pnnx.ncnn.param",
                        "joiner_jit_trace-pnnx.ncnn.bin",
                        "tokens.txt"
                )

        /** HuggingFace 仓库路径 */
        private const val MODEL_REPO_PATH =
                "csukuangfj/sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13/resolve/main"

        /** 镜像优先，官方兜底 */
        private val MODEL_BASE_URLS = listOf("https://hf-mirror.com", "https://huggingface.co")
    }

    private var recognizer: SherpaNcnn? = null
    private var vad: OnnxSileroVad? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private fun clearAndReleaseAudioRecord() {
        val record = audioRecord
        audioRecord = null

        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error stopping AudioRecord", e)
            }
            try {
                record.release()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error releasing AudioRecord", e)
            }
        }
    }

    private val _recognitionState = MutableStateFlow(SpeechService.RecognitionState.UNINITIALIZED)
    override val currentState: SpeechService.RecognitionState
        get() = _recognitionState.value
    override val recognitionStateFlow: StateFlow<SpeechService.RecognitionState> =
            _recognitionState.asStateFlow()

    private val _recognitionResult = MutableStateFlow(SpeechService.RecognitionResult(""))
    override val recognitionResultFlow: StateFlow<SpeechService.RecognitionResult> =
            _recognitionResult.asStateFlow()

    private val _recognitionError = MutableStateFlow(SpeechService.RecognitionError(0, ""))
    override val recognitionErrorFlow: StateFlow<SpeechService.RecognitionError> =
            _recognitionError.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    
    // 添加音量级别Flow实现
    private val _volumeLevelFlow = MutableStateFlow(0f)
    override val volumeLevelFlow: StateFlow<Float> = _volumeLevelFlow.asStateFlow()

    override val isRecognizing: Boolean
        get() = currentState == SpeechService.RecognitionState.RECOGNIZING

    // 音量计算相关常量
    private val VOLUME_SMOOTHING_FACTOR = 0.1f // 平滑因子
    private var currentVolume = 0f

    private val initializeMutex = Mutex()
    private val recognitionMutex = Mutex()

    override suspend fun initialize(): Boolean {
        if (isInitialized.value) return true
        return initializeMutex.withLock {
            if (isInitialized.value) return@withLock true
            AppLogger.d(TAG, "Initializing sherpa-ncnn...")
            try {
                withContext(Dispatchers.IO) {
                    createRecognizer()
                    if (recognizer != null) {
                        AppLogger.d(TAG, "sherpa-ncnn initialized successfully")
                        _isInitialized.value = true
                        _recognitionState.value = SpeechService.RecognitionState.IDLE
                        true
                    } else {
                        AppLogger.e(TAG, "Failed to create sherpa-ncnn recognizer")
                        _recognitionState.value = SpeechService.RecognitionState.ERROR
                        _recognitionError.value =
                            SpeechService.RecognitionError(-1, "Failed to initialize recognizer")
                        false
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to initialize sherpa-ncnn", e)
                _recognitionState.value = SpeechService.RecognitionState.ERROR
                _recognitionError.value =
                    SpeechService.RecognitionError(-1, e.message ?: "Unknown error")
                false
            }
        }
    }

    private fun createRecognizer() {
        val localModelDir: File
        try {
            val modelDirName = "sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13"
            val targetDir = File(OperitPaths.sherpaNcnnModelsDir(context), modelDirName)
            // op1moga: 模型不再内置进 APK（省 ~136MB），改为首次使用时联网下载。
            // 若本地已具备完整文件则直接复用，避免重复下载。
            localModelDir =
                if (hasCompleteModelFiles(targetDir)) {
                    AppLogger.d(TAG, "STT model already present locally: ${targetDir.absolutePath}")
                    targetDir
                } else {
                    AppLogger.d(TAG, "STT model missing, starting network download...")
                    _recognitionState.value = SpeechService.RecognitionState.INITIALIZING
                    downloadModelFiles(targetDir)
                    targetDir
                }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to prepare STT model files.", e)
            _recognitionState.value = SpeechService.RecognitionState.ERROR
            _recognitionError.value =
                    SpeechService.RecognitionError(-1, "Failed to prepare model files.")
            return
        }

        val featConfig = getFeatureExtractorConfig(sampleRate = 16000.0f, featureDim = 80)

        val modelConfig =
                ModelConfig(
                        encoderParam =
                                File(localModelDir, "encoder_jit_trace-pnnx.ncnn.param")
                                        .absolutePath,
                        encoderBin =
                                File(localModelDir, "encoder_jit_trace-pnnx.ncnn.bin").absolutePath,
                        decoderParam =
                                File(localModelDir, "decoder_jit_trace-pnnx.ncnn.param")
                                        .absolutePath,
                        decoderBin =
                                File(localModelDir, "decoder_jit_trace-pnnx.ncnn.bin").absolutePath,
                        joinerParam =
                                File(localModelDir, "joiner_jit_trace-pnnx.ncnn.param")
                                        .absolutePath,
                        joinerBin =
                                File(localModelDir, "joiner_jit_trace-pnnx.ncnn.bin").absolutePath,
                        tokens = File(localModelDir, "tokens.txt").absolutePath,
                        numThreads = 4,
                        useGPU = false
                )

        val decoderConfig = getDecoderConfig(method = "greedy_search", numActivePaths = 4)

        val recognizerConfig =
                RecognizerConfig(
                        featConfig = featConfig,
                        modelConfig = modelConfig,
                        decoderConfig = decoderConfig,
                        enableEndpoint = true,
                        rule1MinTrailingSilence = 2.4f,
                        rule2MinTrailingSilence = 1.2f,
                        rule3MinUtteranceLength = 20.0f,
                        hotwordsFile = "",
                        hotwordsScore = 1.5f
                )

        recognizer =
                SherpaNcnn(
                        config = recognizerConfig,
                        assetManager = null // Force using newFromFile
                )
    }

    /**
     * 计算音频缓冲区的音量级别
     * 
     * @param buffer 音频数据缓冲区
     * @return 音量级别，范围在0.0-1.0之间
     */
    private fun calculateVolumeLevel(buffer: ShortArray, size: Int): Float {
        if (size <= 0) return 0f
        
        var sum = 0.0
        for (i in 0 until size) {
            sum += abs(buffer[i].toDouble())
        }
        
        // 计算平均振幅
        val average = sum / size
        
        // 转换为分贝值 (相对于最大振幅)
        val maxAmplitude = 32768.0
        val db = if (average > 0) 20 * log10(average / maxAmplitude) else -160.0
        
        // 将分贝值映射到0-1范围 (典型语音范围约为-60dB到0dB)
        val normalizedDb = (db + 60.0) / 60.0
        val volume = normalizedDb.coerceIn(0.0, 1.0).toFloat()
        
        // 应用平滑处理
        currentVolume = currentVolume * (1 - VOLUME_SMOOTHING_FACTOR) + volume * VOLUME_SMOOTHING_FACTOR
        
        return currentVolume
    }

    override suspend fun startRecognition(
            languageCode: String,
            continuousMode: Boolean,
            partialResults: Boolean,
            audioSource: Int,
    ): Boolean {
        return recognitionMutex.withLock {
            if (!isInitialized.value) {
                if (!initialize()) return@withLock false
            }
            if (
                currentState == SpeechService.RecognitionState.PREPARING ||
                    currentState == SpeechService.RecognitionState.PROCESSING ||
                    currentState == SpeechService.RecognitionState.RECOGNIZING ||
                    recordingJob?.isActive == true
            ) {
                return@withLock false
            }

            // 防御性清理：避免上一次异常/竞态导致 AudioRecord 遗留
            clearAndReleaseAudioRecord()

            _recognitionState.value = SpeechService.RecognitionState.PREPARING
            // 清空上一轮的识别结果，避免新的订阅者立刻收到旧的 StateFlow 值
            _recognitionResult.value = SpeechService.RecognitionResult(text = "", isFinal = false, confidence = 0f)
            recognizer?.reset(false) // 使用SherpaNcnn中的reset方法，参数为false不重新创建识别器

            val pendingPcm = SpeechPrerollStore.consumePending()
            if (pendingPcm != null && pendingPcm.isNotEmpty()) {
                try {
                    recognizer?.let { recognizerInstance ->
                        val samples = FloatArray(pendingPcm.size) { i -> pendingPcm[i] / 32768.0f }
                        recognizerInstance.acceptSamples(samples)
                        while (recognizerInstance.isReady()) {
                            recognizerInstance.decode()
                        }
                    }
                    AppLogger.d(TAG, "Applied preroll: samples=${pendingPcm.size}")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to apply preroll", e)
                }
            }

            val sampleRateInHz = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)

            if (minBufferSize <= 0) {
                AppLogger.e(TAG, "AudioRecord.getMinBufferSize returned invalid size: $minBufferSize")
                _recognitionState.value = SpeechService.RecognitionState.ERROR
                _recognitionError.value = SpeechService.RecognitionError(-2, "Invalid AudioRecord buffer size")
                return@withLock false
            }

            audioRecord =
                    AudioRecord(
                            audioSource,
                            sampleRateInHz,
                            channelConfig,
                            audioFormat,
                            minBufferSize * 2
                    )
            val recordInstance = audioRecord
            if (recordInstance == null || recordInstance.state != AudioRecord.STATE_INITIALIZED) {
                AppLogger.e(TAG, "AudioRecord is not initialized (state=${recordInstance?.state})")
                clearAndReleaseAudioRecord()
                _recognitionState.value = SpeechService.RecognitionState.ERROR
                _recognitionError.value = SpeechService.RecognitionError(-3, "AudioRecord not initialized")
                return@withLock false
            }
            try {
                recordInstance.startRecording()
            } catch (e: Exception) {
                AppLogger.e(TAG, "AudioRecord.startRecording failed", e)
                clearAndReleaseAudioRecord()
                _recognitionState.value = SpeechService.RecognitionState.ERROR
                _recognitionError.value = SpeechService.RecognitionError(-4, e.message ?: "AudioRecord start failed")
                return@withLock false
            }
            _recognitionState.value = SpeechService.RecognitionState.RECOGNIZING
            // 重置音量
            currentVolume = 0f
            _volumeLevelFlow.value = 0f
            AppLogger.d(TAG, "Started recording")

            recordingJob =
                    scope.launch {
                    try {
                        val bufferSize = minBufferSize
                        val audioBuffer = ShortArray(bufferSize)
                        var lastText = ""

                        val vadInstance = try {
                            (vad ?: OnnxSileroVad(context = context, speechDurationMs = 0)).also { created ->
                                vad = created
                                created.reset()
                            }
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Failed to initialize Silero VAD, falling back to non-VAD mode", e)
                            null
                        }

                        val vadFrameSize = 512
                        val vadFrame = ShortArray(vadFrameSize)
                        var vadFramePos = 0
                        var vadSpeechActive = false

                        while (isActive &&
                                _recognitionState.value == SpeechService.RecognitionState.RECOGNIZING) {
                            val ret = try {
                                audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "AudioRecord.read failed", e)
                                break
                            }
                            if (ret <= 0) break

                            SpeechPrerollStore.appendPcm(audioBuffer, ret)
                            val volumeLevel = calculateVolumeLevel(audioBuffer, ret)
                            _volumeLevelFlow.value = volumeLevel

                            recognizer?.let { recognizerInstance ->
                                if (vadInstance == null) {
                                    val samples = FloatArray(ret) { i -> audioBuffer[i] / 32768.0f }
                                    recognizerInstance.acceptSamples(samples)
                                    while (recognizerInstance.isReady()) {
                                        recognizerInstance.decode()
                                    }

                                    val isEndpoint = recognizerInstance.isEndpoint()
                                    val text = recognizerInstance.text

                                    if (text.isNotBlank()) {
                                        if (isEndpoint) {
                                            lastText = text
                                            _recognitionResult.value =
                                                SpeechService.RecognitionResult(text = text, isFinal = true)
                                        } else if (partialResults && lastText != text) {
                                            lastText = text
                                            _recognitionResult.value =
                                                SpeechService.RecognitionResult(text = text, isFinal = false)
                                        }
                                    }

                                    if (isEndpoint) {
                                        recognizerInstance.reset(false)
                                        if (!continuousMode) {
                                            _recognitionState.value = SpeechService.RecognitionState.IDLE
                                            return@launch
                                        }
                                    }
                                } else {
                                    var idx = 0
                                    while (idx < ret) {
                                        val toCopy = minOf(vadFrameSize - vadFramePos, ret - idx)
                                        java.lang.System.arraycopy(audioBuffer, idx, vadFrame, vadFramePos, toCopy)
                                        vadFramePos += toCopy
                                        idx += toCopy

                                        if (vadFramePos == vadFrameSize) {
                                            val isSpeech = vadInstance.isSpeech(vadFrame)

                                            if (isSpeech) {
                                                vadSpeechActive = true

                                                val frameSamples = FloatArray(vadFrameSize) { i -> vadFrame[i] / 32768.0f }
                                                recognizerInstance.acceptSamples(frameSamples)
                                                while (recognizerInstance.isReady()) {
                                                    recognizerInstance.decode()
                                                }

                                                val text = recognizerInstance.text
                                                if (partialResults && text.isNotBlank() && lastText != text) {
                                                    lastText = text
                                                    _recognitionResult.value =
                                                        SpeechService.RecognitionResult(text = text, isFinal = false)
                                                }
                                            } else if (vadSpeechActive) {
                                                recognizerInstance.inputFinished()
                                                while (recognizerInstance.isReady()) {
                                                    recognizerInstance.decode()
                                                }

                                                val finalText = recognizerInstance.text
                                                if (finalText.isNotBlank()) {
                                                    lastText = finalText
                                                    _recognitionResult.value =
                                                        SpeechService.RecognitionResult(text = finalText, isFinal = true)
                                                }

                                                recognizerInstance.reset(false)
                                                vadInstance.reset()
                                                vadSpeechActive = false

                                                if (!continuousMode) {
                                                    _recognitionState.value = SpeechService.RecognitionState.IDLE
                                                    return@launch
                                                }
                                            }

                                            vadFramePos = 0
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Recording loop failed", e)
                    } finally {
                        withContext(Dispatchers.Main) {
                            _recognitionState.value = SpeechService.RecognitionState.IDLE
                            _volumeLevelFlow.value = 0f
                        }
                        AppLogger.d(TAG, "Stopped recording.")
                    }
                    }
            return@withLock true
        }
    }

    override suspend fun stopRecognition(): Boolean {
        return recognitionMutex.withLock {
            if (recordingJob?.isActive == true &&
                            _recognitionState.value == SpeechService.RecognitionState.RECOGNIZING
            ) {
                AppLogger.d(TAG, "Stopping recognition...")
                try {
                    audioRecord?.stop()
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Error stopping AudioRecord", e)
                }
                _recognitionState.value =
                        SpeechService.RecognitionState.PROCESSING // Indicate processing then idle

                recordingJob?.cancel()
                try {
                    recordingJob?.join()
                } catch (_: Exception) {
                }
                recordingJob = null

                // Finalize recognition
                try {
                    recognizer?.inputFinished()
                    val text = recognizer?.text ?: ""
                    _recognitionResult.value = SpeechService.RecognitionResult(text = text, isFinal = true)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Finalize recognition failed", e)
                }

                clearAndReleaseAudioRecord()
                _recognitionState.value = SpeechService.RecognitionState.IDLE
                _volumeLevelFlow.value = 0f // 重置音量
                return@withLock true
            }
            return@withLock false
        }
    }

    override suspend fun cancelRecognition() {
        recognitionMutex.withLock {
            if (recordingJob?.isActive == true) {
                recordingJob?.cancel()
                try {
                    recordingJob?.join()
                } catch (_: Exception) {
                }
            }
            recordingJob = null
            clearAndReleaseAudioRecord()
            _recognitionState.value = SpeechService.RecognitionState.IDLE
            _volumeLevelFlow.value = 0f // 重置音量
            // 同步清空识别文本，避免下次订阅拿到旧文本
            _recognitionResult.value = SpeechService.RecognitionResult(text = "", isFinal = false, confidence = 0f)
            try {
                vad?.reset()
            } catch (_: Exception) {
            }
        }
    }

    override fun shutdown() {
        runBlocking {
            try {
                cancelRecognition()
            } catch (_: Exception) {
            }
            withContext(Dispatchers.IO) {
                try {
                    recognizer?.release()
                } catch (_: Exception) {
                }
                recognizer = null
                try {
                    vad?.close()
                } catch (_: Exception) {
                }
                vad = null
            }
            _isInitialized.value = false
            _recognitionState.value = SpeechService.RecognitionState.UNINITIALIZED
            _volumeLevelFlow.value = 0f // 重置音量
            _recognitionResult.value = SpeechService.RecognitionResult(text = "", isFinal = false, confidence = 0f)
        }
    }

    override suspend fun getSupportedLanguages(): List<String> =
            withContext(Dispatchers.IO) {
                return@withContext listOf("zh", "en")
            }

    // This method is for non-streaming recognition, which we are not using with sherpa-ncnn's
    // streaming API.
    // We can leave it as a no-op or throw an exception if called.
    override suspend fun recognize(audioData: FloatArray) {
        // Not implemented for streaming recognizer
        withContext(Dispatchers.Main) {
            _recognitionError.value =
                    SpeechService.RecognitionError(
                            -10,
                            "Batch recognition not supported in this provider"
                    )
            _recognitionState.value = SpeechService.RecognitionState.ERROR
        }
    }

    // ==================== op1moga: STT 模型按需下载 ====================

    /**
     * op1moga: 判断本地是否已具备完整模型文件。
     *
     * 五个文件缺任意一个都视为不完整（可能是上次下载中断留下的残缺文件），
     * 需要重新下载。
     */
    private fun hasCompleteModelFiles(dir: File): Boolean =
            dir.isDirectory &&
                    MODEL_FILE_NAMES.all { name ->
                        val f = File(dir, name)
                        f.isFile && f.length() > 0L
                    }

    /**
     * op1moga: 联网下载 STT 模型文件（替代原先打包进 APK 的 assets）。
     *
     * 逐个文件下载，任一失败抛 [IOException]，由调用方统一降级为 ERROR 状态。
     * 下载完成后逐个校验非空，避免残缺文件被当作可用。
     */
    private fun downloadModelFiles(targetDir: File) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Cannot create model directory: ${targetDir.absolutePath}")
        }

        MODEL_FILE_NAMES.forEachIndexed { index, fileName ->
            val dest = File(targetDir, fileName)
            // 已存在且非空则跳过（支持断点续传后的续用）
            if (dest.isFile && dest.length() > 0L) {
                AppLogger.d(TAG, "[$index/${MODEL_FILE_NAMES.size}] skip existing: $fileName")
                return@forEachIndexed
            }

            var lastError: Exception? = null
            var succeeded = false
            for (baseUrl in MODEL_BASE_URLS) {
                val partFile = File(targetDir, "$fileName.part")
                try {
                    AppLogger.d(TAG, "[$index/${MODEL_FILE_NAMES.size}] downloading $fileName from $baseUrl")
                    HttpMultiPartDownloader.download(
                            url = "$baseUrl/$MODEL_REPO_PATH/$fileName",
                            dest = partFile,
                            threadCount = 4
                    )
                    if (!partFile.isFile || partFile.length() <= 0L) {
                        throw IOException("Downloaded file is empty: $fileName")
                    }
                    if (dest.exists()) dest.delete()
                    if (!partFile.renameTo(dest)) {
                        throw IOException("Cannot move downloaded file into place: $fileName")
                    }
                    succeeded = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    AppLogger.w(TAG, "Failed from $baseUrl: ${e.message}")
                    partFile.delete()
                }
            }

            if (!succeeded) {
                throw IOException(
                        "Failed to download STT model file: $fileName",
                        lastError
                )
            }
        }

        AppLogger.i(TAG, "STT model download completed: ${targetDir.absolutePath}")
    }
}
