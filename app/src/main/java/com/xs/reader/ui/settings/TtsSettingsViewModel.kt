package com.xs.reader.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.xs.reader.data.prefs.ReadingPrefs
import com.xs.reader.data.prefs.ReadingPrefsRepository
import com.xs.reader.tts.SecureKeyStore
import com.xs.reader.tts.TtsAudio
import com.xs.reader.tts.TtsEngine
import com.xs.reader.tts.TtsEngineRegistry
import com.xs.reader.tts.TtsRequest
import com.xs.reader.tts.TtsVoice
import com.xs.reader.tts.XunfeiOfflineSdkManager
import com.xs.reader.tts.XunfeiSuperTtsEngine
import com.xs.reader.tts.XunfeiVoicePreset
import kotlinx.coroutines.flow.map
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TtsSettingsState(
    val engines: List<TtsEngine> = emptyList(),
    val activeEngineId: String = "system",
    val voices: List<TtsVoice> = emptyList(),
    val loadingVoices: Boolean = false,
    val error: String? = null
)

sealed class TtsTestState {
    data object Idle : TtsTestState()
    data class Synthesizing(val voiceId: String) : TtsTestState()
    data class Playing(val voiceId: String) : TtsTestState()
    data class Success(val voiceId: String) : TtsTestState()
    data class Error(val message: String) : TtsTestState()
}

/** 讯飞离线 SDK 在设置页的鉴权状态视图模型。 */
sealed class XunfeiOfflineAuthUiState {
    data object Idle : XunfeiOfflineAuthUiState()
    data object Activating : XunfeiOfflineAuthUiState()
    data object Ready : XunfeiOfflineAuthUiState()
    data class Failed(val reason: String) : XunfeiOfflineAuthUiState()
}

