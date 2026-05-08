package com.xs.reader.data.parser

import com.xs.reader.data.db.BookFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.nio.charset.Charset

@Singleton
class TxtParser @Inject constructor() : BookParser {

    override fun supports(file: File, mimeType: String?): Boolean {
        if (mimeType?.startsWith("text/") == true) return true
        return file.extension.equals("txt", ignoreCase = true)
    }

    override suspend fun parse(file: File): ParsedBook = withContext(Dispatchers.IO) {
        val charset = detectCharset(file)
        val title = file.nameWithoutExtension
        val chapters = try {
            // 1) 流式按行扫描(主路径,内存友好)
            val streamed = parseStreaming(file, charset)
            // 2) 太少 -> 全文宽松扫描兜底(对单行/无换行文件)
            if (streamed.size >= 2) streamed
            else if (file.length() < LOOSE_FILE_LIMIT) parseLoose(file, charset)
            else sliceByLengthStreaming(file, charset)
        } catch (e: OutOfMemoryError) {
            System.gc()
            sliceByLengthStreaming(file, charset)
        }
        ParsedBook(
            title = title,
            author = null,
            format = BookFormat.TXT,
            cover = null,
            chapters = chapters
        )
    }

    private fun detectCharset(file: File): Charset {
        val detector = UniversalDetector(null)
        file.inputStream().use { input ->
            val buf = ByteArray(4096)
            var read: Int
            while (input.read(buf).also { read = it } > 0 && !detector.isDone) {
                detector.handleData(buf, 0, read)
            }
        }
        detector.dataEnd()
        val name = detector.detectedCharset
        detector.reset()
        return runCatching {
            if (name.isNullOrBlank()) Charsets.UTF_8
            else Charset.forName(name)
        }.getOrDefault(Charsets.UTF_8)
    }

    /* ============== 流式按行扫描(主路径) ============== */

