package com.xs.reader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ReaderScreen(
    bookId: Long,
    jumpChapter: Int? = null,
    jumpOffset: Int? = null,
    onBack: () -> Unit,
    vm: ReaderViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(bookId, jumpChapter, jumpOffset) {
        vm.openBook(bookId, jumpChapter, jumpOffset)
    }
    when {
        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(state.error ?: "加载失败")
        }
        else -> ReaderContent(state = state, vm = vm, onBack = onBack)
    }
}
