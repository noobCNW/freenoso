package com.xs.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xs.reader.data.db.BookFormat
import com.xs.reader.ui.theme.ReaderThemes
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReaderContent(
    state: ReaderState,
    vm: ReaderViewModel,
    onBack: () -> Unit
) {
    val prefs by vm.prefs.collectAsState()
    val ttsState by vm.ttsState.collectAsState()
    val theme = ReaderThemes.byId(prefs.themeId)
    var showOverlay by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showAddBookmark by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.book?.id) {
        kotlinx.coroutines.delay(3500)
        showOverlay = false
    }

    LaunchedEffect(ttsState.errorMessage) {
        val msg = ttsState.errorMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            vm.clearTtsError()
        }
    }

    val view = LocalView.current
    DisposableEffect(prefs.keepScreenOn) {
        view.keepScreenOn = prefs.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    if (state.book?.format == BookFormat.PDF) {
        com.xs.reader.ui.reader.pdf.PdfReader(
            state = state,
            theme = theme,
            onBack = onBack,
            onOpenToc = { showToc = true },
            onAddBookmark = { showAddBookmark = true },
            vm = vm
        )
    } else {
        ReflowReader(
            state = state,
            prefs = prefs,
            theme = theme,
            ttsPlaying = ttsState.isPlaying,
            showOverlay = showOverlay,
            onToggleOverlay = { showOverlay = !showOverlay },
            onBack = onBack,
            onOpenToc = { showToc = true; showOverlay = false },
            onOpenSettings = { showSettings = true; showOverlay = false },
            onAddBookmark = { showAddBookmark = true; showOverlay = false },
            onToggleTts = { vm.toggleTts() },
            vm = vm
        )
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.BottomCenter)
    )

    if (showToc) {
        ChapterDrawer(
            chapters = state.chapters,
            currentIndex = state.currentChapterIndex,
            onSelect = { idx -> vm.jumpToChapter(idx); showToc = false },
            onDismiss = { showToc = false }
        )
    }

    if (showSettings) {
        ReaderSettingsSheet(onDismiss = { showSettings = false })
    }

    if (showAddBookmark) {
        AddBookmarkDialog(
            defaultSnippet = run {
                val text = state.currentChapterText
                val from = state.currentCharOffset.coerceAtMost(text.length)
                val to = (from + 60).coerceAtMost(text.length)
                if (from < to) text.substring(from, to).replace("\n", " ").trim()
                else state.currentChapter?.title ?: ""
            },
            onConfirm = { note, color ->
                vm.addBookmark(note = note, color = color)
                showAddBookmark = false
            },
            onDismiss = { showAddBookmark = false }
        )
    }
}

