package com.xs.reader.tts

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * matcha-icefall-zh-baker 离线神经 TTS 模型管理器。
 *
 * 模型由两部分组成,16 个散文件 ~100MB:
 *  1) 声学模型 + 中文字典:   HF repo  csukuangfj/matcha-icefall-zh-baker (~96MB, 15 个文件)
 *  2) 声码器 hifigan_v2.onnx: GitHub Release  sherpa-onnx tag=vocoder-models  (~3.6MB)
 *
 * 提供两条就位路径,按优先级生效:
 *
 *  A) ★ 预装 (默认): 全部 16 个文件已经放在 APK assets/[MODEL_NAME]/ 下,
 *     App 首启时 [bootstrapFromAssets] 会把它们一次性拷贝到 filesDir。
 *     拷贝速度受 IO 限制, 真机上一般 5-15s, 之后永久 Ready。
 *
 *  B) 在线下载 (兜底): 当 APK 内 assets 缺失 (例如 future 改成精简版 APK) 或者
 *     拷贝失败时, 仍可走 [ensureReady] 从 hf-mirror.com 散文件下载。
 *
 * 选散文件 (不解 tar.bz2) 的原因:
 *  - matcha-icefall-zh-baker.tar.bz2 (官方 tts-models release) 里 *没有* hifigan_*.onnx,
 *    sherpa-onnx 把声码器单独发在 vocoder-models 那个 release 里。
 *    见: https://github.com/k2-fsa/sherpa-onnx/releases/tag/vocoder-models
 *  - 大陆访问 hf-mirror.com 拉散文件远比 GitHub Release CDN 整包稳定。
 *  - 不需要 Apache Commons Compress 解 tar.bz2。
 *
 * 落盘布局:
 *   filesDir/tts/matcha-icefall-zh-baker/
 *      ├── model-steps-3.onnx              声学模型, 74 MB
 *      ├── hifigan_v2.onnx                 声码器, 3.6 MB
 *      ├── lexicon.txt / tokens.txt
 *      ├── phone.fst / date.fst / number.fst
 *      └── dict/
 *          ├── jieba.dict.utf8 / hmm_model.utf8 / idf.utf8 / stop_words.utf8 / user.dict.utf8
 *          └── pos_dict/{char_state_tab,prob_emit,prob_start,prob_trans}.utf8
 *
 * 同步策略:
 *  - Mutex 保证同进程内只有一次 ensureReady/bootstrap 在跑
 *  - 单个文件下载 / 拷贝到 .partial → 完成后 rename → 失败保留 partial 给下次断点
 *  - 任一关键文件最终缺失时 state=Failed,UI 给"重新安装"
 */
