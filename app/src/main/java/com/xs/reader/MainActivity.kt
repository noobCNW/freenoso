package com.xs.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.xs.reader.ui.AppRoot
import com.xs.reader.ui.theme.AppTheme
import com.xs.reader.ui.theme.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeVm: ThemeViewModel = hiltViewModel()
            val state by themeVm.state.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val isDark = if (state.followSystemDark) systemDark else state.appDarkMode
            AppTheme(darkTheme = isDark, dynamicColor = false) {
                AppRoot()
            }
        }
    }
}