@Composable
private fun ReflowReader(
    state: ReaderState,
    prefs: com.xs.reader.data.prefs.ReadingPrefs,
    theme: com.xs.reader.ui.theme.ReaderTheme,
    ttsPlaying: Boolean,
    showOverlay: Boolean,
    onToggleOverlay: () -> Unit,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddBookmark: () -> Unit,
    onToggleTts: () -> Unit,
    vm: ReaderViewModel
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val fontFamily = remember(prefs.fontFamilyId, prefs.customFontPath) {
        FontProvider.resolve(prefs.fontFamilyId, prefs.customFontPath)
    }
    val textStyle = TextStyle(
        color = theme.textColor,
        fontSize = prefs.fontSizeSp.sp,
        lineHeight = (prefs.fontSizeSp * prefs.lineHeightMultiplier).sp,
        fontFamily = fontFamily
    )
    val titleStyle = TextStyle(
        color = theme.textColor,
        fontSize = (prefs.fontSizeSp * 1.4f).sp,
        lineHeight = (prefs.fontSizeSp * 1.4f * 1.4f).sp,
        fontWeight = FontWeight.Bold,
        fontFamily = fontFamily
    )
    val systemPadding = WindowInsets.systemBars.asPaddingValues()
    val marginPx = with(density) { prefs.pageMarginDp.dp.toPx().toInt() }

    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    var pages by remember { mutableStateOf<List<Page>>(emptyList()) }
    var paginating by remember { mutableStateOf(false) }
    LaunchedEffect(state.currentChapterIndex) {
        pages = emptyList()
        paginating = true
    }
    LaunchedEffect(
        state.book?.id,
        state.currentChapterIndex,
        state.currentChapterText,
        prefs.fontSizeSp,
        prefs.lineHeightMultiplier,
        prefs.fontFamilyId,
        prefs.customFontPath,
        prefs.pageMarginDp,
        contentSize
    ) {
        if (contentSize.width == 0 || contentSize.height == 0) {
            return@LaunchedEffect
        }
        if (state.currentChapterText.isEmpty()) {
            pages = emptyList()
            paginating = false
            return@LaunchedEffect
        }
        val innerWidth = contentSize.width - marginPx * 2
        val innerHeight = contentSize.height - marginPx * 2
        if (innerWidth <= 0 || innerHeight <= 0) {
            pages = emptyList()
            paginating = false
            return@LaunchedEffect
        }
        val cacheKey = buildPageCacheKey(
            bookId = state.book?.id ?: 0L,
            chapterIndex = state.currentChapterIndex,
            prefs = prefs,
            innerWidth = innerWidth,
            innerHeight = innerHeight
        )
        @Suppress("UNCHECKED_CAST")
        val cached = vm.getCachedPages(cacheKey) as? List<Page>
        if (cached != null) {
            pages = cached
            paginating = false
            return@LaunchedEffect
        }
        paginating = true
        val result = withContext(kotlinx.coroutines.Dispatchers.Default) {
            Paginator.paginate(
                title = state.currentChapter?.title.orEmpty(),
                body = state.currentChapterText,
                textMeasurer = measurer,
                style = textStyle,
                titleStyle = titleStyle,
                size = IntSize(innerWidth, innerHeight),
                paragraphSpacing = prefs.paragraphSpacingSp.toInt(),
                showTitle = prefs.showChapterTitle
            )
        }
        vm.putCachedPages(cacheKey, result)
        pages = result
        paginating = false
    }

    // 预排版前后各 1 章,跨章切换近乎无感
    LaunchedEffect(
        state.book?.id,
        state.currentChapterIndex,
        prefs.fontSizeSp,
        prefs.lineHeightMultiplier,
        prefs.fontFamilyId,
        prefs.customFontPath,
        prefs.pageMarginDp,
        prefs.showChapterTitle,
        contentSize
    ) {
        val book = state.book ?: return@LaunchedEffect
        if (contentSize.width == 0 || contentSize.height == 0) return@LaunchedEffect
        val innerWidth = contentSize.width - marginPx * 2
        val innerHeight = contentSize.height - marginPx * 2
        if (innerWidth <= 0 || innerHeight <= 0) return@LaunchedEffect

        val targets = listOf(
            state.currentChapterIndex - 1,
            state.currentChapterIndex + 1
        ).filter { it in state.chapters.indices }

        for (idx in targets) {
            val key = buildPageCacheKey(
                bookId = book.id,
                chapterIndex = idx,
                prefs = prefs,
                innerWidth = innerWidth,
                innerHeight = innerHeight
            )
            if (vm.getCachedPages(key) != null) continue
            val (chTitle, chBody) = vm.loadPrefetchChapter(idx) ?: continue
            if (chBody.isEmpty()) continue
            val res = withContext(kotlinx.coroutines.Dispatchers.Default) {
                Paginator.paginate(
                    title = chTitle,
                    body = chBody,
                    textMeasurer = measurer,
                    style = textStyle,
                    titleStyle = titleStyle,
                    size = IntSize(innerWidth, innerHeight),
                    paragraphSpacing = prefs.paragraphSpacingSp.toInt(),
                    showTitle = prefs.showChapterTitle
                )
            }
            vm.putCachedPages(key, res)
        }
    }

    // 末尾追加一个"过渡页",用户正常滑过去即触发跳转下一章。
    // 不依赖 HorizontalPager 的 NestedScroll 漏出的 overscroll delta(被自身吃掉,极不稳)。
    val hasNext = state.currentChapterIndex < state.chapters.lastIndex
    val pageCountWithTail = pages.size + (if (hasNext) 1 else 0)
    val pagerState = rememberPagerState(
        pageCount = { pageCountWithTail.coerceAtLeast(1) }
    )

    // 章节切换 / 重排版后,把 pager 定位到 currentCharOffset 对应的页
    LaunchedEffect(pages.size, state.currentChapterIndex) {
        if (pages.isNotEmpty()) {
            val target = Paginator.pageOf(pages, state.currentCharOffset)
                .coerceIn(0, pages.lastIndex)
            if (pagerState.currentPage != target) {
                pagerState.scrollToPage(target)
            }
        }
    }

    // 用户停留在过渡页(pageIndex == pages.size) → 自动跳到下一章。
    // 用 settledPage 避免滑动过程中误触发,用一次性 guard 防同章节重复触发。
    var pendingNextChapter by remember(state.currentChapterIndex) { mutableStateOf(false) }
    LaunchedEffect(pagerState.settledPage, pages.size, hasNext) {
        if (
            hasNext &&
            pages.isNotEmpty() &&
            !pendingNextChapter &&
            pagerState.settledPage >= pages.size
        ) {
            pendingNextChapter = true
            vm.nextChapter()
        }
    }

    // TTS 朗读期间跟随当前句子滚动到相应页
    LaunchedEffect(state.ttsActiveSentence, pages.size) {
        val ttsRange = state.ttsActiveSentence
        if (pages.isNotEmpty() && ttsRange != null) {
            val target = Paginator.pageOf(pages, ttsRange.first)
            if (pagerState.currentPage != target) {
                pagerState.animateScrollToPage(target)
            }
        }
    }

    // 用户滑动 / 点击翻页时,把当前页起始偏移写回 ViewModel(用于进度持久化)。
    // TTS 期间跳过,避免和 TTS 朗读位置打架。
    // 滑到末尾的过渡页(currentPage == pages.size)时不写偏移,等下一章加载完再写。
    LaunchedEffect(pagerState.currentPage, pages.size) {
        if (
            pages.isNotEmpty() &&
            state.ttsActiveSentence == null &&
            pagerState.currentPage < pages.size
        ) {
            val page = pages[pagerState.currentPage]
            vm.updateOffset(page.startOffset)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(theme.bgColor)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(systemPadding)
                .onSizeChanged { contentSize = it }
                .pointerInput(prefs.turnMode, pages.size) {
                    detectTapGestures(
                        onTap = { offset ->
                            val w = size.width
                            when {
                                offset.x < w * 0.33f && prefs.turnMode != "scroll" -> {
                                    if (pagerState.currentPage > 0) {
                                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                    } else {
                                        vm.previousChapterToEnd()
                                    }
                                }
                                offset.x > w * 0.66f && prefs.turnMode != "scroll" -> {
                                    val cur = pagerState.currentPage
                                    when {
                                        // 普通页内点右侧 → 下一页
                                        cur < pages.lastIndex ->
                                            scope.launch { pagerState.animateScrollToPage(cur + 1) }
                                        // 最后一真实页点右侧 → 下一章 (过渡页或直接跳)
                                        else -> vm.nextChapter()
                                    }
                                }
                                else -> onToggleOverlay()
                            }
                        }
                    )
                }
        ) {
            if (paginating && pages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = theme.secondaryColor)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = state.currentChapter?.title?.takeIf { it.isNotBlank() }
                                ?.let { "正在排版 · $it" }
                                ?: "正在排版…",
                            color = theme.secondaryColor,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            } else if (prefs.turnMode == "scroll") {
                ScrollReaderBody(
                    state = state,
                    style = textStyle,
                    titleStyle = titleStyle,
                    margin = prefs.pageMarginDp.dp,
                    bgColor = theme.bgColor
                )
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { pageIndex ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(prefs.pageMarginDp.dp)
                    ) {
                        val page = pages.getOrNull(pageIndex)
                        when {
                            page != null -> {
                                val highlighted = highlightTtsRange(
                                    annotated = page.annotated,
                                    pageStart = page.startOffset,
                                    ttsRange = state.ttsActiveSentence,
                                    color = theme.secondaryColor
                                )
                                Text(text = highlighted, style = textStyle)
                            }
                            // 末尾过渡页:被滑到此页时 LaunchedEffect 会触发 vm.nextChapter()
                            hasNext -> Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        "即将进入下一章",
                                        color = theme.secondaryColor,
                                        fontSize = 14.sp
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    CircularProgressIndicator(
                                        color = theme.secondaryColor,
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = prefs.pageMarginDp.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = state.currentChapter?.title.orEmpty(),
                    color = theme.secondaryColor,
                    fontSize = 11.sp,
                    maxLines = 1
                )
                Text(
                    text = run {
                        val total = pages.size
                        val pct = (state.progress * 100).toInt()
                        when {
                            prefs.turnMode == "scroll" || pages.isEmpty() -> "$pct%"
                            // 过渡页, 还没切到下一章
                            pagerState.currentPage >= pages.size -> "下一章 · $pct%"
                            else -> "${pagerState.currentPage + 1}/$total · $pct%"
                        }
                    },
                    color = theme.secondaryColor,
                    fontSize = 11.sp
                )
            }
        }

        AnimatedVisibility(
            visible = showOverlay,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut()
        ) {
            TopAppBar(
                title = { Text(state.book?.title ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) {
                ReaderBottomTextAction(
                    text = if (ttsPlaying) "停止朗读" else "语音朗读",
                    onClick = onToggleTts,
                    modifier = Modifier.weight(1f),
                    highlight = ttsPlaying
                )
                ReaderBottomTextAction(
                    text = "添加书签",
                    onClick = onAddBookmark,
                    modifier = Modifier.weight(1f)
                )
                ReaderBottomTextAction(
                    text = "目录",
                    onClick = onOpenToc,
                    modifier = Modifier.weight(1f)
                )
                ReaderBottomTextAction(
                    text = "阅读设置",
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ReaderBottomTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun ScrollReaderBody(
    state: ReaderState,
    style: TextStyle,
    titleStyle: TextStyle,
    margin: androidx.compose.ui.unit.Dp,
    bgColor: androidx.compose.ui.graphics.Color
) {
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .background(bgColor)
            .verticalScroll(scroll)
            .padding(margin)
    ) {
        Text(text = state.currentChapter?.title.orEmpty(), style = titleStyle)
        Spacer(Modifier.height(12.dp))
        Text(text = state.currentChapterText, style = style)
    }
}

private fun buildPageCacheKey(
    bookId: Long,
    chapterIndex: Int,
    prefs: com.xs.reader.data.prefs.ReadingPrefs,
    innerWidth: Int,
    innerHeight: Int
): String = buildString {
    append(bookId); append('/')
    append(chapterIndex); append('/')
    append(prefs.fontSizeSp); append('/')
    append(prefs.lineHeightMultiplier); append('/')
    append(prefs.fontFamilyId); append('/')
    append(prefs.customFontPath ?: ""); append('/')
    append(prefs.pageMarginDp); append('/')
    append(prefs.paragraphSpacingSp); append('/')
    append(prefs.showChapterTitle); append('/')
    append(innerWidth); append('x'); append(innerHeight)
}

private fun highlightTtsRange(
    annotated: androidx.compose.ui.text.AnnotatedString,
    pageStart: Int,
    ttsRange: IntRange?,
    color: androidx.compose.ui.graphics.Color
): androidx.compose.ui.text.AnnotatedString {
    if (ttsRange == null) return annotated
    val text = annotated.text
    val pageEnd = pageStart + text.length
    val overlapStart = maxOf(pageStart, ttsRange.first) - pageStart
    val overlapEnd = (minOf(pageEnd, ttsRange.last + 1)) - pageStart
    if (overlapEnd <= overlapStart || overlapStart >= text.length) return annotated
    val builder = androidx.compose.ui.text.AnnotatedString.Builder(annotated)
    builder.addStyle(
        androidx.compose.ui.text.SpanStyle(background = color.copy(alpha = 0.25f)),
        overlapStart.coerceIn(0, text.length),
        overlapEnd.coerceIn(0, text.length)
    )
    return builder.toAnnotatedString()
}
