package com.xs.reader.ui.reader.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xs.reader.ui.reader.ReaderState
import com.xs.reader.ui.reader.ReaderViewModel
import com.xs.reader.ui.theme.ReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PdfReader(
    state: ReaderState,
    theme: ReaderTheme,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onAddBookmark: () -> Unit,
    vm: ReaderViewModel
) {
    val book = state.book ?: return
    val pageCount = state.chapters.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = state.currentChapterIndex.coerceIn(0, pageCount - 1),
        pageCount = { pageCount }
    )
    var showOverlay by remember { mutableStateOf(true) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3500)
        showOverlay = false
    }
    LaunchedEffect(pagerState.currentPage) {
        vm.jumpToChapter(pagerState.currentPage)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(theme.bgColor)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { showOverlay = !showOverlay })
            }
            .onSizeChanged { canvasSize = it }
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageIndex ->
            PdfPage(
                file = File(book.sourcePath),
                pageIndex = pageIndex,
                width = canvasSize.width,
                height = canvasSize.height
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp)
        ) {
            Text(
                "${pagerState.currentPage + 1} / $pageCount",
                color = theme.secondaryColor,
                fontSize = 12.sp
            )
        }

        AnimatedVisibility(
            visible = showOverlay,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut()
        ) {
            TopAppBar(
                title = { Text(book.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onAddBookmark) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = "添加书签")
                    }
                }
            )
        }
        AnimatedVisibility(
            visible = showOverlay,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut()
        ) {
            BottomAppBar(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                TextButton(
                    onClick = onOpenToc,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Text(
                        text = "目录",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPage(file: File, pageIndex: Int, width: Int, height: Int) {
    var bitmap by remember(file.absolutePath, pageIndex, width, height) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(file.absolutePath, pageIndex, width, height) {
        if (width <= 0 || height <= 0) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) { renderPage(file, pageIndex, width, height) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } ?: CircularProgressIndicator()
    }
}

private fun renderPage(file: File, pageIndex: Int, viewportW: Int, viewportH: Int): Bitmap? {
    return runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (pageIndex !in 0 until renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    val ratio = page.width.toFloat() / page.height
                    val viewportRatio = viewportW.toFloat() / viewportH
                    val (w, h) = if (ratio > viewportRatio) {
                        viewportW to (viewportW / ratio).toInt()
                    } else {
                        (viewportH * ratio).toInt() to viewportH
                    }
                    val bmp = Bitmap.createBitmap(
                        w.coerceAtLeast(1),
                        h.coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }.getOrNull()
}
