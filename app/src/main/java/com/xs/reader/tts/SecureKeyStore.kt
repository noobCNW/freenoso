package com.xs.reader.tts

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "tts_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            context.getSharedPreferences("tts_secrets_plain", Context.MODE_PRIVATE)
        }
    }

    fun put(engineId: String, key: String, value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove("${engineId}__$key")
            else putString("${engineId}__$key", value)
        }.apply()
    }

    fun get(engineId: String, key: String): String? =
        prefs.getString("${engineId}__$key", null)

    /**
     * 把旧版本下讯飞两个在线引擎 (xunfei / xunfei_super) 各自存的
     * AppID / APIKey / APISecret 合并到共享命名空间 [XUNFEI_SHARED_NS] 中,
     * 顺带清理已下线的 xunfei_offline 命名空间残留 (老版本曾有讯飞离线引擎,
     * 0.2.0 移除, 这里把它在 EncryptedSharedPreferences 里的遗留键一并扫掉)。
     *
     * 这两个在线引擎的鉴权值在讯飞控制台是同一应用的同一份凭据, 没理由让用户填两次。
     * 共享后用户只填一次, 切到任何讯飞引擎都立即生效。
     *
     * 迁移策略:
     *  1. 优先使用 [XUNFEI_SHARED_NS] 下已有的值 (已存在视为最权威, 不覆盖)
     *  2. 否则按 xunfei > xunfei_super > xunfei_offline 顺序取第一个非空值写入共享
     *  3. 清理掉除共享之外所有旧 namespace 下的 app_id/api_key/api_secret
     *
     * 注意: `resource_id` 是讯飞超拟人独有, 不在迁移范围内, 仍保留在 xunfei_super 下。
     */
    fun migrateLegacyXunfeiKeys() {
        val sharedKeys = listOf("app_id", "api_key", "api_secret")
        // xunfei_offline 已下线但保留在列表里, 让本方法把旧版用户在那个 namespace 下
        // 残留的凭据一并合并/清理掉; 删了这一条会导致老数据沉底污染 EncryptedSharedPreferences。
        val legacyEngines = listOf("xunfei", "xunfei_super", "xunfei_offline")

        var changed = false
        prefs.edit().apply {
            sharedKeys.forEach { field ->
                val existingShared = get(XUNFEI_SHARED_NS, field)
                if (existingShared.isNullOrBlank()) {
                    val firstNonBlank = legacyEngines
                        .filter { it != XUNFEI_SHARED_NS }
                        .firstNotNullOfOrNull { ng ->
                            get(ng, field)?.takeIf { it.isNotBlank() }
                        }
                    if (!firstNonBlank.isNullOrBlank()) {
                        putString("${XUNFEI_SHARED_NS}__$field", firstNonBlank)
                        changed = true
                    }
                }
                // 清理冗余的旧 engine-scoped 副本 (xunfei 本身就是共享 ns, 不动)
                legacyEngines
                    .filter { it != XUNFEI_SHARED_NS }
                    .forEach { ng -> remove("${ng}__$field"); changed = true }
            }
        }.apply()
        // changed 仅用于将来可能加的日志/上报; 当前不需要导出
    }

    companion object {
        /**
         * 讯飞 (普通版 / 超拟人) 共享凭据的存储命名空间。
         * 跟"xunfei"普通版引擎 id 取同名是有意的: 老用户在普通版下已存的凭据可被自然继承,
         * 不需要任何迁移就生效。
         */
        const val XUNFEI_SHARED_NS = "xunfei"
    }
}
