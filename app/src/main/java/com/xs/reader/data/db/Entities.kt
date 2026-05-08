package com.xs.reader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BookFormat { TXT, EPUB, PDF }

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val sourcePath: String,
    val format: BookFormat,
    val totalChapters: Int = 0,
    val totalChars: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0L,
    val lastChapterIndex: Int = 0,
    val lastCharOffset: Int = 0,
    val readingProgress: Float = 0f
)

@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId"), Index(value = ["bookId", "chapterIndex"], unique = true)]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val title: String,
    /**
     * 兼容字段:
     *  - 老版 TXT/EPUB: 单章独立文件路径(每章一个 .txt)
     *  - 新版 TXT/EPUB: 共享 content.bin 文件路径,使用 byteOffset/byteLength 切片
     *  - PDF: 不使用,通过 pdfStartPage/pdfEndPage 渲染
     */
    val contentPath: String,
    val charCount: Int,
    val pdfStartPage: Int = -1,
    val pdfEndPage: Int = -1,
    /** 共享文件中的字节偏移,-1 表示采用单章独立文件 */
    val byteOffset: Long = -1L,
    /** 共享文件中本章占用字节数,-1 表示采用单章独立文件 */
    val byteLength: Int = -1
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val charOffset: Int,
    val snippet: String,
    val note: String? = null,
    val color: Int = 0xFFE6A23C.toInt(),
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tts_configs")
data class TtsConfigEntity(
    @PrimaryKey val id: String,
    val engineType: String,
    val voiceId: String?,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val apiBaseUrl: String? = null,
    val displayName: String? = null,
    val extraJson: String? = null
)
