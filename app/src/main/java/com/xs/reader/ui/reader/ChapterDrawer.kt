package com.xs.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xs.reader.data.db.ChapterEntity
import kotlinx.coroutines.launch

@Composable
fun ChapterDrawer(
    chapters: List<ChapterEntity>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    val filtered = remember(chapters, query) {
        if (query.isBlank()) chapters
        else chapters.filter { c ->
            c.title.contains(query, ignoreCase = true) ||
                (c.chapterIndex + 1).toString().contains(query)
        }
    }

    LaunchedEffect(currentIndex, chapters.size, query) {
        if (query.isBlank() && currentIndex in chapters.indices) {
            listState.scrollToItem(currentIndex.coerceAtLeast(0))
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "目录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "共 ${chapters.size} 章" + if (query.isNotBlank() && filtered.size != chapters.size) " · 匹配 ${filtered.size}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch { listState.scrollToItem(currentIndex.coerceAtLeast(0)) }
                }) { Text("回到当前") }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                singleLine = true,
                placeholder = { Text("搜索章节标题或编号") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "清空")
                        }
                    }
                }
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            if (filtered.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("没有匹配的章节", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    items(filtered, key = { it.id }) { chapter ->
                        val active = chapter.chapterIndex == currentIndex
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (active) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${chapter.chapterIndex + 1}.",
                                modifier = Modifier.widthIn(min = 44.dp),
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                            Text(
                                text = chapter.title.ifBlank { "(无标题)" },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp),
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2
                            )
                            TextButton(onClick = { onSelect(chapter.chapterIndex) }) {
                                Text(if (active) "当前" else "跳转")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
