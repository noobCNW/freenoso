package com.xs.reader.ui.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

data class Page(
    val startOffset: Int,
    val endOffset: Int,
    val annotated: AnnotatedString
)

/**
 * 分页算法。
 *
 * 核心思路: 把整章正文(可选 + 章节标题)拼成一个 AnnotatedString,
 * 调用一次 TextMeasurer.measure 拿到 TextLayoutResult(包含每行的 y 坐标和字符偏移),
 * 然后按行高累加切页。这样整个章节只 measure 一次,
 * 比反复二分 measure substring 的旧算法快 1-2 个数量级。
 */
object Paginator {

    /** 单次 layout 处理的字符上限。超长章节(异常切分)走 fallback 分块路径。 */
    private const val SINGLE_LAYOUT_LIMIT = 60_000

    suspend fun paginate(
        title: String,
        body: String,
        textMeasurer: TextMeasurer,
        style: TextStyle,
        titleStyle: TextStyle,
        size: IntSize,
        paragraphSpacing: Int = 8,
        showTitle: Boolean = true
    ): List<Page> {
        if (size.width <= 0 || size.height <= 0) return emptyList()

        val text = body.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isEmpty()) {
            return listOf(Page(0, 0, AnnotatedString(if (showTitle && title.isNotBlank()) title else "")))
        }

        // 极端长章节走兜底分块算法,保证不会因为单次 measure 内存爆炸卡死
        if (text.length > SINGLE_LAYOUT_LIMIT) {
            return paginateChunked(title, text, textMeasurer, style, titleStyle, size, paragraphSpacing, showTitle)
        }

        return paginateWholeChapter(title, text, textMeasurer, style, titleStyle, size, showTitle)
    }

    /** 整章一次 measure + 按行切页(主路径)。 */
    private suspend fun paginateWholeChapter(
        title: String,
        text: String,
        textMeasurer: TextMeasurer,
        style: TextStyle,
        titleStyle: TextStyle,
        size: IntSize,
        showTitle: Boolean
    ): List<Page> {
        val titleVisible = showTitle && title.isNotBlank()
        // title + "\n\n" 作为前缀放进同一个 AnnotatedString,布局算法自然把它考虑进去
        val titlePrefix = if (titleVisible) "$title\n\n" else ""
        val titleLength = titlePrefix.length

        val combined = buildAnnotatedString {
            if (titleVisible) {
                withStyle(ParagraphStyle()) {
                    withStyle(
                        SpanStyle(
                            fontSize = titleStyle.fontSize,
                            fontWeight = titleStyle.fontWeight,
                            color = titleStyle.color
                        )
                    ) {
                        append(title)
                    }
                }
                append("\n")
            }
            append(text)
        }

        coroutineContext.ensureActive()
        val constraints = Constraints(
            minWidth = 0, maxWidth = size.width,
            minHeight = 0, maxHeight = Int.MAX_VALUE
        )
        val layout = textMeasurer.measure(
            text = combined,
            style = style,
            constraints = constraints
        )

        val pageHeight = size.height
        val totalLines = layout.lineCount
        if (totalLines == 0) {
            return listOf(Page(0, 0, AnnotatedString(if (titleVisible) title else "")))
        }

        val pages = mutableListOf<Page>()
        var lineStart = 0
        while (lineStart < totalLines) {
            coroutineContext.ensureActive()
            val pageStartY = layout.getLineTop(lineStart)
            var lineEnd = lineStart + 1
            while (lineEnd < totalLines &&
                layout.getLineBottom(lineEnd) - pageStartY <= pageHeight
            ) {
                lineEnd++
            }
            val combinedStart = layout.getLineStart(lineStart)
            val combinedEnd = if (lineEnd >= totalLines) combined.length else layout.getLineStart(lineEnd)
            val pageAnnotated = combined.subSequence(combinedStart, combinedEnd)
            // 章节正文偏移(去掉标题前缀部分)
            val bodyStart = (combinedStart - titleLength).coerceAtLeast(0)
            val bodyEnd = (combinedEnd - titleLength).coerceAtLeast(0)
            pages += Page(bodyStart, bodyEnd, pageAnnotated)
            lineStart = lineEnd
        }
        return pages.ifEmpty {
            listOf(Page(0, 0, AnnotatedString(if (titleVisible) title else "")))
        }
    }

    /** 超长章节兜底: 按 50000 字符块切,每块独立 measure 后切页。 */
    private suspend fun paginateChunked(
        title: String,
        text: String,
        textMeasurer: TextMeasurer,
        style: TextStyle,
        titleStyle: TextStyle,
        size: IntSize,
        paragraphSpacing: Int,
        showTitle: Boolean
    ): List<Page> {
        val pages = mutableListOf<Page>()
        var cursor = 0
        var first = true
        while (cursor < text.length) {
            coroutineContext.ensureActive()
            val end = (cursor + SINGLE_LAYOUT_LIMIT).coerceAtMost(text.length)
            val chunk = text.substring(cursor, end)
            val showThisTitle = showTitle && first
            val sub = paginateWholeChapter(
                title = if (showThisTitle) title else "",
                text = chunk,
                textMeasurer = textMeasurer,
                style = style,
                titleStyle = titleStyle,
                size = size,
                showTitle = showThisTitle
            )
            // 把 sub 的偏移加上 chunk 起始点,得到全章相对偏移
            for (p in sub) {
                pages += Page(p.startOffset + cursor, p.endOffset + cursor, p.annotated)
            }
            cursor = end
            first = false
        }
        return pages.ifEmpty {
            listOf(Page(0, 0, AnnotatedString(if (showTitle && title.isNotBlank()) title else "")))
        }
    }

    /** 给定页列表与字符偏移,返回所在页索引。 */
    fun pageOf(pages: List<Page>, offset: Int): Int {
        if (pages.isEmpty()) return 0
        val idx = pages.indexOfFirst { offset < it.endOffset }
        return if (idx == -1) pages.lastIndex else idx
    }
}