@HiltViewModel
class TtsSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefsRepo: ReadingPrefsRepository,
    private val registry: TtsEngineRegistry,
    private val keyStore: SecureKeyStore,
    private val xunfeiOfflineSdk: XunfeiOfflineSdkManager
) : ViewModel() {

    private val _state = MutableStateFlow(TtsSettingsState(engines = registry.all))
    val state: StateFlow<TtsSettingsState> = _state.asStateFlow()

    private val _testState = MutableStateFlow<TtsTestState>(TtsTestState.Idle)
    val testState: StateFlow<TtsTestState> = _testState.asStateFlow()

    private val _offlineAuthState = MutableStateFlow<XunfeiOfflineAuthUiState>(
        // 把 SdkManager 的当前状态映射到 UI 上,设置页第一次打开时就能反映出"已激活"
        when (xunfeiOfflineSdk.state) {
            XunfeiOfflineSdkManager.State.Ready -> XunfeiOfflineAuthUiState.Ready
            XunfeiOfflineSdkManager.State.Initializing -> XunfeiOfflineAuthUiState.Activating
            XunfeiOfflineSdkManager.State.AuthFailed,
            XunfeiOfflineSdkManager.State.MissingKeys ->
                XunfeiOfflineAuthUiState.Failed(xunfeiOfflineSdk.lastErrorMessage ?: "未激活")
            XunfeiOfflineSdkManager.State.Uninitialized -> XunfeiOfflineAuthUiState.Idle
        }
    )
    val offlineAuthState: StateFlow<XunfeiOfflineAuthUiState> = _offlineAuthState.asStateFlow()

    val prefs: StateFlow<ReadingPrefs> = prefsRepo.flow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingPrefs()
    )

    /** 当前已保存的所有讯飞自定义发音人 (普通版 + 超拟人 全集), UI 侧自行按 engineId 过滤。 */
    val xunfeiVoicePresets: StateFlow<List<XunfeiVoicePreset>> = prefsRepo.flow
        .map { XunfeiVoicePreset.listFromJson(it.xunfeiVoicePresetsJson) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var testPlayer: ExoPlayer? = null
    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            prefsRepo.flow.collect { p ->
                // 旧版本可能存了已废弃的 "edge" / "openai" 引擎 id, 回退到 system
                val validIds = registry.all.map { it.id }.toSet()
                val effectiveId = if (p.ttsActiveEngineId in validIds) p.ttsActiveEngineId else "system"
                if (effectiveId != p.ttsActiveEngineId) {
                    prefsRepo.setTtsActiveEngine(effectiveId)
                    prefsRepo.setTtsActiveVoice(null)
                }
                if (_state.value.activeEngineId != effectiveId) {
                    _state.update { it.copy(activeEngineId = effectiveId) }
                    refreshVoices()
                }
            }
        }
        refreshVoices()
    }

    fun refreshVoices() {
        val engineId = _state.value.activeEngineId
        val engine = registry.byId(engineId)
        viewModelScope.launch {
            _state.update { it.copy(loadingVoices = true, error = null) }
            try {
                val list = engine.listVoices()
                _state.update { it.copy(voices = list, loadingVoices = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loadingVoices = false, error = e.message) }
            }
        }
    }

    fun setActiveEngine(id: String) {
        viewModelScope.launch {
            prefsRepo.setTtsActiveEngine(id)
            prefsRepo.setTtsActiveVoice(null)
            _state.update { it.copy(activeEngineId = id) }
            refreshVoices()
        }
    }

    fun setActiveVoice(id: String) {
        viewModelScope.launch { prefsRepo.setTtsActiveVoice(id) }
    }

    fun setSpeed(v: Float) = viewModelScope.launch { prefsRepo.setTtsSpeed(v) }
    fun setPitch(v: Float) = viewModelScope.launch { prefsRepo.setTtsPitch(v) }

    /**
     * 主动触发讯飞离线 SDK 鉴权激活。设置页"立即激活"按钮调用。
     */
    fun activateXunfeiOffline() = viewModelScope.launch {
        _offlineAuthState.update { XunfeiOfflineAuthUiState.Activating }
        val ok = withContext(Dispatchers.IO) { xunfeiOfflineSdk.ensureReady() }
        _offlineAuthState.update {
            if (ok) XunfeiOfflineAuthUiState.Ready
            else XunfeiOfflineAuthUiState.Failed(xunfeiOfflineSdk.lastErrorMessage ?: "鉴权失败")
        }
    }

    /**
     * 一键切到"评书"推荐配置:
     *  - 引擎: 讯飞超拟人
     *  - 音色: 儒雅大叔 (引擎内部按 style=pingshu 自动追加 oral_level=low + rhy=1)
     *  - 语速: 0.85x
     *  - 音调: 0.9
     */
    fun applyPingshuPreset() = viewModelScope.launch {
        prefsRepo.setTtsActiveEngine(XunfeiSuperTtsEngine.ENGINE_ID)
        prefsRepo.setTtsActiveVoice(XunfeiSuperTtsEngine.PINGSHU_DEFAULT_VOICE_ID)
        prefsRepo.setTtsSpeed(0.85f)
        prefsRepo.setTtsPitch(0.9f)
        _state.update { it.copy(activeEngineId = XunfeiSuperTtsEngine.ENGINE_ID) }
        refreshVoices()
    }

    /**
     * 添加/更新一个讯飞自定义发音人。同 (engineId, vcn) 视作同一个,会被覆盖。
     */
    fun upsertVoicePreset(preset: XunfeiVoicePreset) = viewModelScope.launch {
        val current = XunfeiVoicePreset.listFromJson(prefsRepo.flow.firstOrNull()?.xunfeiVoicePresetsJson)
        val merged = current.filterNot { it.engineId == preset.engineId && it.vcn == preset.vcn } + preset
        prefsRepo.setXunfeiVoicePresetsJson(XunfeiVoicePreset.listToJson(merged))
        refreshVoices()
    }

    /** 删除一个讯飞自定义发音人; 如果删的是当前选中的, 把 activeVoice 清空。 */
    fun removeVoicePreset(engineId: String, vcn: String) = viewModelScope.launch {
        val current = XunfeiVoicePreset.listFromJson(prefsRepo.flow.firstOrNull()?.xunfeiVoicePresetsJson)
        val remaining = current.filterNot { it.engineId == engineId && it.vcn == vcn }
        prefsRepo.setXunfeiVoicePresetsJson(XunfeiVoicePreset.listToJson(remaining))
        val activeP = prefsRepo.flow.firstOrNull()
        if (activeP != null && activeP.ttsActiveEngineId == engineId && activeP.ttsActiveVoiceId == vcn) {
            prefsRepo.setTtsActiveVoice(null)
        }
        refreshVoices()
    }

    fun getStoredKey(key: String): String? = keyStore.get(_state.value.activeEngineId, key)
    fun setKey(key: String, value: String) {
        keyStore.put(_state.value.activeEngineId, key, value.takeIf { it.isNotBlank() })
    }

    /**
     * 把多个字段一次性写入当前引擎的 KeyStore。表单级"保存"按钮使用,
     * 避免在 onValueChange 里每次按键都写盘 (拖慢输入,也让"未保存"语义模糊)。
     */
    fun saveKeys(values: Map<String, String>) {
        val engineId = _state.value.activeEngineId
        values.forEach { (k, v) -> keyStore.put(engineId, k, v.takeIf { it.isNotBlank() }) }
    }

    /**
     * 试听指定音色; 不传则试听当前选中音色 (没选则用列表第一个)。
     * 流程: 引擎合成 1 句样例文本 -> 写本地缓存 -> ExoPlayer 播放。
     * 状态通过 [testState] 推送到 UI。
     */
    fun testVoice(voice: TtsVoice? = null) {
        stopTest()
        val target = voice
            ?: _state.value.voices.firstOrNull { it.id == prefs.value.ttsActiveVoiceId }
            ?: _state.value.voices.firstOrNull()
        if (target == null) {
            // voices 为空通常是 listVoices() 抛了异常 (例如系统 TTS 未安装),
            // 真正的原因已经存到了 state.error 里; 把它一起带出去,而不是给一句没用的"无可用音色"。
            val rootCause = _state.value.error?.takeIf { it.isNotBlank() }
            val msg = if (rootCause != null) "当前引擎没有可用音色: $rootCause"
            else "当前引擎没有可用音色"
            _testState.update { TtsTestState.Error(msg) }
            return
        }
        val engineId = _state.value.activeEngineId
        _testState.update { TtsTestState.Synthesizing(target.id) }
        testJob = viewModelScope.launch {
            try {
                val engine = registry.byId(engineId)
                val req = TtsRequest(
                    text = SAMPLE_TEXT,
                    voice = target,
                    speed = prefs.value.ttsSpeed,
                    pitch = prefs.value.ttsPitch
                )
                val audio = withContext(Dispatchers.IO) {
                    engine.synthesize(req).firstOrNull()
                }
                if (audio == null) {
                    _testState.update {
                        TtsTestState.Error("引擎未返回音频 (引擎: $engineId, 音色: ${target.id})")
                    }
                    return@launch
                }
                playOnTestPlayer(audio, target.id)
            } catch (e: Exception) {
                _testState.update {
                    TtsTestState.Error(
                        "${e.javaClass.simpleName}: ${e.message ?: "未知错误"} (引擎: $engineId, 音色: ${target.id})"
                    )
                }
            }
        }
    }

    fun stopTest() {
        testJob?.cancel()
        testJob = null
        testPlayer?.stop()
        testPlayer?.clearMediaItems()
        if (_testState.value !is TtsTestState.Idle) {
            _testState.update { TtsTestState.Idle }
        }
    }

    fun clearTestError() {
        if (_testState.value is TtsTestState.Error) {
            _testState.update { TtsTestState.Idle }
        }
    }

    private fun playOnTestPlayer(audio: TtsAudio, voiceId: String) {
        val mediaItem = when (audio) {
            is TtsAudio.EncodedFile -> MediaItem.fromUri("file://${audio.filePath}")
            is TtsAudio.Pcm -> MediaItem.fromUri(audio.toWavFile(context).toURI().toString())
        }
        val p = testPlayer ?: ExoPlayer.Builder(context).build().also { player ->
            testPlayer = player
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        _testState.update { TtsTestState.Playing(voiceId) }
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        _testState.update { TtsTestState.Success(voiceId) }
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    _testState.update {
                        TtsTestState.Error("播放失败: ${error.message ?: error.errorCodeName}")
                    }
                }
            })
        }
        p.setMediaItem(mediaItem)
        p.prepare()
        p.play()
    }

    override fun onCleared() {
        testJob?.cancel()
        testPlayer?.release()
        testPlayer = null
    }

    companion object {
        private const val SAMPLE_TEXT = "这是一段朗读测试，能听到这句话说明朗读功能配置正常。"
    }
}

