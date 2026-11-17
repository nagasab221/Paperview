package com.paperview.mobile.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperview.mobile.cloud.formatSize
import java.io.File

/**
 * The opt-in folder browser, shown only when all-files access is granted.
 * Walks real directories so people who keep PDFs in a known folder don't have
 * to go through the system picker every time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onOpenFile: (File) -> Unit, onBack: () -> Unit) {
    val roots = remember { FileAccess.browseRoots() }
    var current by remember { mutableStateOf<File?>(null) }
    val entries = remember(current) { current?.let { FileAccess.listPdfs(it) } ?: emptyList() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        current?.name ?: "Browse",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val parent = current?.parentFile
                        if (current == null || roots.any { it.absolutePath == current?.absolutePath }) {
                            onBack()
                        } else if (parent != null && parent.canRead()) {
                            current = parent
                        } else {
                            current = null
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (current == null) {
                items(roots) { root -> Entry(root, isDir = true) { current = root } }
            } else {
                items(entries) { entry ->
                    Entry(entry, entry.isDirectory) {
                        if (entry.isDirectory) current = entry else onOpenFile(entry)
                    }
                }
                if (entries.isEmpty()) {
                    item {
                        Text(
                            "No PDFs in this folder.",
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Entry(file: File, isDir: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            null,
            Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!isDir) {
                Text(
                    formatSize(file.length()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    HorizontalDivider()
}
