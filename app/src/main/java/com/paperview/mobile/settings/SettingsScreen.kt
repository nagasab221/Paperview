package com.paperview.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.paperview.mobile.cloud.B2Client
import com.paperview.mobile.cloud.CloudViewModel
import com.paperview.mobile.ui.components.Hairline
import com.paperview.mobile.ui.components.InlineTextButton
import com.paperview.mobile.ui.components.PaperButton
import com.paperview.mobile.ui.components.PaperChoice
import com.paperview.mobile.ui.components.PaperField
import com.paperview.mobile.ui.components.PaperMasthead
import com.paperview.mobile.ui.components.PaperRow
import com.paperview.mobile.ui.components.SectionHeading
import com.paperview.mobile.ui.theme.PaperTheme
import com.paperview.mobile.ui.theme.Skin
import com.paperview.mobile.ui.theme.ThemeChoice

@Composable
fun SettingsScreen(
    theme: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
    skin: String,
    onSkinChange: (String) -> Unit,
    onClearToolHistory: () -> Unit,
    onClearCovers: () -> Unit,
    onClearShelf: () -> Unit,
    shelfCount: Int,
    shelfBytes: Long,
    cloud: CloudViewModel,
    allFilesEnabled: Boolean,
    allFilesGranted: Boolean,
    onAllFilesToggle: (Boolean) -> Unit,
    isDefaultPdfApp: Boolean,
    onMakeDefault: () -> Unit,
    defaultAppInstructions: String,
    onBack: (() -> Unit)?,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val colors = PaperTheme.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 40.dp,
        ),
    ) {
        item { PaperMasthead(title = "Settings") }

        // ── Appearance ───────────────────────────────────────────────────────
        item { SectionHeading("Appearance") }
        item {
            Spacer(Modifier.height(14.dp))
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row {
                ThemeChoice.entries.forEach { choice ->
                    PaperChoice(
                        text = choice.name,
                        selected = theme == choice,
                        onClick = { onThemeChange(choice) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Style", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Each style is a complete look, not just a different accent colour.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
            Spacer(Modifier.height(12.dp))
            Column {
                Skin.entries.forEach { option ->
                    SkinRow(
                        skin = option,
                        selected = option.key == skin,
                        onClick = { onSkinChange(option.key) },
                    )
                }
            }
        }

        // ── Reading ──────────────────────────────────────────────────────────
        item { SectionHeading("Reading") }
        item {
            PaperRow(
                title = "Default PDF app",
                subtitle = if (isDefaultPdfApp) {
                    // Being the default shouldn't trap you: say where to undo it,
                    // so nobody uninstalls the app just to open a PDF elsewhere.
                    "PaperView opens PDFs · tap to change or clear this"
                } else {
                    "Not set — tap to choose PaperView"
                },
                icon = Icons.Outlined.PictureAsPdf,
                onClick = onMakeDefault,
            )
            Hairline(inset = 37)
            if (!isDefaultPdfApp) {
                Text(
                    defaultAppInstructions,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    modifier = Modifier.padding(start = 37.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            PaperRow(
                title = "Browse files on this device",
                subtitle = if (allFilesGranted) {
                    "Granted — a folder browser appears in the Library"
                } else {
                    "Off. The system picker works everywhere without it."
                },
                icon = Icons.Outlined.FolderOpen,
                showChevron = false,
                trailing = {
                    Switch(
                        checked = allFilesEnabled,
                        onCheckedChange = onAllFilesToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onAccent,
                            checkedTrackColor = colors.accent,
                            uncheckedThumbColor = colors.muted,
                            uncheckedTrackColor = colors.background,
                            uncheckedBorderColor = colors.hairline,
                        ),
                    )
                },
            )
            Hairline(inset = 37)
        }

        // ── Cloud ────────────────────────────────────────────────────────────
        item { SectionHeading("Backblaze B2") }
        item { CloudSettings(cloud) }

        // ── Storage ──────────────────────────────────────────────────────────
        item { SectionHeading("Storage") }
        item {
            PaperRow(
                title = "Downloaded documents",
                subtitle = "${cloud.cacheCount} files · ${B2Client.formatSize(cloud.cacheBytes)}",
                icon = Icons.Outlined.CloudQueue,
                showChevron = false,
                trailing = { InlineTextButton("Clear") { cloud.clearCache() } },
            )
            Hairline(inset = 37)
            PaperRow(
                title = "Shelf copies",
                subtitle = if (shelfCount == 0) {
                    "Copies of documents other apps open"
                } else {
                    "$shelfCount files · ${B2Client.formatSize(shelfBytes)}"
                },
                icon = Icons.Outlined.Inventory2,
                showChevron = false,
                trailing = { InlineTextButton("Clear") { onClearShelf() } },
            )
            Hairline(inset = 37)
            PaperRow(
                title = "Library covers",
                subtitle = "Cached first-page previews",
                icon = Icons.Outlined.Palette,
                showChevron = false,
                trailing = { InlineTextButton("Clear") { onClearCovers() } },
            )
            Hairline(inset = 37)
            PaperRow(
                title = "Recent tools",
                subtitle = "Clear the recently used list",
                icon = Icons.Outlined.DeleteSweep,
                showChevron = false,
                trailing = { InlineTextButton("Clear") { onClearToolHistory() } },
            )
            Hairline(inset = 37)
        }

        item {
            Spacer(Modifier.height(34.dp))
            Text(
                "PaperView",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "The same viewer as the desktop build, rendering with PDFium. " +
                    "Editing runs on PDFBox. Nothing you open is uploaded anywhere " +
                    "except the bucket you configure yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
            )
        }
    }
}

/** A style option shown as its own swatch pair, so you can see what you're picking. */
@Composable
private fun SkinRow(skin: Skin, selected: Boolean, onClick: () -> Unit) {
    val colors = PaperTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 46.dp, height = 32.dp)
                .background(skinPreviewBackground(skin))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.accent else colors.hairline,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 24.dp, height = 3.dp)
                    .background(skinPreviewAccent(skin)),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(skin.label, style = MaterialTheme.typography.bodyLarge)
            Text(skin.blurb, style = MaterialTheme.typography.bodySmall, color = colors.muted)
        }
        if (selected) {
            Text("Selected", style = MaterialTheme.typography.labelMedium, color = colors.accent)
        }
    }
}

private fun skinPreviewBackground(skin: Skin) = when (skin) {
    Skin.Paper -> androidx.compose.ui.graphics.Color(0xFFFAF8F4)
    Skin.Slate -> androidx.compose.ui.graphics.Color(0xFFF4F6F8)
    Skin.Ink -> androidx.compose.ui.graphics.Color(0xFFFBFBF8)
}

private fun skinPreviewAccent(skin: Skin) = when (skin) {
    Skin.Paper -> androidx.compose.ui.graphics.Color(0xFF8A5A2B)
    Skin.Slate -> androidx.compose.ui.graphics.Color(0xFF35678F)
    Skin.Ink -> androidx.compose.ui.graphics.Color(0xFF0B0B0A)
}

@Composable
private fun CloudSettings(cloud: CloudViewModel) {
    val colors = PaperTheme.colors
    var keyId by remember(cloud.config) { mutableStateOf(cloud.config.keyId) }
    var appKey by remember(cloud.config) { mutableStateOf(cloud.config.appKey) }
    var bucket by remember(cloud.config) { mutableStateOf(cloud.config.bucket) }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(
            "Used by the Cloud tab of your Library. Uploads only happen when " +
                "you tap “Save to cloud” while reading.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        Spacer(Modifier.height(6.dp))
        PaperRow(
            title = "Cloud tab",
            subtitle = when {
                !cloud.config.ready -> "Save your bucket details below to turn this on"
                cloud.enabled -> "On — Library shows Recents and Cloud"
                else -> "Off — Library shows Recents only"
            },
            icon = Icons.Outlined.CloudQueue,
            showChevron = false,
            trailing = {
                Switch(
                    checked = cloud.active,
                    enabled = cloud.config.ready,
                    onCheckedChange = { cloud.setCloudEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onAccent,
                        checkedTrackColor = colors.accent,
                        uncheckedThumbColor = colors.muted,
                        uncheckedTrackColor = colors.background,
                        uncheckedBorderColor = colors.hairline,
                    ),
                )
            },
        )
        Hairline(inset = 37)
        Spacer(Modifier.height(14.dp))
        PaperField(
            value = keyId,
            onValueChange = { keyId = it; saved = false },
            label = "Key ID",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        PaperField(
            value = appKey,
            onValueChange = { appKey = it; saved = false },
            label = "Application key",
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showKey) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailing = {
                InlineTextButton(if (showKey) "Hide" else "Show") { showKey = !showKey }
            },
        )
        // The two B2 credentials look alike and get pasted into the wrong boxes.
        if (appKey.isNotBlank() && !appKey.startsWith("K")) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Application keys start with “K” — this looks like the Key ID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(10.dp))
        PaperField(
            value = bucket,
            onValueChange = { bucket = it; saved = false },
            label = "Bucket name",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        PaperButton(
            text = if (saved) "Saved" else "Save and connect",
            onClick = { cloud.setCredentials(keyId, appKey, bucket); saved = true },
        )
        cloud.error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
