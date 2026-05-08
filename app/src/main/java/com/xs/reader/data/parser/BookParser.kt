package com.xs.reader.data.parser

import com.xs.reader.data.db.BookFormat
import java.io.File

data class RawChapter(
    val title: String,
    val text: String,
    val pdfStartPage: Int = -1,
    val pdfEndPage: Int = -1
)

data class ParsedBook(
    val title: String,
    val author: String?,
    val format: BookFormat,
    val cover: ByteArray?,
    val chapters: List<RawChapter>
)

interface BookParser {
    fun supports(file: File, mimeType: String?): Boolean
    suspend fun parse(file: File): ParsedBook
}
