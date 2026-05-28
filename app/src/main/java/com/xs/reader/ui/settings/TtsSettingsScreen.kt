package com.xs.reader.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import kotlinx.coroutines.delay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xs.reader.tts.MatchaModelManager
import com.xs.reader.tts.TtsVoice
import com.xs.reader.tts.XunfeiSuperTtsEngine
import com.xs.reader.tts.XunfeiVoicePreset
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontFamily

@Composable
fun TtsSettingsScreen(
    onBack: () -> Unit,
    vm: TtsSettingsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val prefs by vm.prefs.collectAsState()
    val testState by vm.testState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.activeEngineId) { vm.refreshVoices() }
    DisposableEffect(Unit) { onDispose { vm.stopTest() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("朗读引擎") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("引擎", style = MaterialTheme.typography.titleMedium)
            FlowRowSimple(items = state.engines) { e ->
                FilterChip(
                    selected = state.activeEngineId == e.id,
                    onClick = { vm.setActiveEngine(e.id) },
                    label = { Text(e.displayName) }
                )
            }
            EngineHint(state.activeEngineId)

            // Engine-specific config inputs
            //
            // 两个讯飞在线引擎 (普通版 / 超拟人) 在控制台是同一应用同一份凭据,
            // 这里合并成一张「讯飞凭据」卡片 (XunfeiSharedCredCard) 共享显示。
            // 超拟人的 Resource ID 是独有的,单独再加一张小卡片。
            // matcha 引擎需要下载 ~105MB 模型才能用,挂自己的下载卡片。
            when (state.activeEngineId) {
                "xunfei", "xunfei_super" -> {
                    XunfeiSharedCredCard(vm = vm)
                    if (state.activeEngineId == "xunfei_super") {
                        XunfeiSuperResourceIdCard(vm = vm)
                    }
                }
                "matcha_zh_baker" -> MatchaModelCard(vm = vm)
                else -> {} // system 无需 Key
            }

            // 讯飞超拟人独有: 一键应用"评书"推荐预设 (儒雅大叔 + 慢速 + 略压低音调)
            if (state.activeEngineId == "xunfei_super") {
                PingshuPresetCard(onApply = { vm.applyPingshuPreset() })
            }

            // 系统 TTS 时,提供"前往系统设置"按钮,方便用户安装中文语音数据
            if (state.activeEngineId == "system") {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent("com.android.settings.TTS_SETTINGS")
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("前往系统 TTS 设置")
                }
            }

            TestCurrentVoiceCard(
                testState = testState,
                onTest = { vm.testVoice() },
                onStop = { vm.stopTest() },
                onClearError = { vm.clearTestError() }
            )

            // 讯飞两个在线引擎: 显示「我的发音人」编辑器 (用户自己加 vcn)
            if (state.activeEngineId == "xunfei" || state.activeEngineId == "xunfei_super") {
                XunfeiVoicePresetSection(vm = vm, engineId = state.activeEngineId)
            }

            Text("可用音色", style = MaterialTheme.typography.titleMedium)
            if (state.loadingVoices) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在加载音色…")
                }
            } else if (state.voices.isEmpty()) {
                val emptyHint = when (state.activeEngineId) {
                    "xunfei", "xunfei_super" ->
                        "暂无可用音色 (内置音色加载失败)。可到讯飞控制台「能力管理 → 我的发音人」复制 vcn 参数 (例如 x4_xiaoyan / aisjiuxu),用上方「+ 添加发音人」加进来。"
                    else -> "暂无可用音色,请检查 Key 或网络。"
                }
                Text(emptyHint, color = MaterialTheme.colorScheme.error)
            } else {
                state.voices.forEach { v: TtsVoice ->
                    VoiceRow(
                        voice = v,
                        selected = prefs.ttsActiveVoiceId == v.id && prefs.ttsActiveEngineId == v.engineId,
                        testState = testState,
                        onSelect = { vm.setActiveVoice(v.id) },
                        onTest = { vm.testVoice(v) }
                    )
                }
            }

            Text("语速  ${"%.1fx".format(prefs.ttsSpeed)}", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = prefs.ttsSpeed,
                onValueChange = { vm.setSpeed(it) },
                valueRange = 0.5f..2.0f,
                steps = 14
            )

            Text("音调  ${"%.1f".format(prefs.ttsPitch)}", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = prefs.ttsPitch,
                onValueChange = { vm.setPitch(it) },
                valueRange = 0.5f..2.0f,
                steps = 14
            )

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EngineHint(engineId: String) {
    val (text, color) = when (engineId) {
        "system" -> "完全离线,需要手机自带的 TTS 引擎支持中文。如果系统自带是华为/小米的国产引擎,通常已经自带中文; 否则点下方'前往系统 TTS 设置'下载语音数据。" to MaterialTheme.colorScheme.outline
        "xunfei" -> "讯飞「在线流式 TTS」。需到 [讯飞开放平台](https://www.xfyun.cn/) 注册账号并创建应用,在下方「讯飞应用凭据」填一次即可,普通版/超拟人两个引擎共用。中国大陆直连稳定,无需 VPN。" to MaterialTheme.colorScheme.outline
        "xunfei_super" -> "讯飞「超拟人语音合成」,音质比普通版好一档,自带评书/电台/动漫等高表演力发音人。沿用下方共享的「讯飞应用凭据」,再单独填一个超拟人专属的 Resource ID 即可。" to MaterialTheme.colorScheme.outline
        "matcha_zh_baker" -> "开源神经 TTS (sherpa-onnx + matcha-icefall-zh-baker)。完全离线、零字符费、无装机量限制,音质接近商用; 首次使用需下载 ~105MB 模型 (建议 WiFi)。下载后即便断网也能用。" to MaterialTheme.colorScheme.outline
        else -> null to MaterialTheme.colorScheme.outline
    }
    if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color
        )
    }
}

