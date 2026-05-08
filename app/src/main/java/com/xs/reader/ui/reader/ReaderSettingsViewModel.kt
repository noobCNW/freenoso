package com.xs.reader.ui.reader

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xs.reader.data.prefs.ReadingPrefs
import com.xs.reader.data.prefs.ReadingPrefsRepository
import com.xs.reader.data.repo.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@HiltViewModel
class ReaderSettingsViewModel @Inject constructor(
    private val prefsRepo: ReadingPrefsRepository,
    private val bookRepo: BookRepository
) : ViewModel() {

    val prefs: StateFlow<ReadingPrefs> = prefsRepo.flow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingPrefs()
    )

    fun setThemeId(id: String) = viewModelScope.launch { prefsRepo.setThemeId(id) }
    fun setFontFamily(id: String) = viewModelScope.launch { prefsRepo.setFontFamilyId(id) }
    fun setFontSize(v: Float) = viewModelScope.launch { prefsRepo.setFontSize(v) }
    fun setLineHeight(v: Float) = viewModelScope.launch { prefsRepo.setLineHeight(v) }
    fun setPageMargin(v: Float) = viewModelScope.launch { prefsRepo.setPageMargin(v) }
    fun setTurnMode(v: String) = viewModelScope.launch { prefsRepo.setTurnMode(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { prefsRepo.setKeepScreenOn(v) }
    fun setCustomFontPath(path: String?) = viewModelScope.launch { prefsRepo.setCustomFontPath(path) }
    fun setShowChapterTitle(v: Boolean) = viewModelScope.launch { prefsRepo.setShowChapterTitle(v) }

    fun importFontFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val target = withContext(Dispatchers.IO) {
                val ext = guessFontExt(context, uri)
                val out = File(bookRepo.fontsRoot(), "${UUID.randomUUID()}.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                out.absolutePath
            }
            prefsRepo.setCustomFontPath(target)
        }
    }

    private fun guessFontExt(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        }.getOrNull().orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext in listOf("ttf", "otf", "ttc")) ext else "ttf"
    }
}
