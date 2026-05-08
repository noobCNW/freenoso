package com.xs.reader.ui.shelf

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xs.reader.data.db.BookEntity
import com.xs.reader.data.repo.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShelfState(
    val books: List<BookEntity> = emptyList(),
    val importing: Boolean = false,
    val importingMessage: String = "",
    val error: String? = null
)

@HiltViewModel
class ShelfViewModel @Inject constructor(
    private val repo: BookRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ShelfState())
    val state: StateFlow<ShelfState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeBooks().collect { list ->
                _state.update { it.copy(books = list) }
            }
        }
    }

    fun importBook(context: Context, uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(importing = true, importingMessage = "准备…", error = null) }
            try {
                context.contentResolver.takePersistableUriPermissionSafe(uri)
                repo.importFromUri(uri) { msg ->
                    _state.update { it.copy(importingMessage = msg) }
                }
                _state.update { it.copy(importing = false, importingMessage = "") }
            } catch (e: Exception) {
                _state.update {
                    it.copy(importing = false, importingMessage = "", error = e.message ?: "导入失败")
                }
            }
        }
    }

    fun deleteBook(book: BookEntity) {
        viewModelScope.launch {
            repo.deleteBook(book)
        }
    }

    fun recutChapters(book: BookEntity) {
        viewModelScope.launch {
            _state.update { it.copy(importing = true, importingMessage = "重新切分中…", error = null) }
            try {
                repo.recutChapters(book.id) { msg ->
                    _state.update { it.copy(importingMessage = msg) }
                }
                _state.update { it.copy(importing = false, importingMessage = "") }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        importing = false,
                        importingMessage = "",
                        error = e.message ?: "重新切分失败"
                    )
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}

private fun android.content.ContentResolver.takePersistableUriPermissionSafe(uri: Uri) {
    runCatching {
        takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}
