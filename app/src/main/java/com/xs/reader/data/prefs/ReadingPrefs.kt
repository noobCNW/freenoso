package com.xs.reader.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "reading_prefs")

data class ReadingPrefs(
    val themeId: String = "day",
    val fontFamilyId: String = "default",
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.6f,
    val paragraphSpacingSp: Float = 8f,
    val pageMarginDp: Float = 18f,
    val turnMode: String = "horizontal",
    val keepScreenOn: Boolean = true,
    val appDarkMode: Boolean = false,
    val followSystemDark: Boolean = true,
    val customFontPath: String? = null,
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsActiveEngineId: String = "system",
    val ttsActiveVoiceId: String? = null,
    /** 是否在每章首页正文上方显示章节标题(大字)。 */
    val showChapterTitle: Boolean = false,
    /**
     * 自动阅读速度,1~10 之间的归一化数值,值越大越快。
     * - 横向翻页模式:间隔时长 = 14s / speed * 0.7,即速度 5 时约 4s/页,速度 10 约 2s/页。
     * - 滚动模式:像素/秒 = 24 * speed,即速度 5 ≈ 120 px/s,速度 10 ≈ 240 px/s。
     */
    val autoReadSpeed: Float = 5f,
    /**
     * 用户为讯飞 (普通版/超拟人) 自行添加的发音人预设, 序列化为 JSON 串。
     * 由 `XunfeiVoicePreset.listToJson` / `listFromJson` 解析。
     * 离线/系统 TTS 不走这里, 它们音色固定。
     */
    val xunfeiVoicePresetsJson: String? = null
)

@Singleton
class ReadingPrefsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val THEME_ID = stringPreferencesKey("theme_id")
        val FONT_FAMILY = stringPreferencesKey("font_family_id")
        val FONT_SIZE = floatPreferencesKey("font_size")
        val LINE_HEIGHT = floatPreferencesKey("line_height")
        val PARAGRAPH_SPACING = floatPreferencesKey("paragraph_spacing")
        val PAGE_MARGIN = floatPreferencesKey("page_margin")
        val TURN_MODE = stringPreferencesKey("turn_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val APP_DARK_MODE = booleanPreferencesKey("app_dark_mode")
        val FOLLOW_SYSTEM_DARK = booleanPreferencesKey("follow_system_dark")
        val CUSTOM_FONT_PATH = stringPreferencesKey("custom_font_path")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val TTS_ACTIVE_ENGINE = stringPreferencesKey("tts_engine_id")
        val TTS_ACTIVE_VOICE = stringPreferencesKey("tts_voice_id")
        val SHOW_CHAPTER_TITLE = booleanPreferencesKey("show_chapter_title")
        val AUTO_READ_SPEED = floatPreferencesKey("auto_read_speed")
        val XUNFEI_VOICE_PRESETS = stringPreferencesKey("xunfei_voice_presets_json")
    }

    val flow: Flow<ReadingPrefs> = context.dataStore.data.map(::map)

    private fun map(p: Preferences): ReadingPrefs = ReadingPrefs(
        themeId = p[Keys.THEME_ID] ?: "day",
        fontFamilyId = p[Keys.FONT_FAMILY] ?: "default",
        fontSizeSp = p[Keys.FONT_SIZE] ?: 18f,
        lineHeightMultiplier = p[Keys.LINE_HEIGHT] ?: 1.6f,
        paragraphSpacingSp = p[Keys.PARAGRAPH_SPACING] ?: 8f,
        pageMarginDp = p[Keys.PAGE_MARGIN] ?: 18f,
        turnMode = p[Keys.TURN_MODE] ?: "horizontal",
        keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: true,
        appDarkMode = p[Keys.APP_DARK_MODE] ?: false,
        followSystemDark = p[Keys.FOLLOW_SYSTEM_DARK] ?: true,
        customFontPath = p[Keys.CUSTOM_FONT_PATH],
        ttsSpeed = p[Keys.TTS_SPEED] ?: 1.0f,
        ttsPitch = p[Keys.TTS_PITCH] ?: 1.0f,
        ttsActiveEngineId = p[Keys.TTS_ACTIVE_ENGINE] ?: "system",
        ttsActiveVoiceId = p[Keys.TTS_ACTIVE_VOICE],
        showChapterTitle = p[Keys.SHOW_CHAPTER_TITLE] ?: false,
        autoReadSpeed = (p[Keys.AUTO_READ_SPEED] ?: 5f).coerceIn(1f, 10f),
        xunfeiVoicePresetsJson = p[Keys.XUNFEI_VOICE_PRESETS]
    )

    suspend fun setThemeId(id: String) = context.dataStore.edit { it[Keys.THEME_ID] = id }
    suspend fun setFontFamilyId(id: String) = context.dataStore.edit { it[Keys.FONT_FAMILY] = id }
    suspend fun setFontSize(sp: Float) = context.dataStore.edit { it[Keys.FONT_SIZE] = sp }
    suspend fun setLineHeight(v: Float) = context.dataStore.edit { it[Keys.LINE_HEIGHT] = v }
    suspend fun setParagraphSpacing(sp: Float) = context.dataStore.edit { it[Keys.PARAGRAPH_SPACING] = sp }
    suspend fun setPageMargin(dp: Float) = context.dataStore.edit { it[Keys.PAGE_MARGIN] = dp }
    suspend fun setTurnMode(mode: String) = context.dataStore.edit { it[Keys.TURN_MODE] = mode }
    suspend fun setKeepScreenOn(v: Boolean) = context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = v }
    suspend fun setAppDarkMode(v: Boolean) = context.dataStore.edit { it[Keys.APP_DARK_MODE] = v }
    suspend fun setFollowSystemDark(v: Boolean) = context.dataStore.edit { it[Keys.FOLLOW_SYSTEM_DARK] = v }
    suspend fun setCustomFontPath(path: String?) = context.dataStore.edit {
        if (path == null) it.remove(Keys.CUSTOM_FONT_PATH) else it[Keys.CUSTOM_FONT_PATH] = path
    }
    suspend fun setTtsSpeed(v: Float) = context.dataStore.edit { it[Keys.TTS_SPEED] = v }
    suspend fun setTtsPitch(v: Float) = context.dataStore.edit { it[Keys.TTS_PITCH] = v }
    suspend fun setTtsActiveEngine(id: String) = context.dataStore.edit { it[Keys.TTS_ACTIVE_ENGINE] = id }
    suspend fun setTtsActiveVoice(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(Keys.TTS_ACTIVE_VOICE) else it[Keys.TTS_ACTIVE_VOICE] = id
    }
    suspend fun setShowChapterTitle(v: Boolean) = context.dataStore.edit { it[Keys.SHOW_CHAPTER_TITLE] = v }
    suspend fun setAutoReadSpeed(v: Float) = context.dataStore.edit {
        it[Keys.AUTO_READ_SPEED] = v.coerceIn(1f, 10f)
    }
    suspend fun setXunfeiVoicePresetsJson(json: String?) = context.dataStore.edit {
        if (json.isNullOrBlank()) it.remove(Keys.XUNFEI_VOICE_PRESETS) else it[Keys.XUNFEI_VOICE_PRESETS] = json
    }
}