@Singleton
class MatchaModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * State:
     *  - Missing     : 模型尚未就绪(初次启动 / 用户主动删除后)
     *  - Installing  : 正在从 APK assets 拷贝预装文件
     *  - Downloading : 正在从网络下载(兜底路径, assets 不可用时才会进入)
     *  - Ready       : 16 个文件全部就位, 可直接 TTS
     *  - Failed      : 任一步骤出错; lastErrorMessage 给原因
     */
    enum class State { Missing, Installing, Downloading, Ready, Failed }

    private val _state = MutableStateFlow(State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 总体进度 0f..1f, 按"已下载字节 / 全部字节"算, 不是按文件数。 */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    /** 当前正在下载的文件名,UI 可显示"正在下载 jieba.dict.utf8..."。 */
    private val _currentFile = MutableStateFlow("")
    val currentFile: StateFlow<String> = _currentFile.asStateFlow()

    @Volatile var lastErrorMessage: String? = null
        private set

    /** 模型最终目录。即使尚未下载,这个 File 对象也总是同一个,UI/引擎可缓存路径。 */
    val modelDir: File by lazy {
        File(context.filesDir, "tts/$MODEL_NAME").apply { parentFile?.mkdirs() }
    }

    private val mutex = Mutex()

    private val httpClient: OkHttpClient by lazy {
        // 单个最大的文件 74MB,读超时给 5 分钟兜底,call 超时 0 表示不限。
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    init {
        // 启动时同步检查一次:已下载完整就直接 Ready, 给设置页一个稳定起点
        if (isModelComplete()) {
            _state.value = State.Ready
            _progress.value = 1f
        }
    }

    /**
     * 检测 APK assets 里是否包含完整的预装模型。
     * 用于在 UI 上区分"重新从预装安装" vs "重新从网络下载"两条路径。
     */
    fun hasBundledAssets(): Boolean {
        val am = context.assets
        return MODEL_FILES.all { spec ->
            runCatching { am.open("$MODEL_NAME/${spec.relPath}").use { } }.isSuccess
        }
    }

    /**
     * 模型是否完整可用。所有 [MODEL_FILES] 必须存在且大小至少达到 expectedBytes 的 80%
     * (允许镜像版本微小差异,不卡死在硬编码字节数上)。
     */
    fun isModelComplete(): Boolean {
        if (!modelDir.isDirectory) return false
        for (spec in MODEL_FILES) {
            val f = File(modelDir, spec.relPath)
            if (!f.isFile) return false
            if (spec.expectedBytes > 0 && f.length() < (spec.expectedBytes * 0.8).toLong()) {
                // 文件存在但明显太小, 说明下载被截断了
                return false
            }
            if (f.length() == 0L) return false
        }
        return true
    }

    /**
     * 保证模型已就位。优先用 APK assets 预装(快、稳),其次走在线下载兜底。
     * 同一进程并发安全。
     *
     * @return true 表示就绪; false 表示失败,具体原因看 [state] / [lastErrorMessage]
     */
    suspend fun ensureReady(): Boolean = mutex.withLock {
        if (isModelComplete()) {
            _state.value = State.Ready
            _progress.value = 1f
            return true
        }

        lastErrorMessage = null

        // 优先尝试从 APK 内预装资源拷贝
        if (hasBundledAssets()) {
            _state.value = State.Installing
            _progress.value = 0f
            try {
                withContext(Dispatchers.IO) { installFromAssets() }
            } catch (e: Exception) {
                _state.value = State.Failed
                lastErrorMessage = "从预装资源安装失败: ${e.message}"
                Log.e(TAG, "installFromAssets failed", e)
                return false
            }
            if (isModelComplete()) {
                _state.value = State.Ready
                _progress.value = 1f
                _currentFile.value = ""
                return true
            }
            // 预装意外不完整 (理论上不会发生), 继续往下走在线下载兜底
            Log.w(TAG, "bundled assets installed but isModelComplete()==false, fallback to network")
        }

        // 没预装资源 / 预装失败 → 走在线下载
        _state.value = State.Downloading
        _progress.value = 0f
        val ok = try {
            withContext(Dispatchers.IO) { downloadAll() }
        } catch (e: Exception) {
            _state.value = State.Failed
            lastErrorMessage = "下载失败: ${e.message}"
            Log.e(TAG, "downloadAll failed", e)
            return false
        }
        if (!ok) {
            _state.value = State.Failed
            return false
        }

        if (!isModelComplete()) {
            _state.value = State.Failed
            lastErrorMessage = "下载完成但部分文件缺失或截断,请删除模型重试。"
            return false
        }
        _state.value = State.Ready
        _progress.value = 1f
        _currentFile.value = ""
        return true
    }

    // ---- 从 APK assets 预装 -----------------------------------------------

    /**
     * 把 assets/$MODEL_NAME/ 下所有模型文件拷贝到 [modelDir]。
     * 进度按"已拷贝字节 / 全部 expected 字节"算; 单文件成功后再 rename 替换正式文件,
     * 中途失败下次启动会自动重试缺失的那部分。
     */
    private fun installFromAssets() {
        val am: AssetManager = context.assets
        val totalBytes = MODEL_FILES.sumOf { it.expectedBytes }.coerceAtLeast(1L)
        var doneBytes = 0L

        for (spec in MODEL_FILES) {
            val finalFile = File(modelDir, spec.relPath)
            // 已经在了就跳过, 支持中途失败后续传
            if (finalFile.isFile &&
                (spec.expectedBytes <= 0 || finalFile.length() >= (spec.expectedBytes * 0.8).toLong()) &&
                finalFile.length() > 0L
            ) {
                doneBytes += spec.expectedBytes
                _progress.value = (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                continue
            }

            _currentFile.value = spec.relPath
            finalFile.parentFile?.mkdirs()
            val partial = File(finalFile.parentFile, finalFile.name + ".partial")
            partial.delete()

            val assetPath = "$MODEL_NAME/${spec.relPath}"
            am.open(assetPath).use { input ->
                BufferedOutputStream(partial.outputStream()).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var written = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        written += read
                        val global = (doneBytes + written).toFloat() / totalBytes
                        _progress.value = global.coerceIn(0f, 1f)
                    }
                    out.flush()
                }
            }

            // sanity: 拷贝出的文件大小明显与预期不符 → assets 损坏
            if (spec.expectedBytes > 0 && partial.length() < (spec.expectedBytes * 0.5).toLong()) {
                partial.delete()
                throw IOException("asset ${spec.relPath} too small (${partial.length()}B vs expected ~${spec.expectedBytes}B)")
            }

            if (finalFile.exists()) finalFile.delete()
            if (!partial.renameTo(finalFile)) {
                partial.copyTo(finalFile, overwrite = true)
                partial.delete()
            }
            doneBytes += spec.expectedBytes
            _progress.value = (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        }
    }

    /**
     * 构造 sherpa-onnx 的 [OfflineTtsConfig]。模型未就绪时返回 null。
     *
     * 关于模型选择:
     *  - 声学模型: `model-steps-3.onnx` (3 步 ODE 解算, 速度/质量均衡, 移动端推荐)
     *  - 声码器:   `hifigan_v2.onnx`    (~3.6MB, 比 vocos 小 14 倍且 RTF 更友好)
     *  - dictDir:  jieba 中文分词词典, 中文 lexicon 模式必需
     *
     * length / noise scale 用 sherpa 官方建议默认值;
     * 真正的"语速控制"通过 [com.k2fsa.sherpa.onnx.OfflineTts.generate] 的 lengthScale 参数动态传入,
     * 这里 config 里这个值只是引擎兜底,不会被实际朗读用到。
     */
    fun buildConfig(numThreads: Int = 2): OfflineTtsConfig? {
        if (!isModelComplete()) return null
        val matcha = OfflineTtsMatchaModelConfig(
            acousticModel = File(modelDir, "model-steps-3.onnx").absolutePath,
            vocoder = File(modelDir, "hifigan_v2.onnx").absolutePath,
            lexicon = File(modelDir, "lexicon.txt").absolutePath,
            tokens = File(modelDir, "tokens.txt").absolutePath,
            dataDir = "",
            dictDir = File(modelDir, "dict").absolutePath,
            noiseScale = 0.667f,
            lengthScale = 1.0f
        )
        val model = OfflineTtsModelConfig().apply {
            this.matcha = matcha
            this.numThreads = numThreads
            debug = false
            provider = "cpu"
        }
        return OfflineTtsConfig().apply {
            this.model = model
            ruleFsts = ""
            ruleFars = ""
            maxNumSentences = 1
            silenceScale = 0.2f
        }
    }

    /** 整体删除已下载的模型(给 UI"重新下载"按钮用)。 */
    suspend fun deleteModel() = mutex.withLock {
        withContext(Dispatchers.IO) {
            modelDir.deleteRecursively()
        }
        _state.value = State.Missing
        _progress.value = 0f
        _currentFile.value = ""
        lastErrorMessage = null
    }

    // ---- 下载主流程 -------------------------------------------------------

    /**
     * 依次下载所有 [MODEL_FILES]。已存在且大小正确的文件直接跳过(支持中断后续传)。
     * 每个文件按多个 mirror 顺序尝试,任一成功就继续下一个文件。
     */
    private suspend fun downloadAll(): Boolean {
        val totalBytes = MODEL_FILES.sumOf { it.expectedBytes }.coerceAtLeast(1L)
        var doneBytes = 0L

        for (spec in MODEL_FILES) {
            val finalFile = File(modelDir, spec.relPath)
            // 已经下好且大小合理 → 跳过
            if (finalFile.isFile &&
                (spec.expectedBytes <= 0 || finalFile.length() >= (spec.expectedBytes * 0.8).toLong()) &&
                finalFile.length() > 0L
            ) {
                doneBytes += spec.expectedBytes
                _progress.value = (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                continue
            }

            _currentFile.value = spec.relPath
            finalFile.parentFile?.mkdirs()

            val urls = spec.mirrors
            var fileDone = false
            var lastErr: Exception? = null
            for ((idx, url) in urls.withIndex()) {
                try {
                    Log.i(TAG, "downloading ${spec.relPath} from #$idx $url")
                    downloadFile(url, finalFile, baseProgressBytes = doneBytes, totalBytes = totalBytes, fileExpected = spec.expectedBytes)
                    fileDone = true
                    break
                } catch (e: Exception) {
                    lastErr = e
                    Log.w(TAG, "mirror #$idx for ${spec.relPath} failed: ${e.message}")
                    // 失败时把 partial 清掉, 换下一个 mirror 重头来
                    val partial = File(finalFile.parentFile, finalFile.name + ".partial")
                    partial.delete()
                }
            }
            if (!fileDone) {
                lastErrorMessage = "下载 ${spec.relPath} 失败(已尝试 ${urls.size} 个镜像): ${lastErr?.message ?: "未知"}"
                return false
            }
            doneBytes += spec.expectedBytes
            _progress.value = (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
        }
        return true
    }

    /**
     * 下载单个文件到 [finalFile]。
     * 通过 .partial 临时文件 → rename 实现原子替换。
     * 同时把"本文件已下载字节"加到 baseProgressBytes 上,更新全局 progress。
     */
    private fun downloadFile(
        url: String,
        finalFile: File,
        baseProgressBytes: Long,
        totalBytes: Long,
        fileExpected: Long
    ) {
        val partial = File(finalFile.parentFile, finalFile.name + ".partial")
        partial.delete()

        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            val totalThisFile = body.contentLength().takeIf { it > 0 } ?: fileExpected

            body.byteStream().use { input ->
                BufferedOutputStream(partial.outputStream()).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var written = 0L
                    var lastReportedPct = -1
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        written += read
                        // 全局进度: (历史已完成的字节 + 本文件当前已下) / 总字节
                        val global = (baseProgressBytes + written).toFloat() / totalBytes
                        _progress.value = global.coerceIn(0f, 1f)
                        if (totalThisFile > 0) {
                            val pct = ((written.toFloat() / totalThisFile) * 20).toInt()
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                Log.d(TAG, "  ${finalFile.name}: ${pct * 5}% ($written / $totalThisFile)")
                            }
                        }
                    }
                    out.flush()
                }
            }
        }

        // sanity check: 拿到的 body 明显比预期小,大概率是 redirect 错误页 / 404 HTML
        if (fileExpected > 0 && partial.length() < (fileExpected * 0.5).toLong()) {
            partial.delete()
            throw IOException("downloaded ${finalFile.name} too small (${partial.length()} bytes, expected ~$fileExpected)")
        }

        if (finalFile.exists()) finalFile.delete()
        if (!partial.renameTo(finalFile)) {
            // 跨设备 rename 兜底
            partial.copyTo(finalFile, overwrite = true)
            partial.delete()
        }
    }

    /**
     * 一个待下载文件的描述:
     *  - relPath: 相对 modelDir 的路径,例如 "dict/jieba.dict.utf8"
     *  - expectedBytes: 大致字节数,用于校验防止半截下载冒充成功; <=0 表示不校验
     *  - mirrors: 按优先级排序的 URL 列表
     */
    private data class FileSpec(
        val relPath: String,
        val expectedBytes: Long,
        val mirrors: List<String>
    )

    companion object {
        const val MODEL_NAME = "matcha-icefall-zh-baker"
        private const val TAG = "MatchaModelMgr"

        // --- 镜像 URL 生成 ----------------------------------------------------

        /**
         * 声学模型 / 字典 / fst 的散文件,在 HF repo `csukuangfj/matcha-icefall-zh-baker` 下。
         * 主源用 hf-mirror.com (国内可达), 备源用 huggingface.co (海外加速)。
         */
        private fun hfFile(relPath: String): List<String> = listOf(
            "https://hf-mirror.com/csukuangfj/$MODEL_NAME/resolve/main/$relPath",
            "https://huggingface.co/csukuangfj/$MODEL_NAME/resolve/main/$relPath"
        )

        /**
         * hifigan_v2.onnx 在 sherpa-onnx 的 vocoder-models release 下,
         * 也在 HF csukuangfj/vocoder-models 这个 repo (有备份)。
         */
        private val VOCODER_MIRRORS = listOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/hifigan_v2.onnx",
            "https://hf-mirror.com/csukuangfj/vocoder-models/resolve/main/hifigan_v2.onnx",
            "https://huggingface.co/csukuangfj/vocoder-models/resolve/main/hifigan_v2.onnx"
        )

        /**
         * 完整文件清单 + 大致字节数。字节数来自 HF API 实测 (2026-05),
         * 校验时只要求达到 expectedBytes 的 50-80%,允许镜像差异。
         */
        private val MODEL_FILES: List<FileSpec> = listOf(
            FileSpec("model-steps-3.onnx",       73_852L * 1024, hfFile("model-steps-3.onnx")),
            FileSpec("hifigan_v2.onnx",           3_661L * 1024, VOCODER_MIRRORS),
            FileSpec("lexicon.txt",               1_331L * 1024, hfFile("lexicon.txt")),
            FileSpec("tokens.txt",                   19L * 1024, hfFile("tokens.txt")),
            FileSpec("phone.fst",                    86L * 1024, hfFile("phone.fst")),
            FileSpec("date.fst",                     57L * 1024, hfFile("date.fst")),
            FileSpec("number.fst",                   62L * 1024, hfFile("number.fst")),
            FileSpec("dict/jieba.dict.utf8",      4_952L * 1024, hfFile("dict/jieba.dict.utf8")),
            FileSpec("dict/hmm_model.utf8",         507L * 1024, hfFile("dict/hmm_model.utf8")),
            FileSpec("dict/idf.utf8",             5_858L * 1024, hfFile("dict/idf.utf8")),
            FileSpec("dict/stop_words.utf8",          8L * 1024, hfFile("dict/stop_words.utf8")),
            FileSpec("dict/user.dict.utf8",         809L * 1024, hfFile("dict/user.dict.utf8")),
            FileSpec("dict/pos_dict/char_state_tab.utf8",  319L * 1024, hfFile("dict/pos_dict/char_state_tab.utf8")),
            FileSpec("dict/pos_dict/prob_emit.utf8",     1_648L * 1024, hfFile("dict/pos_dict/prob_emit.utf8")),
            FileSpec("dict/pos_dict/prob_start.utf8",        4L * 1024, hfFile("dict/pos_dict/prob_start.utf8")),
            FileSpec("dict/pos_dict/prob_trans.utf8",      121L * 1024, hfFile("dict/pos_dict/prob_trans.utf8")),
        )
    }
}
