package com.xs.reader.tts

import android.content.Context
import android.util.Log
import com.iflytek.aikit.core.AiHelper
import com.iflytek.aikit.core.BaseLibrary
import com.iflytek.aikit.core.CoreListener
import com.iflytek.aikit.core.ErrType
import com.iflytek.aikit.core.LogLvl
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 讯飞 AIKit 离线 SDK 全局单例管理器。
 *
 * 职责:
 *  - 把 assets/iflytek/xtts 下的所有音色文件拷到 app 私有目录 (filesDir/iflytek/xtts/) — 离线引擎要从磁盘读音色资源
 *  - 第一次使用时调用 `BaseLibrary.initEntry()` 做全局初始化 (异步,通过 CoreListener 回调)
 *  - 用 CompletableDeferred 把异步鉴权结果包装成可挂起 await
 *  - 同一进程内的多个朗读请求共享一份 init,不会重复初始化
 *
 * 注意: SDK 的 init 必须在拿到 AppID/APIKey/APISecret 之后再做,
 * 而这三个是用户在设置页填的,所以 init 不能放在 Application.onCreate(),
 * 必须延迟到第一次合成时执行。
 */
@Singleton
class XunfeiOfflineSdkManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore
) {
    enum class State { Uninitialized, Initializing, Ready, AuthFailed, MissingKeys }

    @Volatile var state: State = State.Uninitialized
        private set
    @Volatile var lastAuthCode: Int = -1
        private set
    @Volatile var lastErrorMessage: String? = null
        private set

    private val initMutex = Mutex()

    private val workDir: File by lazy {
        File(context.filesDir, "iflytek").apply { mkdirs() }
    }
    private val resourceDir: File by lazy {
        File(workDir, "xtts").apply { mkdirs() }
    }

    /**
     * 保证 SDK 已经初始化并完成鉴权。
     * 同一进程多次调用安全,只有第一次会真正做 initEntry。
     *
     * @return true 表示可用; false 表示授权失败/缺凭据,具体原因看 [state] 和 [lastErrorMessage]
     */
    suspend fun ensureReady(): Boolean = initMutex.withLock {
        if (state == State.Ready) return true

        val appId = keyStore.get(ENGINE_ID, "app_id")
        val apiKey = keyStore.get(ENGINE_ID, "api_key")
        val apiSecret = keyStore.get(ENGINE_ID, "api_secret")
        if (appId.isNullOrBlank() || apiKey.isNullOrBlank() || apiSecret.isNullOrBlank()) {
            state = State.MissingKeys
            lastErrorMessage = "讯飞离线引擎缺少凭据,请到设置 → 朗读引擎 → 讯飞离线 填写 AppID/APIKey/APISecret"
            return false
        }

        state = State.Initializing
        lastErrorMessage = null

        // 1) 拷贝音色资源到 filesDir (只在文件不存在或大小不一致时拷)
        runCatching { copyAssetsIfNeeded() }
            .onFailure {
                state = State.AuthFailed
                lastErrorMessage = "讯飞离线音色资源拷贝失败: ${it.message}"
                return false
            }

        // 2) 初始化 SDK (initEntry 是阻塞调用; auth 是异步回调)
        val authDeferred = CompletableDeferred<Int>()
        AiHelper.getInst().registerListener(object : CoreListener {
            override fun onAuthStateChange(type: ErrType, code: Int) {
                Log.d(TAG, "onAuthStateChange type=$type code=$code")
                if (type == ErrType.AUTH && !authDeferred.isCompleted) {
                    authDeferred.complete(code)
                }
            }
        })

        runCatching {
            AiHelper.getInst().setLogInfo(LogLvl.INFO, 0, File(workDir, "aee.log").absolutePath)
        }

        val params = BaseLibrary.Params.builder()
            .appId(appId)
            .apiKey(apiKey)
            .apiSecret(apiSecret)
            .workDir(workDir.absolutePath)
            .build()

        runCatching {
            withContext(Dispatchers.IO) {
                AiHelper.getInst().initEntry(context.applicationContext, params)
            }
        }.onFailure {
            state = State.AuthFailed
            lastErrorMessage = "讯飞 AIKit initEntry 调用异常: ${it.message}"
            return false
        }

        // 3) 等待 onAuthStateChange 回调; 超过 20s 视为超时 (首次激活需要联网)
        val code = withTimeoutOrNull(20_000) { authDeferred.await() } ?: -1
        lastAuthCode = code
        return if (code == 0) {
            state = State.Ready
            true
        } else {
            state = State.AuthFailed
            lastErrorMessage = friendlyAuthError(code)
            false
        }
    }

    fun resourceDirAbsolutePath(): String = resourceDir.absolutePath

    private fun copyAssetsIfNeeded() {
        val assetMgr = context.assets
        val files = assetMgr.list("iflytek/xtts").orEmpty()
        if (files.isEmpty()) {
            throw IllegalStateException("assets/iflytek/xtts 目录为空,APK 打包未带音色资源")
        }
        for (name in files) {
            val outFile = File(resourceDir, name)
            // 优先用 openFd 拿原始大小做"已存在 + 大小一致"判断 (零拷贝)。
            // 但若 asset 被 AGP 压缩过 openFd 会抛 FileNotFoundException —— 此时
            // 整个 runCatching 块返回 null, 走下方"按字节流复制"分支。
            val inputSize: Long? = runCatching {
                assetMgr.openFd("iflytek/xtts/$name").use { it.length }
            }.getOrNull()

            if (outFile.exists() && inputSize != null && outFile.length() == inputSize) {
                continue
            }
            // 若拿不到精确 size, 退化到"文件存在且非空就跳过"; 杜绝重复 35MB 拷贝
            if (outFile.exists() && inputSize == null && outFile.length() > 0) {
                continue
            }

            assetMgr.open("iflytek/xtts/$name").use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "copied asset $name -> ${outFile.absolutePath} (${outFile.length()} bytes)")
        }
    }

    /**
     * 讯飞 AIKit 鉴权常见错误码 -> 人话提示。
     * 完整列表见 https://www.xfyun.cn/document/error-code (AIKit 段)。
     */
    private fun friendlyAuthError(code: Int): String = when (code) {
        -1 -> "讯飞离线鉴权超时(20s 未回调)。首次激活需要联网,请检查手机网络。"

        // 187xx: AIKit 鉴权 / 授权阶段错误 (官方错误码表,
        // https://www.xfyun.cn/doc/asr/AIkit_commandWord/Android-SDK.html)
        18700 -> "能力未授权 (code=18700)。当前 AppID 没有绑定本 SDK 能力,请到讯飞控制台「我的应用 → 离线语音合成 (高品质版)」点'立即领取/购买'。"
        18701 -> "网络不通 (code=18701),首次激活需要联网拉鉴权协议,请检查 WiFi / 移动数据是否能访问外网。"
        18702 -> "网关校验失败 (code=18702): 检查 ① 手机系统时间是否正确(校时不准会签名失败); ② APIKey / APISecret 是否完整复制(常见漏字符)。"
        18703 -> "云端响应格式异常 (code=18703),通常是网络劫持或中间代理篡改了授权响应,请换网络环境再试。"
        18704 -> "应用未注册 (code=18704),AppID 不存在或拼错。"
        18705 -> "APIKey / APISecret 校验失败 (code=18705),与 AppID 不匹配。请到控制台核对三个值都属于同一个应用。"
        18706 -> "引擎不支持当前 CPU 架构 (code=18706)。讯飞 AIKit XTTS10 SDK 只发布了 arm64-v8a / armeabi-v7a 的 .so,如果你手机是 x86 / x86_64 (常见于模拟器) 会失败。"
        18707 -> "授权已过期 (code=18707),请到控制台续期。"
        18708 -> "无可用授权 (code=18708)。常见两种原因:\n" +
            "① **装机量已用完** —— 离线 SDK 每个设备激活会消耗一个名额,请到讯飞控制台「我的应用 → 离线语音合成 (高品质版)」查看「剩余装机量」是不是 0; 如果是 0, 申请扩容、或在「装机管理」里释放掉旧的不用了的设备。\n" +
            "② **当前设备 CPU 不在能力支持架构内** —— 例如该 SDK 只支持 arm64, 但你手机是 32 位 armv7。"
        18709 -> "未找到 AppID 绑定的能力 (code=18709),请确认是否已购买离线合成 (高品质版) 这一项。"
        18710 -> "未找到 AppID 绑定的资源 (code=18710),发音人 .dat 资源没在 AppID 下开通。"

        // 旧版 MSC SDK 时期的鉴权错误码 (兼容性保留)
        18301 -> "AppID 不存在或不属于当前账号 (code=18301)"
        18302 -> "API Key 错误 (code=18302),与 AppID 不匹配"
        18303 -> "API Secret 错误 (code=18303),与 AppID 不匹配"
        18306 -> "讯飞离线 SDK 装机量已用完 (code=18306),请到控制台扩容"
        18307 -> "App 包名不在授权白名单 (code=18307)。讯飞控制台需要把 com.xs.reader 加进当前 SDK 的「应用包名」列表"
        18313 -> "授权已过期 (code=18313)"
        18316 -> "本机已经被另一个 AppID 激活,可换台机器或在控制台释放装机额度 (code=18316)"
        18321 -> "联网激活失败 (code=18321),请检查网络是否能访问讯飞授权服务器"

        else -> "讯飞离线鉴权失败 code=$code"
    }

    companion object {
        const val ENGINE_ID = "xunfei_offline"
        private const val TAG = "XunfeiOfflineSdk"
    }
}
