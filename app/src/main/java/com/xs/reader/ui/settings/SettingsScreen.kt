package com.xs.reader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xs.reader.ui.theme.ReaderThemes

@Composable
fun SettingsScreen(
    onOpenTtsSettings: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel()
) {
    val prefs by vm.prefs.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SectionHeader("外观")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("跟随系统暗色模式", Modifier.weight(1f))
                Switch(
                    checked = prefs.followSystemDark,
                    onCheckedChange = { vm.setFollowSystemDark(it) }
                )
            }
            if (!prefs.followSystemDark) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("启用暗色模式", Modifier.weight(1f))
                    Switch(
                        checked = prefs.appDarkMode,
                        onCheckedChange = { vm.setAppDarkMode(it) }
                    )
                }
            }

            SectionHeader("阅读主题")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReaderThemes.all.forEach { theme ->
                    FilterChip(
                        selected = prefs.themeId == theme.id,
                        onClick = { vm.setThemeId(theme.id) },
                        label = { Text(theme.name, maxLines = 1) }
                    )
                }
            }

            SectionHeader("字号")
            Slider(
                value = prefs.fontSizeSp,
                onValueChange = { vm.setFontSize(it) },
                valueRange = 12f..32f,
                steps = 19
            )
            Text("当前 ${prefs.fontSizeSp.toInt()} sp")

            SectionHeader("行高倍数")
            Slider(
                value = prefs.lineHeightMultiplier,
                onValueChange = { vm.setLineHeight(it) },
                valueRange = 1.0f..2.5f,
                steps = 14
            )
            Text("当前 ${"%.1f".format(prefs.lineHeightMultiplier)}")

            SectionHeader("页边距 (dp)")
            Slider(
                value = prefs.pageMarginDp,
                onValueChange = { vm.setPageMargin(it) },
                valueRange = 8f..40f,
                steps = 32
            )
            Text("当前 ${prefs.pageMarginDp.toInt()} dp")

            SectionHeader("翻页方式")
            val turnModes = listOf("horizontal" to "横向翻页", "scroll" to "上下滚动", "none" to "无动画")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                turnModes.forEach { (id, label) ->
                    FilterChip(
                        selected = prefs.turnMode == id,
                        onClick = { vm.setTurnMode(id) },
                        label = { Text(label, maxLines = 1) }
                    )
                }
            }

            SectionHeader("保持屏幕常亮")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("阅读时保持亮屏", Modifier.weight(1f))
                Switch(
                    checked = prefs.keepScreenOn,
                    onCheckedChange = { vm.setKeepScreenOn(it) }
                )
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("朗读引擎与音色") },
                supportingContent = { Text("配置 系统/讯飞 TTS") },
                modifier = Modifier.clickable { onOpenTtsSettings() }
            )
            Text(
                "字体管理、自定义字体导入在阅读器内的设置面板中提供。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}
