package com.xs.reader.ui.bookmark

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xs.reader.data.db.BookEntity
import com.xs.reader.data.db.BookmarkEntity
import com.xs.reader.data.repo.BookRepository
import com.xs.reader.data.repo.BookmarkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookmarksState(
    val items: List<BookmarkEntity> = emptyList(),
    val books: Map<Long, BookEntity> = emptyMap()
)

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val bookRepo: BookRepository,
    private val bookmarkRepo: BookmarkRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BookmarksState())
    val state: StateFlow<BookmarksState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(bookmarkRepo.observeAll(), bookRepo.observeBooks()) { marks, books ->
                _state.update {
                    it.copy(
                        items = marks,
                        books = books.associateBy { b -> b.id }
                    )
                }
            }.collect {}
        }
    }

    fun delete(bm: BookmarkEntity) {
        viewModelScope.launch { bookmarkRepo.delete(bm) }
    }
}