@Composable
private fun TestCurrentVoiceCard(
    testState: TtsTestState,
    onTest: () -> Unit,
    onStop: () -> Unit,
    onClearError: () -> Unit
) {
    val busy = testState is TtsTestState.Synthesizing || testState is TtsTestState.Playing
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("试听当前音色", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "播放一句样例文本,直接验证当前引擎+音色是否可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (busy) {
                    OutlinedButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("停止")
                    }
                } else {
                    Button(onClick = onTest) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("试听")
                    }
                }
            }
            TestStatusLine(testState, onClearError)
        }
    }
}

@Composable
private fun TestStatusLine(testState: TtsTestState, onClearError: () -> Unit) {
    when (testState) {
        is TtsTestState.Idle -> {}
        is TtsTestState.Synthesizing -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在合成 ${testState.voiceId}…", style = MaterialTheme.typography.bodySmall)
            }
        }
        is TtsTestState.Playing -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在播放 ${testState.voiceId}…", style = MaterialTheme.typography.bodySmall)
            }
        }
        is TtsTestState.Success -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "试听成功! ${testState.voiceId} 可用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        is TtsTestState.Error -> {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        "试听失败",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        testState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClearError) { Text("收起") }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(
    voice: TtsVoice,
    selected: Boolean,
    testState: TtsTestState,
    onSelect: () -> Unit,
    onTest: () -> Unit
) {
    val isThisTesting = when (testState) {
        is TtsTestState.Synthesizing -> testState.voiceId == voice.id
        is TtsTestState.Playing -> testState.voiceId == voice.id
        else -> false
    }
    Surface(
        onClick = onSelect,
        tonalElevation = if (selected) 4.dp else 0.dp,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(voice.displayName, style = MaterialTheme.typography.bodyLarge)
                val tags = listOfNotNull(voice.locale, voice.gender, voice.style)
                if (tags.isNotEmpty()) {
                    Text(
                        tags.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            TextButton(
                onClick = onTest,
                enabled = !isThisTesting
            ) {
                if (isThisTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("试听中")
                } else {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("试听")
                }
            }
        }
    }
}

@Composable
private fun FlowRowSimple(
    items: List<com.xs.reader.tts.TtsEngine>,
    item: @Composable (com.xs.reader.tts.TtsEngine) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { e -> item(e) }
    }
}

