package com.paperview.mobile.landing

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperview.mobile.cloud.CloudFile
import com.paperview.mobile.cloud.CloudViewModel
import com.paperview.mobile.cloud.Kind
import com.paperview.mobile.cloud.LocalDoc
import com.paperview.mobile.cloud.formatSize
import com.paperview.mobile.cloud.lastPart
import com.paperview.mobile.cloud.localName
import com.paperview.mobile.ui.components.Hairline
import com.paperview.mobile.ui.components.InlineTextButton
import com.paperview.mobile.ui.components.PaperEmptyState
import com.paperview.mobile.ui.components.PaperField
import com.paperview.mobile.ui.components.PaperRow
import com.paperview.mobile.ui.components.SectionHeading
import com.paperview.mobile.ui.theme.PaperTheme
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** The icon for each kind of account. */
fun kindIcon(kind: Kind): ImageVector = when (kind) {
    Kind.Folder -> Icons.Outlined.FolderShared
    Kind.WebDav -> Icons.Outlined.Language
    Kind.S3 -> Icons.Outlined.Storage
    Kind.B2 -> Icons.Outlined.Cloud
    Kind.Ssh -> Icons.Outlined.Computer
}

/** Seconds since [startMs], ticking while it's set. */
@Composable
fun rememberSeconds(startMs: Long): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startMs) {
        while (startMs > 0) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    return if (startMs > 0) ((now - startMs) / 1000).coerceAtLeast(0) else 0
}

private sealed interface CloudDialog {
    data class Rename(val file: CloudFile) : CloudDialog
    data class Delete(val items: List<Pair<String, String>>) : CloudDialog
    data object NewFolder : CloudDialog
}

/**
 * The current account's folder: where you are (a breadcrumb), whether it's
 * connected, its subfolders, and its documents split by where they are.
 */
