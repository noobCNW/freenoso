package com.xs.reader.tts

import com.iflytek.aikit.core.AeeEvent
import com.iflytek.aikit.core.AiHelper
import com.iflytek.aikit.core.AiInput
import com.iflytek.aikit.core.AiListener
import com.iflytek.aikit.core.AiRequest
import com.iflytek.aikit.core.AiResponse
import com.iflytek.aikit.core.AiText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.ByteArrayOutputStream

/**
 * 讯飞 AIKit「离线语音合成 (高品质版)」引擎封装。
 *
 * 协议要点:
 *  - 全局 SDK 鉴权由 [XunfeiOfflineSdkManager] 负责,本类只负责单句合成
 *  - 调用流程: registerListener(abilityId) -> start -> write -> 等 onResult 累积 PCM -> 等 AEE_EVENT_END -> end(handle)
 *  - 输出 PCM 为 16kHz 16bit 单声道 (与 demo XTTSActivity 的 AudioTrack 配置一致)
 *  - 一次 synthesize 只为一句话工作; 注册的 listener 仅持有当前句子的状态,
 *    通过把它装在 callbackFlow 闭包里避免跨句子混淆
 *
 * 离线音色 (来自 SDK 包 resource/xtts):
 *  - xiaoyan: 中文晓燕 (温柔女声)
 *  - xiaofeng: 中文晓峰 (沉稳男声)
 */
@Singleton
class XunfeiOfflineTtsEngine @Inject constructor(
    private val sdk: XunfeiOfflineSdkManager
) : TtsEngine {

    override val id: String = XunfeiOfflineSdkManager.ENGINE_ID
    override val displayName: String = "讯飞离线 (高品质·需 Key)"
    override val needsNetwork: Boolean = false  // 首次激活需要联网,激活后完全离线
    override val needsApiKey: Boolean = true

    override suspend fun listVoices(): List<TtsVoice> = DEFAULT_VOICES

    override fun synthesize(request: TtsRequest): Flow<TtsAudio> = callbackFlow {
        val ready = sdk.ensureReady()
        if (!ready) {
            close(IllegalStateException(sdk.lastErrorMessage ?: "讯飞离线 SDK 未就绪"))
            return@callbackFlow
        }

        val pcmBuffer = ByteArrayOutputStream()
        var ended = false

        // 重新注册 listener: 同一 abilityId 只保留一份回调,
        // 这里每次合成都覆盖一遍,确保 pcmBuffer/状态来自当前协程闭包
        AiHelper.getInst().registerListener(ABILITY_ID, object : AiListener {
            override fun onResult(handleId: Int, list: MutableList<AiResponse>?, ctx: Any?) {
                if (list.isNullOrEmpty()) return
                for (r in list) {
                    if (r.key == "audio") {
                        val bytes = r.value
                        if (bytes != null && bytes.isNotEmpty()) {
                            pcmBuffer.write(bytes)
                        }
                    }
                }
            }

            override fun onEvent(
                handleId: Int,
                event: Int,
                eventData: MutableList<AiResponse>?,
                ctx: Any?
            ) {
                if (event == AeeEvent.AEE_EVENT_END.value) {
                    if (ended) return
                    ended = true
                    val pcm = pcmBuffer.toByteArray()
                    if (pcm.isEmpty()) {
                        close(IllegalStateException("讯飞离线引擎未返回音频"))
                    } else {
                        trySend(
                            TtsAudio.Pcm(
                                bytes = pcm,
                                sampleRate = OUTPUT_SAMPLE_RATE,
                                channels = OUTPUT_CHANNELS,
                                bitDepth = OUTPUT_BIT_DEPTH
                            )
                        )
                        close()
                    }
                }
            }

            override fun onError(handleId: Int, err: Int, msg: String?, ctx: Any?) {
                if (ended) return
                ended = true
                close(
                    IllegalStateException(
                        friendlyRunError(err, msg ?: "")
                    )
                )
            }
        })

        // 0~100, 默认 50, 与讯飞参数取值范围一致
        val speed = (50 * request.speed).toInt().coerceIn(0, 100)
        val pitch = (50 * request.pitch).toInt().coerceIn(0, 100)
        val voiceName = request.voice.id.ifBlank { "xiaoyan" }

        val params = AiInput.builder()
            .param("vcn", voiceName)
            .param("language", 1)            // 1=中文
            .param("textEncoding", "UTF-8")
            .param("pitch", pitch)
            .param("speed", speed)
            .param("volume", 70)
            .build()

        val handle = AiHelper.getInst().start(ABILITY_ID, params, null)
        if (handle == null || handle.code != 0) {
            close(
                IllegalStateException(
                    "讯飞离线 start 失败,code=${handle?.code ?: -1}; " +
                        "可能是音色资源 (${sdk.resourceDirAbsolutePath()}/) 未拷贝或损坏。"
                )
            )
            return@callbackFlow
        }

        val req = AiRequest.builder()
            .payload(AiText.get("text").data(request.text).valid())
            .build()
        val ret = AiHelper.getInst().write(req, handle)
        if (ret != 0) {
            close(IllegalStateException("讯飞离线 write 失败,code=$ret"))
            return@callbackFlow
        }

        awaitClose {
            // 协程被取消时也保证 end,避免 SDK 内部 handle 泄漏
            runCatching { AiHelper.getInst().end(handle) }
        }
    }

    private fun friendlyRunError(code: Int, raw: String): String = when (code) {
        18900 -> "讯飞离线引擎: 资源文件缺失或损坏 (code=$code) $raw"
        18901 -> "讯飞离线引擎: 不支持的发音人 (code=$code) $raw"
        18902 -> "讯飞离线引擎: 文本编码错误 (code=$code) $raw"
        else -> "讯飞离线引擎运行出错 code=$code msg=$raw"
    }

    companion object {
        // demo 里的 ABILITYID,对应"离线语音合成 XTTS10 e2e44feff" SDK 包
        const val ABILITY_ID = "e2e44feff"

        // demo XTTSActivity 里 AudioTrack 配置: 16kHz / mono / 16bit PCM
        private const val OUTPUT_SAMPLE_RATE = 16000
        private const val OUTPUT_CHANNELS = 1
        private const val OUTPUT_BIT_DEPTH = 16

        val DEFAULT_VOICES = listOf(
            TtsVoice(
                engineId = XunfeiOfflineSdkManager.ENGINE_ID,
                id = "xiaoyan",
                displayName = "晓燕 (温柔女声·离线)",
                locale = "zh-CN",
                gender = "Female",
                style = "offline"
            ),
            TtsVoice(
                engineId = XunfeiOfflineSdkManager.ENGINE_ID,
                id = "xiaofeng",
                displayName = "晓峰 (沉稳男声·离线)",
                locale = "zh-CN",
                gender = "Male",
                style = "offline"
            )
        )
    }
}