/**
 * 讯飞「应用凭据」卡片 —— 三个讯飞引擎 (普通版 / 超拟人 / 离线 SDK) 共用同一份凭据,
 * 因此这个卡片在 active 引擎是任一讯飞引擎时都展示同一套表单 / 摘要。
 *
 * 两种显示态:
 *  - 已保存 (hasXunfeiSharedCreds && !editing): 折叠成一行掩码摘要 + 「编辑」按钮
 *  - 编辑中 (editing 或尚未保存过): 展开 3 个输入框 + 保存/取消
 */
@Composable
private fun XunfeiSharedCredCard(vm: TtsSettingsViewModel) {
    // 通过这个 trigger 强制重新读取 keyStore (saveKeys 后递增, 让 derivedSavedXxx 重算)
    var reloadTrigger by remember { mutableStateOf(0) }
    val savedAppId = remember(reloadTrigger) { vm.getXunfeiSharedKey("app_id") ?: "" }
    val savedApiKey = remember(reloadTrigger) { vm.getXunfeiSharedKey("api_key") ?: "" }
    val savedApiSecret = remember(reloadTrigger) { vm.getXunfeiSharedKey("api_secret") ?: "" }
    val hasCreds = savedAppId.isNotBlank() && savedApiKey.isNotBlank() && savedApiSecret.isNotBlank()

    // 首次进入: 有凭据 → 折叠; 没凭据 → 直接展开输入态
    var editing by remember { mutableStateOf(!hasCreds) }

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("讯飞应用凭据", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "普通版 / 超拟人 两个引擎共用同一份凭据; 在 [讯飞开放平台](https://www.xfyun.cn/) 控制台同一应用的详情页一次复制即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            if (!editing && hasCreds) {
                XunfeiCredSummary(
                    appId = savedAppId,
                    apiKey = savedApiKey,
                    apiSecret = savedApiSecret,
                    onEdit = { editing = true }
                )
            } else {
                XunfeiCredEditor(
                    initialAppId = savedAppId,
                    initialApiKey = savedApiKey,
                    initialApiSecret = savedApiSecret,
                    canCancel = hasCreds,
                    onSave = { appId, apiKey, apiSecret ->
                        vm.saveXunfeiSharedKeys(appId, apiKey, apiSecret)
                        reloadTrigger++
                        editing = false
                    },
                    onCancel = { editing = false }
                )
            }
        }
    }
}

/** 折叠态: 一行掩码摘要 + 编辑按钮。 */
@Composable
private fun XunfeiCredSummary(
    appId: String,
    apiKey: String,
    apiSecret: String,
    onEdit: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "AppID  ${maskMiddle(appId)}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "APIKey  ${maskMiddle(apiKey)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "APISecret  ${maskMiddle(apiSecret)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("编辑")
        }
    }
}

