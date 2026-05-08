package com.xs.reader.data.repo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.xs.reader.data.db.BookDao
import com.xs.reader.data.db.BookEntity
import com.xs.reader.data.db.BookFormat
import com.xs.reader.data.db.ChapterDao
import com.xs.reader.data.db.ChapterEntity
import com.xs.reader.data.parser.BookParserRegistry
import com.xs.reader.data.parser.RawChapter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

class ImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Singleton
class BookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val parsers: BookParserRegistry
) {
    fun observeBooks(): Flow<List<BookEntity>> = bookDao.observeAll()
    fun observeBook(id: Long): Flow<BookEntity?> = bookDao.observeById(id)
    suspend fun getBook(id: Long): BookEntity? = bookDao.getById(id)

    fun observeChapters(bookId: Long) = chapterDao.observeByBook(bookId)
    suspend fun listChapters(bookId: Long) = chapterDao.listByBook(bookId)
    suspend fun getChapter(bookId: Long, index: Int) = chapterDao.getByIndex(bookId, index)

    fun booksRoot(): File = File(context.filesDir, "books").apply { mkdirs() }
    fun fontsRoot(): File = File(context.filesDir, "fonts").apply { mkdirs() }

    suspend fun loadChapterText(chapter: ChapterEntity): String = withContext(Dispatchers.IO) {
        val file = File(chapter.contentPath)
        if (!file.exists()) return@withContext ""
        // 共享 content.bin: 用 byteOffset/byteLength 切片
        if (chapter.byteLength >= 0 && chapter.byteOffset >= 0) {
            return@withContext RandomAccessFile(file, "r").use { raf ->
                raf.seek(chapter.byteOffset)
                val buf = ByteArray(chapter.byteLength)
                var off = 0
                while (off < buf.size) {
                    val read = raf.read(buf, off, buf.size - off)
                    if (read <= 0) break
                    off += read
                }
                String(buf, 0, off, Charsets.UTF_8)
            }
        }
        // 兼容旧版: 单章独立文件
        file.readText(Charsets.UTF_8)
    }

    suspend fun deleteBook(book: BookEntity) = withContext(Dispatchers.IO) {
        runCatching { File(book.sourcePath).parentFile?.deleteRecursively() }
        bookDao.deleteById(book.id)
    }

    /**
     * 用当前 parser 重新切分章节(对源文件还在的书有效)。
     * 旧章节文件会被删除并重建,阅读进度重置到第一章。
     */
    suspend fun recutChapters(bookId: Long, onProgress: (String) -> Unit = {}): BookEntity =
        withContext(Dispatchers.IO) {
            val book = bookDao.getById(bookId) ?: throw ImportException("书籍不存在")
            val srcFile = File(book.sourcePath)
            if (!srcFile.exists()) throw ImportException("源文件已丢失,无法重新切分。请删除后重新导入。")

            val mimeType = mimeForFormat(book.format)
            val parser = parsers.pickParser(srcFile, mimeType)
                ?: throw ImportException("未找到匹配的解析器")

            onProgress("解析章节…")
            val parsed = runCatching { parser.parse(srcFile) }.getOrElse {
                throw ImportException("解析失败: ${it.message ?: "未知错误"}", it)
            }

            onProgress("清理旧章节…")
            val bookDir = srcFile.parentFile ?: File(booksRoot(), bookId.toString())
            val chDir = File(bookDir, "chapters")
            chDir.deleteRecursively()
            File(bookDir, "content.bin").delete()
            chapterDao.deleteByBook(bookId)

            val totalChars = parsed.chapters.sumOf { it.text.length }
            onProgress("写入章节… 共 ${parsed.chapters.size} 章")
            val newChapterEntities = writeChaptersBundled(bookDir, bookId, parsed.chapters)
            onProgress("入库…")
            chapterDao.insertAll(newChapterEntities)

            val updated = book.copy(
                totalChapters = parsed.chapters.size,
                totalChars = totalChars,
                lastChapterIndex = 0,
                lastCharOffset = 0,
                readingProgress = 0f
            )
            bookDao.update(updated)
            updated
        }

    private fun mimeForFormat(format: BookFormat): String = when (format) {
        BookFormat.TXT -> "text/plain"
        BookFormat.EPUB -> "application/epub+zip"
        BookFormat.PDF -> "application/pdf"
    }

    suspend fun updateProgress(bookId: Long, chapter: Int, offset: Int, progress: Float) {
        bookDao.updateProgress(bookId, chapter, offset, progress)
    }

    suspend fun importFromUri(uri: Uri, onProgress: (String) -> Unit = {}): BookEntity = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (displayName, mimeType) = readMeta(resolver, uri)
        onProgress("拷贝文件…")

        val bookDir = File(booksRoot(), UUID.randomUUID().toString()).apply { mkdirs() }
        val ext = displayName.substringAfterLast('.', missingDelimiterValue = "")
            .ifBlank { mimeToExt(mimeType) }
        val safeName = displayName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "book.${ext.ifBlank { "bin" }}" }
        val srcFile = File(bookDir, safeName)
        resolver.openInputStream(uri)?.use { input ->
            srcFile.outputStream().use { input.copyTo(it) }
        } ?: throw ImportException("无法打开所选文件")

        val parser = parsers.pickParser(srcFile, mimeType)
            ?: throw ImportException("不支持的文件类型: ${mimeType ?: ext}")
        val sizeMB = srcFile.length() / 1024 / 1024
        onProgress("解析章节… (${sizeMB}MB)")
        val parsed = try {
            parser.parse(srcFile)
        } catch (oom: OutOfMemoryError) {
            srcFile.parentFile?.deleteRecursively()
            System.gc()
            throw ImportException("文件过大导致内存不足($sizeMB MB),请尝试拆分后再导入。", oom)
        } catch (t: Throwable) {
            srcFile.parentFile?.deleteRecursively()
            throw ImportException("解析失败: ${t.message ?: t.javaClass.simpleName}", t)
        }

        val coverPath: String? = parsed.cover?.let { bytes ->
            val cover = File(bookDir, "cover.bin")
            cover.writeBytes(bytes)
            cover.absolutePath
        }

        val totalChars = parsed.chapters.sumOf { it.text.length }
        val book = BookEntity(
            title = parsed.title.ifBlank { displayName.substringBeforeLast('.') },
            author = parsed.author,
            coverPath = coverPath,
            sourcePath = srcFile.absolutePath,
            format = parsed.format,
            totalChapters = parsed.chapters.size,
            totalChars = totalChars
        )
        val bookId = bookDao.insert(book)

        val total = parsed.chapters.size
        onProgress("写入章节… 共 $total 章")
        val chapterEntities = writeChaptersBundled(bookDir, bookId, parsed.chapters)
        onProgress("入库… ($total 章)")
        chapterDao.insertAll(chapterEntities)
        bookDao.update(book.copy(id = bookId))
        bookDao.getById(bookId)!!
    }

    /**
     * 把所有章节内容打包写入单一 content.bin,DB 行只存 (byteOffset, byteLength) 索引。
     * 这样 1000+ 章只需 1 次大文件 IO 而不是 1000 次小文件 IO,导入速度提升 1-2 个数量级。
     */
    private fun writeChaptersBundled(
        bookDir: File,
        bookId: Long,
        chapters: List<RawChapter>
    ): List<ChapterEntity> {
        val bundleFile = File(bookDir, "content.bin")
        val entities = ArrayList<ChapterEntity>(chapters.size)
        bundleFile.outputStream().buffered(64 * 1024).use { out ->
            var offset = 0L
            chapters.forEachIndexed { idx, raw ->
                val bytes = raw.text.toByteArray(Charsets.UTF_8)
                out.write(bytes)
                entities += ChapterEntity(
                    bookId = bookId,
                    chapterIndex = idx,
                    title = raw.title,
                    contentPath = bundleFile.absolutePath,
                    charCount = raw.text.length,
                    pdfStartPage = raw.pdfStartPage,
                    pdfEndPage = raw.pdfEndPage,
                    byteOffset = offset,
                    byteLength = bytes.size
                )
                offset += bytes.size
            }
        }
        return entities
    }

    private fun readMeta(resolver: ContentResolver, uri: Uri): Pair<String, String?> {
        var name = uri.lastPathSegment ?: "book"
        val mime = resolver.getType(uri)
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx) ?: name
                }
            }
        }
        return name to mime
    }

    private fun mimeToExt(mime: String?): String = when {
        mime == null -> ""
        mime == "application/pdf" -> "pdf"
        mime == "application/epub+zip" -> "epub"
        mime.startsWith("text/") -> "txt"
        else -> ""
    }

    fun bookFormatFromExt(ext: String): BookFormat? = when (ext.lowercase()) {
        "txt" -> BookFormat.TXT
        "epub" -> BookFormat.EPUB
        "pdf" -> BookFormat.PDF
        else -> null
    }
}
