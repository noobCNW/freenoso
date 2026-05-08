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
import com.xs.reader.tts.TtsVoice
import com.xs.reader.tts.XunfeiSuperTtsEngine
import com.xs.reader.tts.XunfeiVoicePreset
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
            when (state.activeEngineId) {
                "xunfei" -> XunfeiKeyForm(vm = vm)
                "xunfei_super" -> XunfeiSuperKeyForm(vm = vm)
                "xunfei_offline" -> XunfeiOfflineKeyForm(vm = vm)
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
                        "还没有添加发音人。请到讯飞控制台「能力管理 → 我的发音人」复制 vcn 参数 (例如 x4_xiaoyan / aisjiuxu),用上方「+ 添加发音人」加进来。"
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
        "xunfei" -> "讯飞「在线流式 TTS」。需要自行到 [讯飞开放平台](https://www.xfyun.cn/) 注册账号并创建应用,把 AppID/APIKey/APISecret 填到下方。中国大陆直连稳定,无需 VPN。" to MaterialTheme.colorScheme.outline
        "xunfei_super" -> "讯飞「超拟人语音合成」,音质比普通版好一档,自带评书/电台/动漫等高表演力发音人。需在控制台开通,并填写 AppID/APIKey/APISecret + 专属 Resource ID。" to MaterialTheme.colorScheme.outline
        "xunfei_offline" -> "讯飞「离线语音合成 (高品质版)」,首次使用需联网激活一次,激活后完全离线、零网络/零字符费用,但每台设备占一个授权位 (装机量)。仅 2 个发音人: 晓燕/晓峰。" to MaterialTheme.colorScheme.outline
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

@Composable
private fun XunfeiKeyForm(vm: TtsSettingsViewModel) {
    val savedAppId = vm.getStoredKey("app_id") ?: ""
    val savedApiKey = vm.getStoredKey("api_key") ?: ""
    val savedApiSecret = vm.getStoredKey("api_secret") ?: ""
    var appId by remember { mutableStateOf(savedAppId) }
    var apiKey by remember { mutableStateOf(savedApiKey) }
    var apiSecret by remember { mutableStateOf(savedApiSecret) }
    var savedSnapshot by remember {
        mutableStateOf(Triple(savedAppId, savedApiKey, savedApiSecret))
    }
    val dirty = Triple(appId, apiKey, apiSecret) != savedSnapshot

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("讯飞普通版凭据", style = MaterialTheme.typography.titleSmall)
        Text(
            "三个值都需要填,分别从 [讯飞开放平台](https://www.xfyun.cn/) 控制台 → 应用详情页复制。注意 APISecret 是一长串十六进制,务必整段复制不要漏字符。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it },
            label = { Text("AppID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("APIKey") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiSecret,
            onValueChange = { apiSecret = it },
            label = { Text("APISecret") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        KeyFormSaveBar(
            dirty = dirty,
            onSave = {
                vm.saveKeys(
                    mapOf(
                        "app_id" to appId,
                        "api_key" to apiKey,
                        "api_secret" to apiSecret
                    )
                )
                savedSnapshot = Triple(appId, apiKey, apiSecret)
            }
        )
    }
}

@Composable
private fun XunfeiSuperKeyForm(vm: TtsSettingsViewModel) {
    val savedAppId = vm.getStoredKey("app_id") ?: ""
    val savedApiKey = vm.getStoredKey("api_key") ?: ""
    val savedApiSecret = vm.getStoredKey("api_secret") ?: ""
    val savedResourceId = vm.getStoredKey("resource_id") ?: ""
    var appId by remember { mutableStateOf(savedAppId) }
    var apiKey by remember { mutableStateOf(savedApiKey) }
    var apiSecret by remember { mutableStateOf(savedApiSecret) }
    var resourceId by remember { mutableStateOf(savedResourceId) }
    var savedSnapshot by remember {
        mutableStateOf(listOf(savedAppId, savedApiKey, savedApiSecret, savedResourceId))
    }
    val dirty = listOf(appId, apiKey, apiSecret, resourceId) != savedSnapshot

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("讯飞超拟人凭据", style = MaterialTheme.typography.titleSmall)
        Text(
            "凭据与「讯飞 (普通版)」分开保存。Resource ID 见控制台「超拟人合成 API」一栏 (URL 末段, 形如 mcd9m97e6)。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it },
            label = { Text("AppID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("APIKey") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiSecret,
            onValueChange = { apiSecret = it },
            label = { Text("APISecret") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = resourceId,
            onValueChange = { resourceId = it },
            label = { Text("Resource ID") },
            placeholder = { Text("如 mcd9m97e6") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        KeyFormSaveBar(
            dirty = dirty,
            onSave = {
                vm.saveKeys(
                    mapOf(
                        "app_id" to appId,
                        "api_key" to apiKey,
                        "api_secret" to apiSecret,
                        "resource_id" to resourceId
                    )
                )
                savedSnapshot = listOf(appId, apiKey, apiSecret, resourceId)
            }
        )
    }
}

@Composable
private fun XunfeiOfflineKeyForm(vm: TtsSettingsViewModel) {
    val savedAppId = vm.getStoredKey("app_id") ?: ""
    val savedApiKey = vm.getStoredKey("api_key") ?: ""
    val savedApiSecret = vm.getStoredKey("api_secret") ?: ""
    var appId by remember { mutableStateOf(savedAppId) }
    var apiKey by remember { mutableStateOf(savedApiKey) }
    var apiSecret by remember { mutableStateOf(savedApiSecret) }
    var savedSnapshot by remember {
        mutableStateOf(Triple(savedAppId, savedApiKey, savedApiSecret))
    }
    val dirty = Triple(appId, apiKey, apiSecret) != savedSnapshot
    val authState by vm.offlineAuthState.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("讯飞离线 (高品质) 凭据", style = MaterialTheme.typography.titleSmall)
        Text(
            "首次使用需联网激活,激活会消耗 1 个装机额度 (控制台「离线语音合成(高品质版)」可见剩余装机量)。激活后完全离线、不再产生网络/字符费用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it },
            label = { Text("AppID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("APIKey") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiSecret,
            onValueChange = { apiSecret = it },
            label = { Text("APISecret") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        KeyFormSaveBar(
            dirty = dirty,
            onSave = {
                vm.saveKeys(
                    mapOf(
                        "app_id" to appId,
                        "api_key" to apiKey,
                        "api_secret" to apiSecret
                    )
                )
                savedSnapshot = Triple(appId, apiKey, apiSecret)
            }
        )
        XunfeiOfflineAuthCard(
            state = authState,
            onActivate = { vm.activateXunfeiOffline() }
        )
    }
}

@Composable
private fun XunfeiOfflineAuthCard(
    state: XunfeiOfflineAuthUiState,
    onActivate: () -> Unit
) {
    val (badgeText, badgeColor) = when (state) {
        is XunfeiOfflineAuthUiState.Idle -> "未激活" to MaterialTheme.colorScheme.outline
        is XunfeiOfflineAuthUiState.Activating -> "正在激活…" to MaterialTheme.colorScheme.tertiary
        is XunfeiOfflineAuthUiState.Ready -> "已激活 ✓" to MaterialTheme.colorScheme.primary
        is XunfeiOfflineAuthUiState.Failed -> "激活失败" to MaterialTheme.colorScheme.error
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("离线 SDK 鉴权", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "状态: $badgeText",
                        style = MaterialTheme.typography.bodySmall,
                        color = badgeColor
                    )
                }
                val activating = state is XunfeiOfflineAuthUiState.Activating
                Button(
                    onClick = onActivate,
                    enabled = !activating
                ) {
                    if (activating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("激活中")
                    } else {
                        Text("立即激活")
                    }
                }
            }
            if (state is XunfeiOfflineAuthUiState.Failed) {
                Text(
                    state.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
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
 */
@Composable
private fun KeyFormSaveBar(
    dirty: Boolean,
    onSave: () -> Unit
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
