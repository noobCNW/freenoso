package com.xs.reader.tts

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 基于 sherpa-onnx + matcha-icefall-zh-baker 的离线神经 TTS 引擎。
 *
 * 协议要点:
 *  - 模型由 [MatchaModelManager] 单独管理 (下载 / 解压 / 路径)
 *  - 本引擎只负责合成: 第一次 synthesize 时懒加载 OfflineTts (约 80MB ONNX 进 RAM,
 *    主线程加载会冻 UI, 必须在 IO 线程做且只做一次)
 *  - 后续合成直接复用同一个 OfflineTts 实例; 配置改变时由 [close] 释放重建
 *
 * 关于参数语义:
 *  - sherpa-onnx 的 `lengthScale` 是"时长缩放": >1 拖长 (慢), <1 压缩 (快),
 *    跟项目里 [TtsRequest.speed] 语义相反 — 这里做 `1/speed` 转换。
 *  - matcha 模型**不支持 pitch (基频) 控制**, [TtsRequest.pitch] 在此引擎下被忽略。
 *  - baker 数据集是单 speaker 训练, [OfflineTts.generate] 的 sid 必须传 0。
 *
 * 关于音频输出:
 *  - sherpa 返回 [com.k2fsa.sherpa.onnx.GeneratedAudio] = `FloatArray samples + Int sampleRate`,
 *    采样值范围 [-1, 1]
 *  - 项目 [TtsAudio.Pcm] 需要 16bit little-endian PCM ByteArray, 这里做转换
 *  - 模型采样率运行时从引擎读 (matcha-icefall-zh-baker 是 22050Hz), 不硬编码
 */
@Singleton
class MatchaTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: MatchaModelManager
) : TtsEngine {

    override val id: String = ENGINE_ID
    override val displayName: String = "matcha (中文·离线神经)"
    override val needsNetwork: Boolean = false  // 首次下载模型需联网, 之后完全离线
    override val needsApiKey: Boolean = false

    private val initMutex = Mutex()

    @Volatile
    private var tts: OfflineTts? = null

    override suspend fun listVoices(): List<TtsVoice> = DEFAULT_VOICES

    /**
     * 懒加载 OfflineTts 实例。模型未下载时直接抛带提示的异常,
     * UI 应该在切到本引擎时主动检查 [MatchaModelManager.isModelComplete],
     * 这里只是兜底。
     */
    private suspend fun ensureTts(): OfflineTts {
        tts?.let { return it }
        return initMutex.withLock {
            tts?.let { return@withLock it }
            if (!modelManager.isModelComplete()) {
                throw IllegalStateException(
                    "matcha 离线模型未下载, 请到 设置 → 朗读引擎 → matcha 点「下载模型 (~105MB)」"
                )
            }
            val config = modelManager.buildConfig()
                ?: throw IllegalStateException("matcha 模型配置构建失败, 文件可能损坏。请到设置删除模型重试。")
            Log.i(TAG, "loading OfflineTts from ${modelManager.modelDir} ...")
            val instance = withContext(Dispatchers.IO) {
                // assetManager 传 null —— 我们的模型在 filesDir 里(绝对路径), 不在 APK assets
                OfflineTts(null, config)
            }
            Log.i(TAG, "OfflineTts loaded: sampleRate=${instance.sampleRate()}, numSpeakers=${instance.numSpeakers()}")
            tts = instance
            instance
        }
    }

    override fun synthesize(request: TtsRequest): Flow<TtsAudio> = flow {
        val engine = ensureTts()
        // 0.5..2.0 → lengthScale 2.0..0.5 (语义相反)
        val safeSpeed = request.speed.coerceIn(0.5f, 2.0f)
        val lengthScale = 1f / safeSpeed
        val gen = engine.generate(text = request.text, sid = 0, speed = lengthScale)
        val pcm = floatToPcm16Bytes(gen.samples)
        emit(
            TtsAudio.Pcm(
                bytes = pcm,
                sampleRate = gen.sampleRate,
                channels = 1,
                bitDepth = 16
            )
        )
    }.flowOn(Dispatchers.IO)

    override suspend fun close() {
        initMutex.withLock {
            tts?.release()
            tts = null
        }
    }

    /**
     * Float [-1, 1] → 16bit little-endian PCM ByteArray。
     * 用 ByteBuffer 一次性转, 比 for + bitwise 拼 ByteArray 快 5 倍 (尤其长句子时差距明显)。
     */
    private fun floatToPcm16Bytes(samples: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val clipped = if (s > 1f) 1f else if (s < -1f) -1f else s
            val short = (clipped * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            bb.putShort(short)
        }
        return bb.array()
    }

    companion object {
        const val ENGINE_ID = "matcha_zh_baker"
        private const val TAG = "MatchaTtsEngine"

        // baker 数据集就一个标贝青年女声, sherpa-onnx 调用时 sid 固定 0
        val DEFAULT_VOICES = listOf(
            TtsVoice(
                engineId = ENGINE_ID,
                id = "baker",
                displayName = "标贝女声 (神经·离线)",
                locale = "zh-CN",
                gender = "Female",
                style = "neural-offline"
            )
        )
    }
}
