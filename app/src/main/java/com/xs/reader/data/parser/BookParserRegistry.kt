package com.xs.reader.data.parser

import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

@Singleton
class BookParserRegistry @Inject constructor(
    private val txt: TxtParser,
    private val epub: EpubParser,
    private val pdf: PdfParser
) {
    fun pickParser(file: File, mimeType: String?): BookParser? {
        return listOf(epub, pdf, txt).firstOrNull { it.supports(file, mimeType) }
    }
}
