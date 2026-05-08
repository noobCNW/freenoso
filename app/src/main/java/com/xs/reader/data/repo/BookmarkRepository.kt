package com.xs.reader.data.repo

import com.xs.reader.data.db.BookmarkDao
import com.xs.reader.data.db.BookmarkEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class BookmarkRepository @Inject constructor(
    private val dao: BookmarkDao
) {
    fun observeAll(): Flow<List<BookmarkEntity>> = dao.observeAll()
    fun observeByBook(bookId: Long): Flow<List<BookmarkEntity>> = dao.observeByBook(bookId)
    suspend fun insert(b: BookmarkEntity): Long = dao.insert(b)
    suspend fun update(b: BookmarkEntity) = dao.update(b)
    suspend fun delete(b: BookmarkEntity) = dao.delete(b)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
}
