package com.xs.reader.ui.reader

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xs.reader.data.db.BookEntity
import com.xs.reader.data.db.BookFormat
import com.xs.reader.data.db.BookmarkEntity
import com.xs.reader.data.db.ChapterEntity
import com.xs.reader.data.prefs.ReadingPrefs
import com.xs.reader.data.prefs.ReadingPrefsRepository
import com.xs.reader.data.repo.BookRepository
import com.xs.reader.data.repo.BookmarkRepository
import com.xs.reader.tts.TtsController
import com.xs.reader.tts.TtsEngineRegistry
import com.xs.reader.tts.TtsHost
import com.xs.reader.tts.TtsPlaybackService
import com.xs.reader.tts.TtsState
import com.xs.reader.tts.TtsVoice
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaderState(
    val loading: Boolean = true,
    val error: String? = null,
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentChapter: ChapterEntity? = null,
    val currentChapterText: String = "",
    val currentCharOffset: Int = 0,
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val ttsActiveSentence: IntRange? = null
) {
    val progress: Float
        get() {
            val b = book ?: return 0f
            if (b.totalChars <= 0) return 0f
            val before = chapters.take(currentChapterIndex).sumOf { it.charCount }
            return ((before + currentCharOffset).toFloat() / b.totalChars).coerceIn(0f, 1f)
        }
}

