package com.xs.reader.data.parser

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.xs.reader.data.db.BookFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PDF 走"按页"模式：每个页区段映射为一个 Chapter（默认每章 1 页）。
 * 真实文本仅在阅读器中需要时按页渲染（PdfRenderer），
 * 这里仅扫描页数与构造伪章节占位。
 */
@Singleton
class PdfParser @Inject constructor() : BookParser {

    override fun supports(file: File, mimeType: String?): Boolean {
        if (mimeType == "application/pdf") return true
        return file.extension.equals("pdf", ignoreCase = true)
    }

    override suspend fun parse(file: File): ParsedBook = withContext(Dispatchers.IO) {
        val pageCount = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            }
        }.getOrDefault(0)

        val chapters = if (pageCount <= 0) {
            listOf(RawChapter("正文", "", 0, 0))
        } else {
            (0 until pageCount).map { p ->
                RawChapter(
                    title = "第 ${p + 1} 页",
                    text = "",
                    pdfStartPage = p,
                    pdfEndPage = p
                )
            }
        }
        ParsedBook(
            title = file.nameWithoutExtension,
            author = null,
            format = BookFormat.PDF,
            cover = null,
            chapters = chapters
        )
    }
}
