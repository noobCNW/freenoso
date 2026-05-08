package com.xs.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ColorChoices = listOf(
    0xFFE6A23C.toInt(),
    0xFFEB5757.toInt(),
    0xFF27AE60.toInt(),
    0xFF2D9CDB.toInt(),
    0xFF9B51E0.toInt()
)

@Composable
fun AddBookmarkDialog(
    defaultSnippet: String,
    onConfirm: (note: String?, color: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var note by remember { mutableStateOf("") }
    var selectedColor by remember { mutableIntStateOf(ColorChoices.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(note.takeIf { it.isNotBlank() }, selectedColor) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text("添加书签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "位置：${defaultSnippet.take(80)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("颜色", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ColorChoices.forEach { c ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .clickable { selectedColor = c },
                            contentAlignment = Alignment.Center
                        ) {
                            if (c == selectedColor) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}
