package com.xs.reader.data.parser

import com.xs.reader.data.db.BookFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.documentnode.epub4j.domain.Book
import io.documentnode.epub4j.epub.EpubReader
import org.jsoup.Jsoup
import java.io.File

@Singleton
class EpubParser @Inject constructor() : BookParser {

    override fun supports(file: File, mimeType: String?): Boolean {
        if (mimeType == "application/epub+zip") return true
        return file.extension.equals("epub", ignoreCase = true)
    }

    override suspend fun parse(file: File): ParsedBook = withContext(Dispatchers.IO) {
        val book: Book = file.inputStream().use { EpubReader().readEpub(it) }
        val title = book.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        val author = book.metadata?.authors?.firstOrNull()?.let {
            listOfNotNull(it.firstname, it.lastname).joinToString(" ").trim().ifBlank { null }
        }
        val coverBytes = runCatching { book.coverImage?.data }.getOrNull()

        val tocRefs = book.tableOfContents?.tocReferences?.flatten().orEmpty()
        val resourceIdToTitle = mutableMapOf<String, String>()
        tocRefs.forEach { ref ->
            ref.resource?.id?.let { id -> resourceIdToTitle[id] = ref.title ?: "" }
        }

        val spine = book.spine?.spineReferences.orEmpty()
        val chapters = mutableListOf<RawChapter>()
        spine.forEachIndexed { index, sr ->
            val res = sr.resource ?: return@forEachIndexed
            val raw = runCatching { String(res.data, Charsets.UTF_8) }.getOrElse { "" }
            val cleaned = htmlToPlainText(raw)
            if (cleaned.isBlank()) return@forEachIndexed
            val tocTitle = resourceIdToTitle[res.id]
            val title = (tocTitle ?: res.title)?.takeIf { it.isNotBlank() }
                ?: extractFirstHeading(raw)
                ?: "第${index + 1}章"
            chapters += RawChapter(title.trim(), cleaned)
        }

        ParsedBook(
            title = title,
            author = author,
            format = BookFormat.EPUB,
            cover = coverBytes,
            chapters = chapters.ifEmpty {
                listOf(RawChapter("正文", file.nameWithoutExtension))
            }
        )
    }

    private fun List<io.documentnode.epub4j.domain.TOCReference>.flatten(): List<io.documentnode.epub4j.domain.TOCReference> {
        val out = mutableListOf<io.documentnode.epub4j.domain.TOCReference>()
        forEach {
            out += it
            out += it.children.orEmpty().flatten()
        }
        return out
    }

    private fun htmlToPlainText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script, style, nav, header, footer").remove()
        val builder = StringBuilder()
        doc.body()?.children()?.forEach { collectText(it, builder) } ?: builder.append(doc.text())
        return builder.toString()
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun collectText(el: org.jsoup.nodes.Element, builder: StringBuilder) {
        when (el.tagName().lowercase()) {
            "br" -> builder.append('\n')
            "p", "div", "section", "article" -> {
                val text = el.text().trim()
                if (text.isNotEmpty()) {
                    builder.append(text).append("\n\n")
                }
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val text = el.text().trim()
                if (text.isNotEmpty()) builder.append(text).append("\n\n")
            }
            "li" -> {
                val text = el.text().trim()
                if (text.isNotEmpty()) builder.append("• ").append(text).append('\n')
            }
            else -> {
                if (el.children().isEmpty()) {
                    val text = el.text().trim()
                    if (text.isNotEmpty()) builder.append(text).append('\n')
                } else {
                    el.children().forEach { collectText(it, builder) }
                }
            }
        }
    }

    private fun extractFirstHeading(html: String): String? {
        val doc = Jsoup.parse(html)
        val h = doc.select("h1, h2, h3, title").firstOrNull() ?: return null
        return h.text().takeIf { it.isNotBlank() }
    }
}
