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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 讯飞开放平台在线语音合成 API。需要 AppID/APIKey/APISecret。
 * 协议参考: https://www.xfyun.cn/doc/tts/online_tts/API.html
 * 返回 16k 16bit 单声道 PCM (raw),按 base64 在 JSON status 字段中分片下发。
 */
@Singleton
class XunfeiTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val keyStore: SecureKeyStore,
    private val prefsRepo: ReadingPrefsRepository
) : TtsEngine {

    override val id: String = "xunfei"
    override val displayName: String = "讯飞 TTS（在线·需 Key）"
    override val needsNetwork: Boolean = true
    override val needsApiKey: Boolean = true

    private val cacheDir = File(context.cacheDir, "tts").apply { mkdirs() }

    /**
     * 讯飞 WebAPI 没有「列出当前账号已开通的发音人」的接口,所以这里读用户在
     * 设置页手动添加的预设列表。第一次运行时列表为空,UI 会引导用户照着
     * 控制台 → 我的发音人 把 vcn 抄进来。
     */
    override suspend fun listVoices(): List<TtsVoice> {
        val json = prefsRepo.flow.first().xunfeiVoicePresetsJson
        return XunfeiVoicePreset.listFromJson(json)
            .filter { it.engineId == id }
            .map { it.toTtsVoice() }
    }

    override fun synthesize(request: TtsRequest): Flow<TtsAudio> = callbackFlow {
        val appId = keyStore.get(id, "app_id")?.takeIf { it.isNotBlank() }
        val apiKey = keyStore.get(id, "api_key")?.takeIf { it.isNotBlank() }
        val apiSecret = keyStore.get(id, "api_secret")?.takeIf { it.isNotBlank() }
        if (appId.isNullOrBlank() || apiKey.isNullOrBlank() || apiSecret.isNullOrBlank()) {
            close(
                IllegalStateException(
                    "讯飞 TTS 凭据缺失,请到 设置 → 朗读引擎 → 讯飞 (普通版) 填写自己的 AppID/APIKey/APISecret 后再朗读。"
                )
            )
            return@callbackFlow
        }
        val authedUrl = buildAuthUrl(apiKey, apiSecret)
        val req = Request.Builder().url(authedUrl).build()

        val pcmBuffer = ByteArrayOutputStream()
        val ws = okHttpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val payload = buildSendPayload(appId, request)
                webSocket.send(payload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    val code = json.optInt("code", 0)
                    if (code != 0) {
                        close(IllegalStateException(friendlyXunfeiError(code, json.optString("message"))))
                        return
                    }
                    val data = json.optJSONObject("data") ?: return
                    val audio = data.optString("audio")
                    if (audio.isNotBlank()) {
                        val bytes = Base64.decode(audio, Base64.DEFAULT)
                        pcmBuffer.write(bytes)
                    }
                    val status = data.optInt("status", 0)
                    if (status == 2) {
                        val pcm = pcmBuffer.toByteArray()
                        val wavFile = File(cacheDir, "xunfei-${UUID.randomUUID()}.wav")
                        writePcmAsWav(wavFile, pcm, sampleRate = 16000, channels = 1)
                        trySend(TtsAudio.EncodedFile(wavFile.absolutePath, "audio/wav"))
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

    private fun buildAuthUrl(apiKey: String, apiSecret: String): String {
        val host = "tts-api.xfyun.cn"
        val path = "/v2/tts"
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val date = sdf.format(java.util.Date())
        val signOrigin = "host: $host\ndate: $date\nGET $path HTTP/1.1"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(), "HmacSHA256"))
        val signSha = mac.doFinal(signOrigin.toByteArray())
        val signature = Base64.encodeToString(signSha, Base64.NO_WRAP)
        val authOrigin = """api_key="$apiKey", algorithm="hmac-sha256", headers="host date request-line", signature="$signature""""
        val authorization = Base64.encodeToString(authOrigin.toByteArray(), Base64.NO_WRAP)
        // OkHttp 的 HttpUrl 只接受 http/https; 用 https 拼好后再交给 newWebSocket(),
        // OkHttp 内部会自动升级成 wss。
        val baseUrl = "https://$host$path".toHttpUrl()
            .newBuilder()
            .addQueryParameter("authorization", authorization)
            .addQueryParameter("date", date)
            .addQueryParameter("host", host)
            .build()
        return baseUrl.toString()
    }

    private fun buildSendPayload(appId: String, request: TtsRequest): String {
        val voiceName = request.voice.id.ifBlank { "xiaoyan" }
        val speed = (50 * request.speed).toInt().coerceIn(0, 100)
        val pitch = (50 * request.pitch).toInt().coerceIn(0, 100)
        val textBase64 = Base64.encodeToString(
            request.text.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val common = JSONObject().put("app_id", appId)
        val business = JSONObject().apply {
            put("aue", "raw")
            put("auf", "audio/L16;rate=16000")
            put("vcn", voiceName)
            put("speed", speed)
            put("volume", 70)
            put("pitch", pitch)
            put("tte", "UTF8")
        }
        val data = JSONObject().apply {
            put("status", 2)
            put("text", textBase64)
        }
        return JSONObject().apply {
            put("common", common)
            put("business", business)
            put("data", data)
        }.toString()
    }

    private fun writePcmAsWav(out: File, pcm: ByteArray, sampleRate: Int, channels: Int) {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = pcm.size + 36
        out.outputStream().use { os ->
            os.write("RIFF".toByteArray())
            os.write(intLE(totalDataLen))
            os.write("WAVE".toByteArray())
            os.write("fmt ".toByteArray())
            os.write(intLE(16))
            os.write(shortLE(1))
            os.write(shortLE(channels.toShort().toInt()))
            os.write(intLE(sampleRate))
            os.write(intLE(byteRate))
            os.write(shortLE((channels * bitsPerSample / 8).toShort().toInt()))
            os.write(shortLE(bitsPerSample.toShort().toInt()))
            os.write("data".toByteArray())
            os.write(intLE(pcm.size))
            os.write(pcm)
        }
    }

    private fun intLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 24) and 0xff).toByte()
    )
    private fun shortLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte()
    )

    @Suppress("unused")
    private fun urlEncode(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * 把讯飞 WebAPI 常见错误码翻译成可操作的人话提示,定位"该去哪改"。
     * 参考: https://www.xfyun.cn/document/error-code
     */
    private fun friendlyXunfeiError(code: Int, raw: String): String = when (code) {
        10005 -> "讯飞 AppID 不存在 (code=10005): $raw\n请检查 AppID 是否填错。"
        10006 -> "讯飞参数错误 (code=10006): $raw"
        10010 -> "讯飞鉴权失败 (code=10010): $raw\n请检查 APIKey/APISecret 是否复制完整。"
        10043 -> "讯飞音频路数超限 (code=10043): 同一 AppID 并发太多,稍候再试。"
        10160 -> "讯飞请求格式不合法 (code=10160): $raw"
        10313 -> "讯飞 APIKey 与 AppID 不匹配 (code=10313): $raw\n请到讯飞控制台「我的应用 → 选中本应用 → 应用概览」核对; APIKey/APISecret 必须来自同一个应用。"
        10317 -> "讯飞 APIKey 已被禁用 (code=10317): $raw"
        11200 -> "讯飞授权未通过 (code=11200): $raw\n该能力或发音人没有绑定到当前 AppID。请到控制台对应能力页点'立即领取/购买'。"
        11201 -> "讯飞日流量超限 (code=11201): 今日已经用满,明天再试或购买扩容。"
        11202 -> "讯飞秒级流量超限 (code=11202): QPS 超出免费额度。"
        else -> "讯飞返回错误 code=$code msg=$raw"
    }

}
