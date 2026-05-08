package com.xs.reader.ui.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xs.reader.ui.theme.ReaderThemes

@Composable
fun ReaderSettingsSheet(
    onDismiss: () -> Unit,
    vm: ReaderSettingsViewModel = hiltViewModel()
) {
    val prefs by vm.prefs.collectAsState()
    val context = LocalContext.current

    val pickFont = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importFontFromUri(context, uri)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("阅读设置", style = MaterialTheme.typography.titleMedium)

            Text("阅读主题", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReaderThemes.all.forEach { t ->
                    FilterChip(
                        selected = prefs.themeId == t.id,
                        onClick = { vm.setThemeId(t.id) },
                        label = { Text(t.name, maxLines = 1) }
                    )
                }
            }

            Text("字号  ${prefs.fontSizeSp.toInt()} sp", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = prefs.fontSizeSp,
                onValueChange = { vm.setFontSize(it) },
                valueRange = 12f..32f,
                steps = 19
            )

            Text("行高  ${"%.1f".format(prefs.lineHeightMultiplier)}", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = prefs.lineHeightMultiplier,
                onValueChange = { vm.setLineHeight(it) },
                valueRange = 1.0f..2.5f,
                steps = 14
            )

            Text("页边距  ${prefs.pageMarginDp.toInt()} dp", style = MaterialTheme.typography.titleSmall)
            Slider(
                value = prefs.pageMarginDp,
                onValueChange = { vm.setPageMargin(it) },
                valueRange = 8f..40f,
                steps = 32
            )

            Text("翻页方式", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("horizontal" to "横向", "scroll" to "上下").forEach { (id, label) ->
                    FilterChip(
                        selected = prefs.turnMode == id,
                        onClick = { vm.setTurnMode(id) },
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }

            Text("字体", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "default" to "系统默认",
                    "serif" to "宋体",
                    "sans" to "无衬线",
                    "mono" to "等宽"
                ).forEach { (id, label) ->
                    FilterChip(
                        selected = prefs.fontFamilyId == id && prefs.customFontPath.isNullOrBlank(),
                        onClick = {
                            vm.setFontFamily(id)
                            vm.setCustomFontPath(null)
                        },
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { pickFont.launch(arrayOf("font/ttf", "font/otf", "*/*")) }
                ) { Text("导入 .ttf / .otf") }
                Spacer(Modifier.width(12.dp))
                if (!prefs.customFontPath.isNullOrBlank()) {
                    AssistChip(
                        onClick = { vm.setCustomFontPath(null) },
                        label = { Text("自定义字体已启用，点击取消") }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("常亮", Modifier.weight(1f))
                Switch(
                    checked = prefs.keepScreenOn,
                    onCheckedChange = { vm.setKeepScreenOn(it) }
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("章节首页显示标题")
                    Text(
                        "关闭后正文上方不再有章节标题大字",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = prefs.showChapterTitle,
                    onCheckedChange = { vm.setShowChapterTitle(it) }
                )
            }
        }
    }
}