@HiltViewModel
@OptIn(FlowPreview::class)
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepo: BookRepository,
    private val bookmarkRepo: BookmarkRepository,
    private val prefsRepo: ReadingPrefsRepository,
    private val ttsController: TtsController,
    private val ttsRegistry: TtsEngineRegistry
) : ViewModel(), TtsHost {

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    val prefs: StateFlow<ReadingPrefs> = prefsRepo.flow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingPrefs()
    )

    val ttsState: StateFlow<TtsState> = ttsController.state

    private val progressTrigger = MutableStateFlow(0L)
    private var currentBookId: Long = -1L

    /**
     * 分页结果缓存(LRU,最多 40 个 key)。Key 由章节 + 排版参数 + 内容尺寸组成,
     * value 是 UI 层的 List<Page>,这里用 Any 持有避免 ViewModel 反向依赖 UI 类型。
     */
    private val pageCache = object : LinkedHashMap<String, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>): Boolean {
            return size > 40
        }
    }

    @Synchronized
    fun getCachedPages(key: String): Any? = pageCache[key]

    @Synchronized
    fun putCachedPages(key: String, value: Any) {
        pageCache[key] = value
    }

    fun clearTtsError() {
        ttsController.clearError()
    }

    /** 给 ReaderContent 后台预排版用: 按章节 index 加载 (title, body),不改变当前 state。 */
    suspend fun loadPrefetchChapter(chapterIndex: Int): Pair<String, String>? {
        val s = _state.value
        val ch = s.chapters.getOrNull(chapterIndex) ?: return null
        if (chapterIndex == s.currentChapterIndex) {
            return (s.currentChapter?.title ?: ch.title) to s.currentChapterText
        }
        val text = withContext(Dispatchers.IO) { bookRepo.loadChapterText(ch) }
        return ch.title to text
    }

    init {
        viewModelScope.launch {
            progressTrigger
                .filterNotNull()
                .debounce(1500)
                .distinctUntilChanged()
                .collect {
                    val s = _state.value
                    val book = s.book ?: return@collect
                    bookRepo.updateProgress(
                        bookId = book.id,
                        chapter = s.currentChapterIndex,
                        offset = s.currentCharOffset,
                        progress = s.progress
                    )
                }
        }
    }

    fun openBook(bookId: Long, jumpChapter: Int?, jumpOffset: Int?) {
        if (currentBookId == bookId && _state.value.book != null) {
            jumpChapter?.let { jumpToChapter(it, jumpOffset ?: 0) }
            return
        }
        currentBookId = bookId
        ttsController.bindHost(this)
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val book = bookRepo.getBook(bookId) ?: error("书籍不存在")
                val chapters = bookRepo.listChapters(bookId)
                if (chapters.isEmpty()) error("书籍内容为空")
                val targetIndex = jumpChapter?.coerceIn(0, chapters.size - 1)
                    ?: book.lastChapterIndex.coerceIn(0, chapters.size - 1)
                val targetOffset = jumpOffset ?: book.lastCharOffset
                val chapter = chapters[targetIndex]
                val text = withContext(Dispatchers.IO) { bookRepo.loadChapterText(chapter) }
                _state.update {
                    it.copy(
                        loading = false,
                        book = book,
                        chapters = chapters,
                        currentChapterIndex = targetIndex,
                        currentChapter = chapter,
                        currentChapterText = text,
                        currentCharOffset = targetOffset.coerceIn(0, text.length)
                    )
                }
                bookmarkRepo.observeByBook(bookId).collect { list ->
                    _state.update { it.copy(bookmarks = list) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun jumpToChapter(index: Int, offset: Int = 0) {
        val s = _state.value
        if (index !in s.chapters.indices) return
        val chapter = s.chapters[index]
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { bookRepo.loadChapterText(chapter) }
            _state.update {
                it.copy(
                    currentChapterIndex = index,
                    currentChapter = chapter,
                    currentChapterText = text,
                    currentCharOffset = offset.coerceIn(0, text.length)
                )
            }
            triggerSave()
        }
    }

    fun nextChapter() {
        val s = _state.value
        if (s.currentChapterIndex < s.chapters.size - 1) jumpToChapter(s.currentChapterIndex + 1, 0)
    }

    fun previousChapter() {
        val s = _state.value
        if (s.currentChapterIndex > 0) jumpToChapter(s.currentChapterIndex - 1, 0)
    }

    /**
     * 跳到上一章并定位到末尾(用于"前页继续向前滑"过渡到上一章末页)。
     * 用 Int.MAX_VALUE 让 LaunchedEffect 的 pageOf 自然落到末页。
     */
    fun previousChapterToEnd() {
        val s = _state.value
        if (s.currentChapterIndex > 0) jumpToChapter(s.currentChapterIndex - 1, Int.MAX_VALUE)
    }

    fun updateOffset(offset: Int) {
        val s = _state.value
        if (offset == s.currentCharOffset) return
        _state.update { it.copy(currentCharOffset = offset.coerceAtLeast(0)) }
        triggerSave()
    }

    fun addBookmark(note: String? = null, color: Int = 0xFFE6A23C.toInt()) {
        val s = _state.value
        val book = s.book ?: return
        val text = s.currentChapterText
        val from = s.currentCharOffset.coerceAtLeast(0).coerceAtMost(text.length)
        val to = (from + 60).coerceAtMost(text.length)
        val snippet = text.substring(from, to).replace("\n", " ").trim()
        viewModelScope.launch {
            bookmarkRepo.insert(
                BookmarkEntity(
                    bookId = book.id,
                    chapterIndex = s.currentChapterIndex,
                    charOffset = s.currentCharOffset,
                    snippet = if (snippet.isBlank()) (s.currentChapter?.title ?: "") else snippet,
                    note = note,
                    color = color
                )
            )
        }
    }

    fun deleteBookmark(b: BookmarkEntity) {
        viewModelScope.launch { bookmarkRepo.delete(b) }
    }

    fun toggleTts() {
        val s = _state.value
        val book = s.book ?: return
        if (book.format == BookFormat.PDF) {
            _state.update { it.copy(error = "PDF 暂不支持朗读") }
            return
        }
        val tts = ttsState.value
        if (tts.isPlaying) {
            ttsController.stop()
            return
        }
        viewModelScope.launch {
            val p = prefs.value
            val engine = ttsRegistry.byId(p.ttsActiveEngineId)
            val voices = runCatching { engine.listVoices() }.getOrDefault(emptyList())
            val voice: TtsVoice = voices.firstOrNull { it.id == p.ttsActiveVoiceId }
                ?: voices.firstOrNull()
                ?: TtsVoice(engine.id, "default", "默认", "zh-CN")
            startService()
            ttsController.bindHost(this@ReaderViewModel)
            ttsController.start(
                bookId = book.id,
                chapterIndex = s.currentChapterIndex,
                fromOffset = s.currentCharOffset,
                engineId = engine.id,
                voice = voice,
                speed = p.ttsSpeed,
                pitch = p.ttsPitch
            )
        }
    }

    private fun startService() {
        val intent = Intent(context, TtsPlaybackService::class.java)
        runCatching { context.startService(intent) }
    }

    fun setTtsHighlight(range: IntRange?) {
        _state.update { it.copy(ttsActiveSentence = range) }
    }

    fun isPdf(): Boolean = _state.value.book?.format == BookFormat.PDF

    private fun triggerSave() {
        progressTrigger.value = System.currentTimeMillis()
    }

    override fun onCleared() {
        super.onCleared()
        ttsController.bindHost(null)
    }

    /* TtsHost implementation */

    override suspend fun loadChapter(bookId: Long, chapterIndex: Int): Pair<String, String>? {
        val s = _state.value
        if (s.book?.id != bookId) return null
        return if (chapterIndex == s.currentChapterIndex) {
            (s.currentChapter?.title ?: "") to s.currentChapterText
        } else {
            val chapter = s.chapters.getOrNull(chapterIndex) ?: return null
            val text = withContext(Dispatchers.IO) { bookRepo.loadChapterText(chapter) }
            chapter.title to text
        }
    }

    override fun nextChapter(bookId: Long, chapterIndex: Int): Int? {
        val s = _state.value
        return if (chapterIndex < s.chapters.size - 1) chapterIndex + 1 else null
    }

    override fun onSentenceChanged(bookId: Long, chapterIndex: Int, range: IntRange?) {
        if (chapterIndex != _state.value.currentChapterIndex && range != null) {
            jumpToChapter(chapterIndex, range.first)
        }
        setTtsHighlight(range)
    }
}
