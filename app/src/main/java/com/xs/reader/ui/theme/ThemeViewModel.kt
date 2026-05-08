package com.xs.reader.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xs.reader.data.prefs.ReadingPrefs
import com.xs.reader.data.prefs.ReadingPrefsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class ThemeViewModel @Inject constructor(
    repo: ReadingPrefsRepository
) : ViewModel() {
    val state: StateFlow<ReadingPrefs> = repo.flow.stateIn(
        viewModelScope, SharingStarted.Eagerly, ReadingPrefs()
    )
}
