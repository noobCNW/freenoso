package com.xs.reader.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class SystemTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : TtsEngine {

    override val id: String = "system"
    override val displayName: String = "系统 TTS（离线）"
    override val needsNetwork: Boolean = false
    override val needsApiKey: Boolean = false

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initOk: Boolean = false
    private val cacheDir = File(context.cacheDir, "tts").apply { mkdirs() }

    /**
     * 等待系统 TTS 初始化完成。失败抛 [IllegalStateException] 并附人话错误信息,
     * 让 UI 在 errorMessage 里提示"请去系统设置安装语音数据 / 切换 TTS 引擎"。
     */
    private suspend fun ensureTts(): TextToSpeech = suspendCancellableCoroutine { cont ->
        val existing = tts
        if (existing != null && initOk) {
            cont.resume(existing); return@suspendCancellableCoroutine
        }
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                initOk = true
                if (cont.isActive) cont.resume(tts!!)
            } else {
                initOk = false
                if (cont.isActive) {
                    cont.resumeWithException(
                        IllegalStateException(
                            "系统 TTS 引擎初始化失败 (status=$status)。" +
                                "请进入手机【设置 → 系统/通用 → 语言与输入法 → 文字转语音输出】," +
                                "安装一个支持中文的引擎(如 Google 文字转语音/讯飞TTS),或在本应用中切换为讯飞 TTS。"
                        )
                    )
                }
            }
        }
        tts = engine
        cont.invokeOnCancellation {
            runCatching { engine.shutdown() }
        }
    }

    override suspend fun listVoices(): List<TtsVoice> {
        val engine = ensureTts()
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        return voices
            .filter { it.locale.language == "zh" || it.locale.language == "en" }
            .map { v ->
                TtsVoice(
                    engineId = id,
                    id = v.name,
                    displayName = "${displayLocaleName(v.locale)} · ${v.name}",
                    locale = v.locale.toLanguageTag(),
                    style = if (v.isNetworkConnectionRequired) "network" else "offline"
                )
            }
            .ifEmpty {
                listOf(
                    TtsVoice(
                        engineId = id,
                        id = Locale.SIMPLIFIED_CHINESE.toLanguageTag(),
                        displayName = "简体中文（系统默认）",
                        locale = Locale.SIMPLIFIED_CHINESE.toLanguageTag()
                    ),
                    TtsVoice(
                        engineId = id,
                        id = Locale.US.toLanguageTag(),
                        displayName = "English (US)",
                        locale = Locale.US.toLanguageTag()
                    )
                )
            }
    }

    private fun displayLocaleName(locale: Locale): String {
        val display = locale.getDisplayName(Locale.SIMPLIFIED_CHINESE)
        return display.ifBlank { locale.toLanguageTag() }
    }

    override fun synthesize(request: TtsRequest): Flow<TtsAudio> = callbackFlow {
        val engine = try {
            ensureTts()
        } catch (e: Throwable) {
            close(e)
            awaitClose { }
            return@callbackFlow
        }

        val tag = request.voice.locale ?: Locale.SIMPLIFIED_CHINESE.toLanguageTag()
        val locale = Locale.forLanguageTag(tag)
        // LANG_MISSING_DATA / LANG_NOT_SUPPORTED 都是失败信号,直接抛带建议的错误
        val avail = runCatching { engine.isLanguageAvailable(locale) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED) {
            close(
                IllegalStateException(
                    "系统 TTS 缺少 ${locale.toLanguageTag()} 语音数据 (code=$avail)。" +
                        "请在手机【设置 → 文字转语音输出】中下载对应语言数据,或在本应用中切换为讯飞 TTS。"
                )
            )
            awaitClose { }
            return@callbackFlow
        }

        runCatching { engine.language = locale }
        runCatching {
            engine.voices?.firstOrNull { it.name == request.voice.id }?.let { engine.voice = it }
        }
        engine.setSpeechRate(request.speed.coerceIn(0.5f, 2.0f))
        engine.setPitch(request.pitch.coerceIn(0.5f, 2.0f))

        val utteranceId = UUID.randomUUID().toString()
        val outFile = File(cacheDir, "$utteranceId.wav")

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(uttId: String?) {}
            override fun onDone(uttId: String?) {
                if (outFile.exists() && outFile.length() > 44) {
                    trySend(TtsAudio.EncodedFile(outFile.absolutePath, "audio/wav"))
                    close()
                } else {
                    close(
                        IllegalStateException(
                            "系统 TTS 合成完成但未输出音频。可能是当前引擎不支持中文,请切换 TTS 引擎(系统设置 → 文字转语音输出)。"
                        )
                    )
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(uttId: String?) {
                close(IllegalStateException("系统 TTS 合成失败,可能是引擎或语言数据问题。"))
            }
            override fun onError(uttId: String?, errorCode: Int) {
                close(IllegalStateException("系统 TTS 合成失败 (errorCode=$errorCode)。"))
            }
        })
        val params = Bundle()
        val res = engine.synthesizeToFile(request.text, params, outFile, utteranceId)
        if (res != TextToSpeech.SUCCESS) {
            close(IllegalStateException("synthesizeToFile 调用失败 code=$res,系统 TTS 不可用,请改用其他引擎或安装新的 TTS 引擎。"))
        }
        awaitClose {
            engine.stop()
            outFile.delete()
        }
    }

    override suspend fun close() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