    private fun parseStreaming(file: File, charset: Charset): List<RawChapter> {
        val chapters = mutableListOf<RawChapter>()
        var currentTitle: String = "引言"
        val currentBody = StringBuilder(8192)
        var hasAnyChapter = false
        var totalLines = 0L

        file.bufferedReader(charset).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                totalLines++
                val extracted = tryExtractChapterTitle(line)
                if (extracted != null) {
                    val body = currentBody.toString().trim()
                    if (body.isNotEmpty() || hasAnyChapter) {
                        chapters += RawChapter(currentTitle, body)
                    }
                    currentTitle = extracted.ifBlank { "第${chapters.size + 1}章" }
                    currentBody.setLength(0)
                    hasAnyChapter = true
                } else {
                    currentBody.append(line).append('\n')
                    // 单"章" buffer 超 50 万字,强制 flush 防止 OOM
                    if (currentBody.length > 500_000) {
                        chapters += RawChapter(currentTitle, currentBody.toString().trim())
                        currentTitle = "第${chapters.size + 1}段"
                        currentBody.setLength(0)
                    }
                }
            }
        }
        val tail = currentBody.toString().trim()
        if (tail.isNotEmpty()) {
            chapters += RawChapter(currentTitle, tail)
        }
        return if (hasAnyChapter) chapters else emptyList()
    }

    /* ============== 全文宽松扫描(兜底,适合无换行文本) ============== */

    private fun parseLoose(file: File, charset: Charset): List<RawChapter> {
        val text = file.readText(charset).replace("\r\n", "\n").replace('\r', '\n')
        val core = Regex("第\\s?[零〇一二三四五六七八九十百千万两\\d０-９]{1,12}\\s?[章回卷部篇集节折]")
        val raw = core.findAll(text).toList()
        if (raw.size < 2) return sliceByLengthStreaming(file, charset)

        val anchors = mutableListOf<Int>()
        for (m in raw) {
            val s = m.range.first
            val prev = if (s == 0) '\n' else text[s - 1]
            val prevOk = prev == '\n' || prev.isWhitespace() ||
                prev in "。！？!?；;…—\u201d\u201c\u3000"
            if (prevOk) anchors += s
        }
        if (anchors.size < 2) return sliceByLengthStreaming(file, charset)

        val chapters = mutableListOf<RawChapter>()
        if (anchors.first() > 200) {
            val pre = text.substring(0, anchors.first()).trim()
            if (pre.isNotEmpty()) chapters += RawChapter("引言", pre)
        }
        for (i in anchors.indices) {
            val start = anchors[i]
            val end = if (i == anchors.size - 1) text.length else anchors[i + 1]
            val section = text.substring(start, end)
            // 标题取首行 / 截到 80 字符内的首个句末标点为止
            val titleEnd = (0 until minOf(section.length, 100)).firstOrNull { idx ->
                val c = section[idx]
                c == '\n' || c in "。！？"
            } ?: minOf(section.length, 60)
            val title = section.substring(0, titleEnd).trim().trimDecorations()
            val body = section.substring(titleEnd).trim()
            chapters += RawChapter(
                title.ifBlank { "第${i + 1}章" },
                body
            )
        }
        return chapters
    }

    /* ============== 极端兜底:按字符长度切 ============== */

    private fun sliceByLengthStreaming(file: File, charset: Charset): List<RawChapter> {
        val chapters = mutableListOf<RawChapter>()
        val sliceSize = 4000
        val buf = CharArray(sliceSize)
        val sb = StringBuilder(sliceSize)
        file.bufferedReader(charset).use { reader ->
            while (true) {
                val read = reader.read(buf, 0, sliceSize)
                if (read <= 0) break
                sb.append(buf, 0, read)
                if (sb.length >= sliceSize) {
                    val text = sb.toString().trim()
                    if (text.isNotEmpty()) {
                        chapters += RawChapter("第${chapters.size + 1}段", text)
                    }
                    sb.setLength(0)
                }
            }
            val tail = sb.toString().trim()
            if (tail.isNotEmpty()) {
                chapters += RawChapter("第${chapters.size + 1}段", tail)
            }
        }
        return chapters
    }

    /* ============== 章节标题识别 ============== */

    /**
     * 尝试把一行解析为章节标题。
     * 支持的格式:
     * - 第N章/回/卷/部/篇/集/节/折 标题
     * - 序章/楔子/引子/前言/番外1/外传/特别篇 等
     * - Chapter N (英文)
     * - 装饰前缀: "章节目录 ", "正文 ", "目录 ", "全文阅读 ", "卷一/卷二 "
     * - 包裹符号: "===标题===", "【标题】", "*标题*"
     * 返回清理后的标题,如果不是章节行返回 null。
     */
    private fun tryExtractChapterTitle(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > 200) return null

        // 1) 包裹符号(=== 或 【】 或 ===)
        var content = trimmed
        var wasWrapped = false
        if (content.length >= 4 && content.startsWith("=") && content.endsWith("=")) {
            content = content.trim('=').trim()
            wasWrapped = true
            if (content.isEmpty()) return null
        } else if (content.startsWith("【") && content.endsWith("】")) {
            content = content.removeSurrounding("【", "】").trim()
            wasWrapped = true
            if (content.isEmpty()) return null
        }

        // 2) 装饰前缀(导航类) - 多次剥离应对嵌套
        val navPrefixes = listOf(
            "章节目录", "正文", "目录", "全文阅读", "正篇"
        )
        var stripped = true
        var safety = 0
        while (stripped && safety++ < 4) {
            stripped = false
            for (p in navPrefixes) {
                if (content.startsWith(p)) {
                    val after = content.substring(p.length)
                    if (after.isEmpty()) return null
                    val sep = after[0]
                    if (sep.isWhitespace() || sep == '\u3000' || sep in ":：-\u2014") {
                        content = after.trim().trimStart(':', '：', '-', '\u2014')
                            .trim()
                        stripped = true
                        break
                    }
                }
            }
        }

        // 3) 装饰符号
        content = content.trim('*', '-', '\u2014', '\u2022', '\u25c6', '【', '】', '[', ']').trim()
        if (content.isEmpty() || content.length > 140) return null

        // 4) 主正则集
        if (chapterTitleRegexes.any { it.matches(content) }) {
            return content
        }

        // 5) 包裹兜底: === 或 【】 包裹 + 短文本 + 无中文断句标点 -> 当章节
        //    用于覆盖 "===萧玄魂天帝篇===" "===外传===" 等没有"第X章"的标题
        if (wasWrapped && content.length in 1..60 && !content.contains(Regex("[。！？!?]"))) {
            return content
        }

        return null
    }

    private val chapterTitleRegexes: List<Regex> = listOf(
        // 第N章/回/卷/部/篇/集/节/折 [+ 副标题]
        Regex(
            "第\\s?[零〇一二三四五六七八九十百千万两\\d０-９]{1,12}\\s?[章回卷部篇集节折](?:[ \\t\\u3000:：\\-\\u2014][^\\n]{0,120})?"
        ),
        // 序章/楔子/番外/外传 等 [+ 数字] [+ 标题]
        Regex(
            "(?:序章|序幕|序言|楔子|引子|前言|开篇|后记|尾声|结语|终章|番外(?:篇)?|外传|特别篇|附录)(?:[\\s\\u3000]?[一二三四五六七八九十百千０-９\\d]{1,4})?(?:[ \\t\\u3000:：\\-\\u2014.][^\\n]{0,80})?"
        ),
        // Chapter N
        Regex(
            "(?:Chapter|CHAPTER|Ch\\.|Part|Book|Volume|Vol\\.)\\s+[\\dIVXLCDM]+(?:[ \\t:：\\-\\u2014][^\\n]{0,120})?",
            RegexOption.IGNORE_CASE
        )
    )

    private fun String.trimDecorations(): String =
        trim().trim('*', '-', '\u2014', '\u2022', '\u25c6', '【', '】', '[', ']', '=').trim()

    companion object {
        private const val LOOSE_FILE_LIMIT = 30L * 1024 * 1024
    }
}
