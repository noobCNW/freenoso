package com.xs.reader

import android.app.Application
import com.xs.reader.tts.MatchaModelManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class ReaderApp : Application() {

    @Inject lateinit var matchaModelManager: MatchaModelManager

    /**
     * App 级 IO scope。这里只是给"首启自动安装预装 matcha 模型"用的轻量背景任务,
     * 进程被杀会一起停, 下次启动会从 partial 文件接着干, 不需要 WorkManager。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 静默预装 matcha 离线 TTS 模型。
        // - 已就绪 (isModelComplete) → ensureReady 立即返回, 几乎 0 开销
        // - 首启 → 触发 installFromAssets 把 APK 内嵌的 16 个文件拷到 filesDir
        // - 一切失败也不影响其它引擎; 用户进 TTS 设置页能看到具体状态
        appScope.launch {
            runCatching { matchaModelManager.ensureReady() }
        }
    }
}
