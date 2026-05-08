package com.xs.reader.ui.bookmark

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun BookmarksScreen(
    onOpenBookmark: (bookId: Long, chapterIndex: Int, charOffset: Int) -> Unit,
    vm: BookmarksViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("书签") }) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.items.isEmpty()) {
                Text(
                    "还没有书签，在阅读时点击书签按钮添加",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.outline
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.items, key = { it.id }) { bm ->
                        val book = state.books[bm.bookId]
                        Card(
                            onClick = { onOpenBookmark(bm.bookId, bm.chapterIndex, bm.charOffset) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(bm.color)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Bookmark,
                                        contentDescription = null,
                                        tint = Color.White
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = book?.title ?: "未知书籍",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "第 ${bm.chapterIndex + 1} 章 · ${bm.snippet}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (!bm.note.isNullOrBlank()) {
                                        Text(
                                            "备注：${bm.note}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                IconButton(onClick = { vm.delete(bm) }) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "删除"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
