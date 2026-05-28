package com.xs.reader.tts

import android.content.Context
import android.util.Base64
import com.xs.reader.data.prefs.ReadingPrefsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 讯飞「超拟人语音合成」WebAPI。
 *
 * 与 [XunfeiTtsEngine] 对接的 /v2/tts 普通版完全是另一套协议:
 *  - 端点: wss://cbm01.cn-huabei-1.xf-yun.com/v1/private/<resource_id>
 *    resource_id 在讯飞控制台「超拟人语音合成 → 超拟人合成 API」一栏可见,每个账号不同。
 *  - 鉴权: HMAC-SHA256 query 鉴权,与普通版同算法,但签的 path 不同。
 *  - 请求体: header / parameter / payload 三段式。
 *  - 返回: 多帧 base64 mp3(encoding=lame)分片下发,header.status==2 为最后一帧。
 *  - 多出 oral_level / rhy / bgs / spark_assist 等参数,适合"评书/有声书"场景。
 *
 * 评书发音人(在 voice.style 标 "pingshu")会自动套用一组保守的 oral/rhy 参数,
 * 让讯飞侧自动加抑扬顿挫,避免出现"超拟人但太聊天感"的违和。
 */
@Singleton
class XunfeiSuperTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val keyStore: SecureKeyStore,
    private val prefsRepo: ReadingPrefsRepository
) : TtsEngine {

    override val id: String = ENGINE_ID
    override val displayName: String = "讯飞超拟人 (在线·需 Key)"
    override val needsNetwork: Boolean = true
    override val needsApiKey: Boolean = true

    private val cacheDir = File(context.cacheDir, "tts").apply { mkdirs() }

    /**
     * 内置控制台「超拟人语音合成 → 发音人授权管理」默认开通的"聆"系列基础发音人
     * (见 [XunfeiVoicePreset.BUILTIN_XUNFEI_SUPER]),并与用户自定义预设合并;
     * 同 vcn 时以用户自定义优先 (例如用户想给 `x6_lingyuyan_pro` 标 style=pingshu
     * 自动套评书参数, 自添加同 vcn 的预设即可覆盖)。
     */
    override suspend fun listVoices(): List<TtsVoice> {
        val json = prefsRepo.flow.first().xunfeiVoicePresetsJson
        val user = XunfeiVoicePreset.listFromJson(json)
        return XunfeiVoicePreset.mergedVoicesFor(id, user)
    }

    override fun synthesize(request: TtsRequest): Flow<TtsAudio> = callbackFlow {
        // app_id/api_key/api_secret 从讯飞共享命名空间读 (普通版/超拟人/离线 SDK 同源,
        // 见 SecureKeyStore.XUNFEI_SHARED_NS); resource_id 仍是超拟人独有, 保留在 id 下。
        val appId = keyStore.get(SecureKeyStore.XUNFEI_SHARED_NS, KEY_APP_ID)
        val apiKey = keyStore.get(SecureKeyStore.XUNFEI_SHARED_NS, KEY_API_KEY)
        val apiSecret = keyStore.get(SecureKeyStore.XUNFEI_SHARED_NS, KEY_API_SECRET)
        val resourceId = keyStore.get(id, KEY_RESOURCE_ID)?.trim()
        if (appId.isNullOrBlank() || apiKey.isNullOrBlank() || apiSecret.isNullOrBlank()) {
            close(IllegalStateException("讯飞超拟人 TTS 需要在设置中填写 AppID / APIKey / APISecret"))
            return@callbackFlow
        }
        if (resourceId.isNullOrBlank()) {
            close(IllegalStateException("讯飞超拟人 TTS 需要在设置中填写 Resource ID (控制台「超拟人合成 API」栏可见)"))
            return@callbackFlow
        }

        val authedUrl = buildAuthUrl(apiKey, apiSecret, resourceId)
        val req = Request.Builder().url(authedUrl).build()

        val mp3Buffer = ByteArrayOutputStream()
        val ws = okHttpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val payload = buildSendPayload(appId, request)
                webSocket.send(payload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    val header = json.optJSONObject("header") ?: JSONObject()
                    val code = header.optInt("code", 0)
                    if (code != 0) {
                        close(
                            IllegalStateException(
                                friendlyError(code, header.optString("message"))
                            )
                        )
                        return
                    }
                    val payload = json.optJSONObject("payload")
                    val audio = payload?.optJSONObject("audio")
                    val audioStr = audio?.optString("audio")
                    if (!audioStr.isNullOrBlank()) {
                        val bytes = Base64.decode(audioStr, Base64.DEFAULT)
                        mp3Buffer.write(bytes)
                    }
                    val status = header.optInt("status", -1)
                    if (status == 2) {
                        val mp3 = mp3Buffer.toByteArray()
                        if (mp3.isEmpty()) {
                            close(IllegalStateException("讯飞超拟人返回空音频"))
                            return
                        }
                        val outFile = File(cacheDir, "xunfei-super-${UUID.randomUUID()}.mp3")
                        outFile.writeBytes(mp3)
                        trySend(TtsAudio.EncodedFile(outFile.absolutePath, "audio/mpeg"))
                        webSocket.close(1000, "done")
                        close()
                    }
                }.onFailure { close(it) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onMessage(webSocket, bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }
        })

        awaitClose { ws.cancel() }
    }

    private fun buildAuthUrl(apiKey: String, apiSecret: String, resourceId: String): String {
        val host = "cbm01.cn-huabei-1.xf-yun.com"
        val path = "/v1/private/$resourceId"
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val date = sdf.format(java.util.Date())
        val signOrigin = "host: $host\ndate: $date\nGET $path HTTP/1.1"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(), "HmacSHA256"))
        val signSha = mac.doFinal(signOrigin.toByteArray())
        val signature = Base64.encodeToString(signSha, Base64.NO_WRAP)
        val authOrigin =
            "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", " +
                "headers=\"host date request-line\", signature=\"$signature\""
        val authorization = Base64.encodeToString(authOrigin.toByteArray(), Base64.NO_WRAP)
        // OkHttp 的 HttpUrl 只接受 http/https; 用 https 拼好后再交给 newWebSocket(),
        // OkHttp 内部会自动升级成 wss。与 XunfeiTtsEngine 的处理保持一致。
        val baseUrl = "https://$host$path".toHttpUrl()
            .newBuilder()
            .addQueryParameter("authorization", authorization)
            .addQueryParameter("date", date)
            .addQueryParameter("host", host)
            .build()
        return baseUrl.toString()
    }

    private fun buildSendPayload(appId: String, request: TtsRequest): String {
        val voice = request.voice
        val voiceName = voice.id.ifBlank { "x6_ruyadashu_pro" }
        val isPingshu = voice.style == STYLE_PINGSHU
        // 0~100, 默认 50
        val speed = (50 * request.speed).toInt().coerceIn(0, 100)
        val pitch = (50 * request.pitch).toInt().coerceIn(0, 100)

        val textBase64 = Base64.encodeToString(
            request.text.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val header = JSONObject().apply {
            put("app_id", appId)
            put("status", 2)
        }

        val oral = JSONObject().apply {
            // 评书是表演性叙事,不要"聊天口语化"; 其他声音保留中等口语化,听感更自然。
            put("oral_level", if (isPingshu) "low" else "mid")
        }

        val audio = JSONObject().apply {
            put("encoding", "lame")
            put("sample_rate", 24000)
            put("channels", 1)
            put("bit_depth", 16)
            put("frame_size", 0)
        }

        val tts = JSONObject().apply {
            put("vcn", voiceName)
            put("speed", speed)
            put("volume", 70)
            put("pitch", pitch)
            put("bgs", 0)
            put("reg", 0)
            put("rdn", 0)
            // 评书开启韵律标注,强化抑扬顿挫
            put("rhy", if (isPingshu) 1 else 0)
            put("audio", audio)
        }

        val parameter = JSONObject().apply {
            put("oral", oral)
            put("tts", tts)
        }

        val text = JSONObject().apply {
            put("encoding", "utf8")
            put("compress", "raw")
            put("format", "plain")
            put("status", 2)
            put("seq", 0)
            put("text", textBase64)
        }

        val payload = JSONObject().apply {
            put("text", text)
        }

        return JSONObject().apply {
            put("header", header)
            put("parameter", parameter)
            put("payload", payload)
        }.toString()
    }

    /**
     * 讯飞超拟人 WebAPI 常见错误码翻译,直接告诉用户去控制台哪一步做什么。
     */
    private fun friendlyError(code: Int, raw: String): String = when (code) {
        10005 -> "讯飞超拟人 AppID 不存在 (code=10005): $raw\n请检查 AppID 是否填错。"
        10010 -> "讯飞超拟人鉴权失败 (code=10010): $raw\n请检查 APIKey/APISecret 是否复制完整。"
        10313 -> "讯飞超拟人 APIKey 与 AppID 不匹配 (code=10313): $raw\n请到控制台「我的应用 → 选中本应用 → 应用概览」核对三件套必须来自同一个应用。"
        11200 -> "讯飞超拟人发音人未授权 (code=11200): $raw\n该发音人没有绑定到当前 AppID。请到控制台「超拟人语音合成 → 发音人授权管理」对该发音人点'立即领取免费'或'立即购买'。"
        11201 -> "讯飞超拟人日流量超限 (code=11201): 今日已经用满,明天再试或购买扩容。"
        11202 -> "讯飞超拟人秒级流量超限 (code=11202): QPS 超出免费额度。"
        else -> "讯飞超拟人返回错误 code=$code msg=$raw"
    }

    companion object {
        const val ENGINE_ID = "xunfei_super"
        const val KEY_APP_ID = "app_id"
        const val KEY_API_KEY = "api_key"
        const val KEY_API_SECRET = "api_secret"
        const val KEY_RESOURCE_ID = "resource_id"

        /** 标记此声音适合作为"评书"用,引擎会自动套用 oral_level=low + rhy=1。 */
        const val STYLE_PINGSHU = "pingshu"

        /** 评书首选音色的 vcn (儒雅大叔,普通话男声)。如果用户已自行添加同名预设,会被「评书一键预设」直接选中。 */
        const val PINGSHU_DEFAULT_VOICE_ID = "x6_ruyadashu_pro"
    }
}
