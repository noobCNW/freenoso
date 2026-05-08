package com.xs.reader.ui.shelf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xs.reader.data.db.BookEntity
import com.xs.reader.data.db.BookFormat

@Composable
fun ShelfScreen(
    onOpenBook: (Long) -> Unit,
    vm: ShelfViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importBook(context, uri)
    }

    var pendingDelete by remember { mutableStateOf<BookEntity?>(null) }
    var pendingActions by remember { mutableStateOf<BookEntity?>(null) }
    var pendingRecut by remember { mutableStateOf<BookEntity?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("书架") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                pickFile.launch(arrayOf(
                    "text/plain",
                    "application/pdf",
                    "application/epub+zip",
                    "application/octet-stream",
                    "*/*"
                ))
            }) {
                Icon(Icons.Default.Add, contentDescription = "导入书籍")
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.importing -> {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在导入：${state.importingMessage}")
                    }
                }
                state.books.isEmpty() -> {
                    Text(
                        "书架空空如也，点击右下角 + 导入第一本书",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        items(state.books, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                onClick = { onOpenBook(book.id) },
                                onLongClick = { pendingActions = book }
                            )
                        }
                    }
                }
            }

            state.error?.let { err ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    action = { TextButton(onClick = { vm.clearError() }) { Text("关闭") } }
                ) { Text(err) }
            }
        }
    }

    pendingActions?.let { b ->
        AlertDialog(
            onDismissRequest = { pendingActions = null },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pendingActions = null }) { Text("取消") }
            },
            title = { Text(b.title, maxLines = 2) },
            text = {
                Column {
                    Text(
                        "请选择要执行的操作",
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    if (b.format == BookFormat.TXT) {
                        TextButton(
                            onClick = {
                                pendingRecut = b
                                pendingActions = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("重新切分章节", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    TextButton(
                        onClick = {
                            pendingDelete = b
                            pendingActions = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("删除", modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        )
    }

    pendingRecut?.let { b ->
        AlertDialog(
            onDismissRequest = { pendingRecut = null },
            confirmButton = {
                TextButton(onClick = {
                    vm.recutChapters(b)
                    pendingRecut = null
                }) { Text("重新切分") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRecut = null }) { Text("取消") }
            },
            title = { Text("重新切分章节") },
            text = {
                Text("将用最新的章节识别规则重新解析《${b.title}》。\n\n• 阅读进度会重置到第一章\n• 书签可能会失效\n• 适合章节数明显异常的书")
            }
        )
    }

    pendingDelete?.let { b ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteBook(b)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
            title = { Text("删除") },
            text = { Text("确定从书架中删除《${b.title}》？数据无法恢复。") }
        )
    }
}

@Composable
private fun BookCard(
    book: BookEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val formatColor = when (book.format) {
        BookFormat.TXT -> Color(0xFF6B8E23)
        BookFormat.EPUB -> Color(0xFF1E90FF)
        BookFormat.PDF -> Color(0xFFCD5C5C)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = book.title.take(2),
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = book.format.name,
                fontSize = 10.sp,
                color = formatColor,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
            if (book.readingProgress > 0f) {
                LinearProgressIndicator(
                    progress = { book.readingProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!book.author.isNullOrBlank()) {
            Text(
                book.author,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