@Composable
fun CloudPage(
    cloud: CloudViewModel,
    onOpenLocal: (java.io.File) -> Unit,
    onUploadFiles: () -> Unit,
    onUploadFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
) {
    val colors = PaperTheme.colors
    val acct = cloud.current ?: return
    var dialog by remember { mutableStateOf<CloudDialog?>(null) }
    val inPlace = acct.kind == Kind.Folder

    val dirs = cloud.files.filter { it.isDir }
    val localNames = cloud.localFiles.map { it.name }.toSet()
    val remoteOnly = cloud.files.filter { !it.isDir && localName(it.name) !in localNames }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
    ) {
        item {
            var addOpen by remember { mutableStateOf(false) }
            SectionHeading(
                "Saved to cloud",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            InlineTextButton("Add…") { addOpen = true }
                            DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                                MenuItem("Upload PDFs…") { addOpen = false; onUploadFiles() }
                                MenuItem("Upload a folder…") { addOpen = false; onUploadFolder() }
                                MenuItem("New folder…") { addOpen = false; dialog = CloudDialog.NewFolder }
                            }
                        }
                        IconButton(onClick = { cloud.refresh() }, enabled = !cloud.busy) {
                            Icon(Icons.Outlined.Refresh, "Refresh", tint = if (cloud.busy) colors.muted else colors.ink)
                        }
                    }
                },
            )
        }
        item { PathBar(cloud) }

        // What's happening now.
        item {
            val secs = rememberSeconds(cloud.opStartedAt)
            val t = cloud.transfer
            if (t != null && t.label == "Uploading") {
                Text(
                    "${cloud.status}  ${(t.fraction * 100).toInt()}%" +
                        if (t.bytesPerSecond > 0) " · ${formatSize(t.bytesPerSecond.toLong())}/s" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                LinearProgressIndicator(
                    progress = { t.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp),
                    color = colors.accent,
                    trackColor = colors.hairline,
                )
            } else if (cloud.busy || cloud.status.isNotEmpty()) {
                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.accent)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        cloud.status.ifBlank { "Connecting…" } + if (secs >= 2) "  $secs s" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                    )
                }
            }
        }

        if (cloud.selected.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${cloud.selected.size} selected", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    InlineTextButton("Download") { cloud.downloadSelected() }
                    InlineTextButton("Delete…") {
                        dialog = CloudDialog.Delete(
                            cloud.files.filter { !it.isDir && cloud.selected.contains(it.path) }.map { it.path to it.id },
                        )
                    }
                    InlineTextButton("Cancel") { cloud.selected.clear() }
                }
                Hairline()
            }
        }

        if (dirs.isNotEmpty()) {
            item { GroupLabel("Folders", dirs.size) }
            items(dirs, key = { "d:" + it.path }) { d ->
                PaperRow(
                    title = d.name,
                    icon = Icons.Outlined.Folder,
                    onClick = { cloud.enterDir(d.path) },
                )
                Hairline()
            }
        }
        if (cloud.localFiles.isNotEmpty()) {
            item { GroupLabel(if (inPlace) "Copies kept offline" else "On this device", cloud.localFiles.size) }
            items(cloud.localFiles, key = { "l:" + it.file.absolutePath }) { doc ->
                LocalRow(cloud, doc, acct.name, inPlace, onOpenLocal)
            }
        }
        if (remoteOnly.isNotEmpty()) {
            item { GroupLabel("In ${acct.name}", remoteOnly.size) }
            items(remoteOnly, key = { "r:" + it.path }) { f ->
                RemoteRow(cloud, f, acct.name, inPlace) { dialog = it }
            }
        }

        item {
            if (cloud.listed && cloud.files.isEmpty() && cloud.localFiles.isEmpty() && !cloud.busy) {
                PaperEmptyState(
                    title = if (cloud.dir.isEmpty()) "Nothing in ${acct.name} yet" else "This folder is empty",
                    body = "Use Add… above, or “Save to cloud” while reading, to put PDFs here.",
                )
            }
            cloud.error?.let { why ->
                Spacer(Modifier.height(12.dp))
                if (!cloud.listed) {
                    val auth = listOf("401", "403", "accept", "sign-in", "password", "Auth").any { why.contains(it, true) }
                    Text(
                        if (auth) "${acct.name} didn't let PaperView in" else "Couldn't reach ${acct.name}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row {
                    if (!cloud.listed) InlineTextButton("Check the account in Settings") { onOpenSettings() }
                    InlineTextButton("Try again") { cloud.refresh() }
                }
            }
            val docs = cloud.files.filter { !it.isDir }
            if (docs.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                val used = docs.sumOf { it.size }
                var line = "${docs.size} file${if (docs.size == 1) "" else "s"} · ${formatSize(used)}"
                if (acct.kind == Kind.B2 && cloud.dir.isEmpty()) {
                    line += " of ${formatSize(CloudViewModel.FREE_TIER_BYTES)} free tier"
                }
                Text(line, style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
    }

    when (val d = dialog) {
        is CloudDialog.Rename -> NameDialog(
            title = "Rename",
            body = "It's renamed in ${acct.name}, so your other devices see the new name too.",
            initial = d.file.name.substringBeforeLast('.'),
            confirm = "Rename",
            taken = cloud.files.map { it.name },
            withPdf = true,
            onDismiss = { dialog = null },
        ) { name -> dialog = null; if (name != d.file.name) cloud.rename(d.file, name) }

        CloudDialog.NewFolder -> NameDialog(
            title = "New folder",
            body = "In ${cloud.placeName()}",
            initial = "",
            confirm = "Make folder",
            taken = cloud.files.map { it.name },
            withPdf = false,
            onDismiss = { dialog = null },
        ) { name -> dialog = null; cloud.makeFolder(name) }

        is CloudDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (d.items.size == 1) "Delete this file?" else "Delete ${d.items.size} files?") },
            text = {
                Column {
                    d.items.take(5).forEach { Text("·  ${lastPart(it.first)}") }
                    if (d.items.size > 5) Text("…and ${d.items.size - 5} more", color = colors.muted)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "They're removed from ${acct.name} for good — your other devices won't see " +
                            "them either. Copies downloaded to this phone are removed too.",
                        color = colors.muted,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { cloud.delete(d.items); dialog = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )

        null -> {}
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(text, style = MaterialTheme.typography.bodyMedium) }, onClick = onClick)

/** "Laptop › Math › Topology", each step tappable, plus connection state and switching. */
@Composable
private fun PathBar(cloud: CloudViewModel) {
    val colors = PaperTheme.colors
    val acct = cloud.current ?: return
    val others = cloud.accounts.filter { it.ready }
    var switchOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(kindIcon(acct.kind), null, Modifier.size(18.dp), tint = colors.muted)
        Spacer(Modifier.width(6.dp))
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val parts = cloud.dir.split('/').filter { it.isNotEmpty() }
            Crumb(acct.name, last = parts.isEmpty()) { cloud.enterDir("") }
            parts.forEachIndexed { i, part ->
                Text("›", color = colors.muted, modifier = Modifier.padding(horizontal = 2.dp))
                Crumb(part, last = i == parts.lastIndex) { cloud.enterDir(parts.take(i + 1).joinToString("/")) }
            }
        }
        when {
            cloud.listed -> Text("✓ Connected", style = MaterialTheme.typography.labelMedium, color = colors.accent)
            !cloud.busy && cloud.error != null ->
                Text("Not connected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        if (others.size > 1) {
            Box {
                IconButton(onClick = { switchOpen = true }) {
                    Icon(Icons.Outlined.SwapHoriz, "Switch account", tint = colors.ink)
                }
                DropdownMenu(expanded = switchOpen, onDismissRequest = { switchOpen = false }) {
                    others.forEach { a ->
                        DropdownMenuItem(
                            leadingIcon = { Icon(kindIcon(a.kind), null, Modifier.size(18.dp)) },
                            text = {
                                Text(
                                    a.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (a.id == acct.id) colors.accent else colors.ink,
                                )
                            },
                            onClick = { switchOpen = false; if (a.id != acct.id) cloud.switchAccount(a.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Crumb(text: String, last: Boolean, onClick: () -> Unit) {
    if (last) {
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    } else {
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(text, style = MaterialTheme.typography.titleSmall, color = PaperTheme.colors.accent, maxLines = 1)
        }
    }
}

@Composable
private fun GroupLabel(text: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = PaperTheme.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = PaperTheme.colors.muted)
    }
    Hairline()
}

/** A document downloaded to this phone: opens with no network at all. */
@Composable
private fun LocalRow(
    cloud: CloudViewModel,
    doc: LocalDoc,
    place: String,
    inPlace: Boolean,
    onOpen: (java.io.File) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    PaperRow(
        title = doc.name,
        subtitle = "On this device · ${formatSize(doc.size)}",
        icon = Icons.Outlined.OfflinePin,
        showChevron = false,
        onClick = { onOpen(doc.file) },
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, "More", tint = PaperTheme.colors.muted, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(if (inPlace) "Remove offline copy" else "Remove download") {
                        menu = false
                        cloud.removeLocal(doc.file)
                        cloud.message = "Removed from this phone; it's still in $place"
                    }
                }
            }
        },
    )
    Hairline()
}

/** A document only in the account: downloading, waiting, or tap to get it. */
@Composable
private fun RemoteRow(
    cloud: CloudViewModel,
    file: CloudFile,
    place: String,
    inPlace: Boolean,
    onDialog: (CloudDialog) -> Unit,
) {
    val colors = PaperTheme.colors
    val transfer = cloud.transfer?.takeIf { it.path == file.path && it.label == "Downloading" }
    val queued = cloud.queuedPaths.contains(file.path)
    val picked = cloud.selected.contains(file.path)
    var menu by remember { mutableStateOf(false) }
    val date = if (file.modifiedMs > 0) " · " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.modifiedMs)) else ""
    val subtitle = when {
        transfer != null -> "Downloading ${(transfer.fraction * 100).toInt()}%" +
            if (transfer.bytesPerSecond > 0) " · ${formatSize(transfer.bytesPerSecond.toLong())}/s" else ""
        queued -> "Waiting to download…"
        picked -> "Selected · ${formatSize(file.size)}"
        inPlace -> "Opens from $place · ${formatSize(file.size)}$date"
        else -> "Tap to download · ${formatSize(file.size)}$date"
    }
    PaperRow(
        title = file.name,
        subtitle = subtitle,
        icon = when {
            transfer != null || queued -> Icons.Outlined.Downloading
            inPlace -> Icons.Outlined.Description
            else -> Icons.Outlined.CloudDownload
        },
        showChevron = false,
        onClick = {
            when {
                cloud.selected.isNotEmpty() -> cloud.toggleSelected(file.path)
                transfer != null || queued -> {}
                inPlace -> cloud.openInPlace(file)
                else -> cloud.download(file.path, file.size, openAfter = true)
            }
        },
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, "More", tint = if (picked) colors.accent else colors.muted, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (inPlace) {
                        MenuItem("Open") { menu = false; cloud.openInPlace(file) }
                        MenuItem("Keep a copy offline") { menu = false; cloud.download(file.path, file.size, false) }
                    } else {
                        MenuItem("Download") { menu = false; cloud.download(file.path, file.size, false) }
                        MenuItem("Download and open") { menu = false; cloud.download(file.path, file.size, true) }
                    }
                    MenuItem("Rename…") { menu = false; onDialog(CloudDialog.Rename(file)) }
                    MenuItem("Delete from $place…") { menu = false; onDialog(CloudDialog.Delete(listOf(file.path to file.id))) }
                    MenuItem(if (picked) "Deselect" else "Select") { menu = false; cloud.toggleSelected(file.path) }
                }
            }
        },
    )
    if (transfer != null) {
        LinearProgressIndicator(
            progress = { transfer.fraction },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = colors.accent,
            trackColor = colors.hairline,
        )
    } else if (queued) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = colors.accent, trackColor = colors.hairline)
    } else {
        Hairline()
    }
}

/** Asks for a name: no "/", not hidden, not taken. */
@Composable
private fun NameDialog(
    title: String,
    body: String,
    initial: String,
    confirm: String,
    taken: List<String>,
    withPdf: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val name = text.trim().let { if (withPdf && it.isNotEmpty() && !it.lowercase().endsWith(".pdf")) "$it.pdf" else it }
    val problem = when {
        name.isEmpty() -> null
        name.contains('/') -> "Names can't contain “/”."
        name.startsWith(".") -> "Names starting with “.” would be hidden."
        name != (if (withPdf) "$initial.pdf" else initial) && taken.contains(name) -> "There's already something with that name here."
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(body, color = PaperTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                PaperField(value = text, onValueChange = { text = it }, label = "Name", modifier = Modifier.fillMaxWidth())
                problem?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotEmpty() && problem == null) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
