package com.xs.reader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xs.reader.data.prefs.ReadingPrefs
import com.xs.reader.data.prefs.ReadingPrefsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: ReadingPrefsRepository
) : ViewModel() {
    val prefs: StateFlow<ReadingPrefs> = repo.flow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingPrefs()
    )

    fun setThemeId(id: String) = viewModelScope.launch { repo.setThemeId(id) }
    fun setFontSize(v: Float) = viewModelScope.launch { repo.setFontSize(v) }
    fun setLineHeight(v: Float) = viewModelScope.launch { repo.setLineHeight(v) }
    fun setPageMargin(v: Float) = viewModelScope.launch { repo.setPageMargin(v) }
    fun setTurnMode(v: String) = viewModelScope.launch { repo.setTurnMode(v) }
    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { repo.setKeepScreenOn(v) }
    fun setAppDarkMode(v: Boolean) = viewModelScope.launch { repo.setAppDarkMode(v) }
    fun setFollowSystemDark(v: Boolean) = viewModelScope.launch { repo.setFollowSystemDark(v) }
}