/** 编辑态: 三个输入框 + 保存按钮 (可选取消)。 */
@Composable
private fun XunfeiCredEditor(
    initialAppId: String,
    initialApiKey: String,
    initialApiSecret: String,
    canCancel: Boolean,
    onSave: (appId: String, apiKey: String, apiSecret: String) -> Unit,
    onCancel: () -> Unit
) {
    var appId by remember { mutableStateOf(initialAppId) }
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var apiSecret by remember { mutableStateOf(initialApiSecret) }
    var savedSnapshot by remember {
        mutableStateOf(Triple(initialAppId, initialApiKey, initialApiSecret))
    }
    val dirty = Triple(appId, apiKey, apiSecret) != savedSnapshot

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "APISecret 是一长串十六进制,务必整段复制不要漏字符。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it.trim() },
            label = { Text("AppID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it.trim() },
            label = { Text("APIKey") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiSecret,
            onValueChange = { apiSecret = it.trim() },
            label = { Text("APISecret") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        KeyFormSaveBar(
            dirty = dirty,
            canCancel = canCancel,
            onSave = {
                savedSnapshot = Triple(appId, apiKey, apiSecret)
                onSave(appId, apiKey, apiSecret)
            },
            onCancel = onCancel
        )
    }
}

/**
 * 讯飞超拟人独有的 Resource ID 卡片。
 * 沿用「折叠 / 编辑」的同款交互, 让两张卡片在视觉上一致。
 */
@Composable
private fun XunfeiSuperResourceIdCard(vm: TtsSettingsViewModel) {
    var reloadTrigger by remember { mutableStateOf(0) }
    val savedResourceId = remember(reloadTrigger) { vm.getStoredKey("resource_id") ?: "" }
    val has = savedResourceId.isNotBlank()
    var editing by remember { mutableStateOf(!has) }

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("超拟人 Resource ID", style = MaterialTheme.typography.titleSmall)
            Text(
                "控制台「超拟人合成 API」一栏 URL 末段,形如 mcd9m97e6。这是超拟人独有的额外授权字段。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )

            if (!editing && has) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Resource ID  ${maskMiddle(savedResourceId)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { editing = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("编辑")
                    }
                }
            } else {
                var value by remember { mutableStateOf(savedResourceId) }
                var snapshot by remember { mutableStateOf(savedResourceId) }
                val dirty = value != snapshot
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    label = { Text("Resource ID") },
                    placeholder = { Text("如 mcd9m97e6") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                KeyFormSaveBar(
                    dirty = dirty,
                    canCancel = has,
                    onSave = {
                        // 沿用 saveKeys (按 active engineId 命名空间),
                        // 此时 active 一定是 xunfei_super, 与现有读取路径一致。
                        vm.saveKeys(mapOf("resource_id" to value))
                        snapshot = value
                        reloadTrigger++
                        editing = false
                    },
                    onCancel = { editing = false }
                )
            }
        }
    }
}

/**
 * 把一个凭据字符串脱敏成 "前 4 位 + 星号 + 后 4 位" 形式;
 * 太短时显示全星号, 避免泄露片段。
 *  - "abcdef1234567890" → "abcd********7890"
 *  - "abc"              → "***"
 */
private fun maskMiddle(value: String): String {
    val v = value.trim()
    if (v.length <= 8) return "*".repeat(v.length.coerceAtLeast(3))
    val head = v.take(4)
    val tail = v.takeLast(4)
    val starCount = (v.length - 8).coerceAtMost(12)
    return "$head${"*".repeat(starCount)}$tail"
}

/**
 * matcha 离线神经 TTS 模型管理卡片。
 *
 * 四种状态对应不同 UI:
 *  - Missing : 显示「下载离线模型 (~90MB)」按钮 + 来源说明
 *  - Downloading : 显示线性进度条 + 当前正在下载的文件名 + 百分比
 *  - Ready : 显示 ✓ 已就绪 + 「删除模型」按钮
 *  - Failed : 显示错误信息 + 「重试」按钮
 *
 * 注意: 下载跑在 ViewModel 协程上, 退出设置页后仍会继续 (Singleton 持有状态),
 * 用户回到设置页能看到当前进度, 不会"白下"。
 */
@Composable
private fun MatchaModelCard(vm: TtsSettingsViewModel) {
    val state by vm.matchaState.collectAsState()
    val progress by vm.matchaProgress.collectAsState()
    val currentFile by vm.matchaCurrentFile.collectAsState()
    val hasBundled = remember { vm.matchaHasBundledAssets() }

    // 按钮文案: 有预装资源就显示"从预装安装", 否则就是"在线下载"
    val installAction: () -> Unit = { vm.downloadMatchaModel() }
    val installLabel = if (hasBundled) "立即安装 (~100MB)" else "下载离线模型 (~100MB)"
    val retryLabel = if (hasBundled) "重新安装" else "重试下载"
    val descLines = if (hasBundled) {
        "APK 已预装完整模型文件,首次启用只是把它们解压到 App 目录,耗时几秒,无需联网。"
    } else {
        "APK 内未预装模型,需联网下载。下载源走 HuggingFace 国内镜像 (hf-mirror.com),国内一般 1-3 分钟。"
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("离线神经 TTS 模型", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "matcha-icefall-zh-baker (标贝中文女声),开源、完全离线、无配额限制。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            when (state) {
                MatchaModelManager.State.Missing -> {
                    Button(
                        onClick = installAction,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(installLabel)
                    }
                    Text(
                        descLines,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                MatchaModelManager.State.Installing,
                MatchaModelManager.State.Downloading -> {
                    val stageText = if (state == MatchaModelManager.State.Installing) {
                        "正在安装预装模型 ${(progress * 100).toInt()}%"
                    } else {
                        "正在下载模型 ${(progress * 100).toInt()}%"
                    }
                    Text(
                        stageText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (currentFile.isNotBlank()) {
                        Text(
                            "当前文件: $currentFile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Text(
                        "可以离开此页面继续阅读,任务在后台进行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                MatchaModelManager.State.Ready -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "✓ 模型已就绪,可离线使用",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = { vm.deleteMatchaModel() }) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("删除")
                        }
                    }
                }
                MatchaModelManager.State.Failed -> {
                    val err = vm.matchaErrorMessage ?: "未知错误"
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(
                        onClick = installAction,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(retryLabel)
                    }
                }
            }
        }
    }
}

/**
 * 用户自定义讯飞发音人编辑区。
 * 列出当前 engineId 下已添加的预设, 提供「+ 添加发音人」按钮唤出对话框。
 * 删除操作直接走 ViewModel 落库 + 刷新音色列表。
 */
@Composable
private fun XunfeiVoicePresetSection(
    vm: TtsSettingsViewModel,
    engineId: String
) {
    val all by vm.xunfeiVoicePresets.collectAsState()
    val list = all.filter { it.engineId == engineId }
    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<XunfeiVoicePreset?>(null) }

    val sectionTitle = if (engineId == "xunfei_super") "我的超拟人发音人" else "我的发音人"
    val builtinCount = XunfeiVoicePreset.builtinFor(engineId).size

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sectionTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                editing = null
                showDialog = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("添加发音人")
            }
        }
        if (builtinCount > 0) {
            Text(
                "已内置控制台「发音人授权管理」默认免费开通的 $builtinCount 个基础音色," +
                    "见上方「可用音色」。如需使用控制台里你账号特有的其它 vcn (如评书 / 动漫 / 自训发音人)," +
                    "在这里追加即可,同 vcn 的自定义会覆盖内置显示名。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (list.isEmpty()) {
            Text(
                "示例: 控制台「我的发音人」里看到「讯飞小燕 / vcn=x4_xiaoyan」, " +
                    "就点「添加发音人」, 显示名填「讯飞小燕」, vcn 填「x4_xiaoyan」, 保存即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            list.forEach { p ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 1.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "vcn = ${p.vcn}" +
                                    (p.gender?.let { "  ·  $it" } ?: "") +
                                    (p.style?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        TextButton(onClick = {
                            editing = p
                            showDialog = true
                        }) { Text("编辑") }
                        IconButton(onClick = { vm.removeVoicePreset(p.engineId, p.vcn) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        XunfeiVoicePresetDialog(
            initial = editing,
            engineId = engineId,
            onDismiss = { showDialog = false },
            onSave = { preset ->
                vm.upsertVoicePreset(preset)
                showDialog = false
            }
        )
    }
}

/** 添加 / 编辑对话框。仅 vcn + 显示名是必填; 性别 / 备注可选。 */
@Composable
private fun XunfeiVoicePresetDialog(
    initial: XunfeiVoicePreset?,
    engineId: String,
    onDismiss: () -> Unit,
    onSave: (XunfeiVoicePreset) -> Unit
) {
    var vcn by remember { mutableStateOf(initial?.vcn ?: "") }
    var displayName by remember { mutableStateOf(initial?.displayName ?: "") }
    var gender by remember { mutableStateOf(initial?.gender ?: "") }
    var style by remember { mutableStateOf(initial?.style ?: "") }
    val canSave = vcn.isNotBlank() && displayName.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加发音人" else "编辑发音人") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vcn,
                    onValueChange = { vcn = it.trim() },
                    label = { Text("vcn 参数 *") },
                    placeholder = { Text("如 x4_xiaoyan / aisjiuxu") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("显示名 *") },
                    placeholder = { Text("如 讯飞小燕") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = gender,
                    onValueChange = { gender = it },
                    label = { Text("性别 (可选)") },
                    placeholder = { Text("Female / Male / Neutral") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (engineId == "xunfei_super") {
                    OutlinedTextField(
                        value = style,
                        onValueChange = { style = it },
                        label = { Text("风格 (可选)") },
                        placeholder = { Text("填 pingshu 自动套评书参数; 其它如 narrative / anime") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    "* vcn 必须与讯飞控制台「能力管理 → 我的发音人」中的参数完全一致, " +
                        "大小写敏感。如果填错或该 vcn 未在你账号下开通, 朗读会报 11200 LiccCheck。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        XunfeiVoicePreset(
                            engineId = engineId,
                            vcn = vcn.trim(),
                            displayName = displayName.trim(),
                            gender = gender.takeIf { it.isNotBlank() },
                            style = style.takeIf { it.isNotBlank() }
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun PingshuPresetCard(onApply: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("评书一键预设", style = MaterialTheme.typography.titleSmall)
                Text(
                    "把当前 voice 切到 vcn=x6_ruyadashu_pro (儒雅大叔), 语速 0.85x, 音调 0.9, 引擎自动开启韵律 (rhy=1) 与低口语化 (oral_level=low)。" +
                        "如果你账号里没开通这个 vcn, 朗读会报 11200; 请先在「我的超拟人发音人」里加一条 vcn=x6_ruyadashu_pro 的预设。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onApply) {
                Text("应用")
            }
        }
    }
}

@Composable
private fun OpenAiKeyForm(vm: TtsSettingsViewModel) {
    val savedBaseUrl = vm.getStoredKey("base_url") ?: "https://api.openai.com"
    val savedApiKey = vm.getStoredKey("api_key") ?: ""
    val savedModel = vm.getStoredKey("model") ?: "tts-1"
    val savedFormat = vm.getStoredKey("format") ?: "mp3"
    var baseUrl by remember { mutableStateOf(savedBaseUrl) }
    var apiKey by remember { mutableStateOf(savedApiKey) }
    var model by remember { mutableStateOf(savedModel) }
    var format by remember { mutableStateOf(savedFormat) }
    var savedSnapshot by remember {
        mutableStateOf(listOf(savedBaseUrl, savedApiKey, savedModel, savedFormat))
    }
    val dirty = listOf(baseUrl, apiKey, model, format) != savedSnapshot

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("OpenAI 兼容服务", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model 名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = format,
            onValueChange = { format = it },
            label = { Text("Response Format (mp3/wav/opus)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        KeyFormSaveBar(
            dirty = dirty,
            onSave = {
                vm.saveKeys(
                    mapOf(
                        "base_url" to baseUrl,
                        "api_key" to apiKey,
                        "model" to model,
                        "format" to format
                    )
                )
                savedSnapshot = listOf(baseUrl, apiKey, model, format)
            }
        )
    }
}

/**
 * 凭据表单底部统一的"保存"操作栏。
 *  - dirty=false 时按钮置灰,左侧显示"已保存"
 *  - dirty=true 时按钮高亮,左侧显示"有未保存的修改"
 *  - 点击保存后,2 秒内左侧短暂显示"已保存 ✓"
 *
 * 当 [canCancel] 为 true 时,左侧额外展示一个"取消"按钮 —— 用于"已配置后再编辑"场景,
 * 让用户可以放弃本次修改回到折叠态。
 */
@Composable
private fun KeyFormSaveBar(
    dirty: Boolean,
    onSave: () -> Unit,
    canCancel: Boolean = false,
    onCancel: () -> Unit = {}
) {
    var justSaved by remember { mutableStateOf(false) }
    LaunchedEffect(justSaved) {
        if (justSaved) {
            delay(2000)
            justSaved = false
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            justSaved -> {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "已保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            dirty -> {
                Text(
                    "有未保存的修改",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            else -> {
                Text(
                    "已保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (canCancel) {
            TextButton(onClick = onCancel) { Text("取消") }
            Spacer(Modifier.width(4.dp))
        }
        Button(
            onClick = {
                onSave()
                justSaved = true
            },
            enabled = dirty
        ) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("保存")
        }
    }
}