private fun TtsAudio.Pcm.toWavFile(context: Context): File {
    val dir = File(context.cacheDir, "tts").apply { mkdirs() }
    val out = File(dir, "test-pcm-${UUID.randomUUID()}.wav")
    val bitsPerSample = bitDepth
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val totalDataLen = bytes.size + 36
    out.outputStream().use { os ->
        os.write("RIFF".toByteArray())
        os.write(intLE(totalDataLen))
        os.write("WAVE".toByteArray())
        os.write("fmt ".toByteArray())
        os.write(intLE(16))
        os.write(shortLE(1))
        os.write(shortLE(channels))
        os.write(intLE(sampleRate))
        os.write(intLE(byteRate))
        os.write(shortLE(channels * bitsPerSample / 8))
        os.write(shortLE(bitsPerSample))
        os.write("data".toByteArray())
        os.write(intLE(bytes.size))
        os.write(bytes)
    }
    return out
}

private fun intLE(v: Int) = byteArrayOf(
    (v and 0xff).toByte(),
    ((v shr 8) and 0xff).toByte(),
    ((v shr 16) and 0xff).toByte(),
    ((v shr 24) and 0xff).toByte()
)
private fun shortLE(v: Int) = byteArrayOf(
    (v and 0xff).toByte(),
    ((v shr 8) and 0xff).toByte()
)
