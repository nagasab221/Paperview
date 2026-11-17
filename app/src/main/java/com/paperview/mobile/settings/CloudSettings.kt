package com.paperview.mobile.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.paperview.mobile.cloud.Account
import com.paperview.mobile.cloud.AccountEditor
import com.paperview.mobile.cloud.CloudViewModel
import com.paperview.mobile.cloud.Kind
import com.paperview.mobile.cloud.SshKey
import com.paperview.mobile.cloud.folderLabel
import com.paperview.mobile.landing.kindIcon
import com.paperview.mobile.landing.rememberSeconds
import com.paperview.mobile.ui.components.Hairline
import com.paperview.mobile.ui.components.InlineTextButton
import com.paperview.mobile.ui.components.PaperButton
import com.paperview.mobile.ui.components.PaperChoice
import com.paperview.mobile.ui.components.PaperField
import com.paperview.mobile.ui.components.PaperOutlinedButton
import com.paperview.mobile.ui.components.PaperRow
import com.paperview.mobile.ui.components.SectionHeading
import com.paperview.mobile.ui.theme.PaperTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Settings › Cloud: the accounts (each with what it last said), the form to
 * add or change one — which checks the connection before saving — the Cloud
 * tab switch and where secrets live.
 */
@Composable
fun CloudAccountsSection(cloud: CloudViewModel, onPickFolder: () -> Unit) {
    val colors = PaperTheme.colors
    var removing by remember { mutableStateOf<Account?>(null) }

    SectionHeading(
        "Cloud",
        trailing = if (cloud.editor == null) {
            { InlineTextButton("Add account") { cloud.startEditor(null) } }
        } else null,
    )
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            "Keep documents somewhere besides this phone: a NAS, another computer over SSH, " +
                "or a cloud bucket. Nothing uploads unless you choose to.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        cloud.accounts.forEach { acct -> AccountRow(cloud, acct) { removing = acct } }
        if (cloud.accounts.isEmpty() && cloud.editor == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("No accounts yet.", style = MaterialTheme.typography.bodyMedium, color = colors.muted)
                InlineTextButton("Add one") { cloud.startEditor(null) }
            }
        }
        cloud.editor?.let { AccountForm(cloud, it, onPickFolder) }

        Spacer(Modifier.height(6.dp))
        val ready = cloud.current?.ready == true
        PaperRow(
            title = "Cloud tab",
            subtitle = when {
                !ready -> "Add an account above to turn this on"
                cloud.enabled -> "On — the Library shows a Cloud tab"
                else -> "Off — the Library shows Recents only"
            },
            icon = Icons.Outlined.CloudQueue,
            showChevron = false,
            trailing = {
                Switch(
                    checked = cloud.active,
                    enabled = ready,
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
        val plain = cloud.accounts.filter { it.plainSecret != null }.map { it.name }
        Text(
            if (plain.isEmpty()) {
                "Passwords and keys are encrypted with a key kept in this phone's Keystore."
            } else {
                "The Keystore wasn't available, so the password for ${plain.joinToString()} is kept " +
                    "in PaperView's private settings on this phone."
            },
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }

    removing?.let { acct ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${acct.name}?") },
            text = {
                Text(
                    "PaperView forgets this account and its saved password, and removes what it " +
                        "downloaded from it. Nothing is deleted from the account itself.",
                )
            },
            confirmButton = {
                TextButton(onClick = { cloud.removeAccount(acct.id); removing = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** One account: name, where it is, and a line saying what it last said. */
@Composable
private fun AccountRow(cloud: CloudViewModel, acct: Account, onRemove: () -> Unit) {
    val colors = PaperTheme.colors
    var menu by remember { mutableStateOf(false) }
    val current = acct.id == cloud.currentId
    val testing = cloud.test?.takeIf { !it.fromForm && it.account == acct.id }
    PaperRow(
        title = acct.name,
        subtitle = acct.summary + if (current && cloud.accounts.size > 1) " · shown in the Cloud tab" else "",
        icon = kindIcon(acct.kind),
        showChevron = false,
        trailing = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, "More", tint = colors.muted, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!current && acct.ready) {
                        DropdownMenuItem(text = { Text("Show in the Cloud tab") }, onClick = {
                            menu = false; cloud.switchAccount(acct.id); cloud.setCloudEnabled(true)
                        })
                    }
                    DropdownMenuItem(
                        text = { Text("Test connection") },
                        enabled = cloud.test == null && acct.ready,
                        onClick = { menu = false; cloud.testAccount(acct.id) },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        enabled = cloud.editor == null,
                        onClick = { menu = false; cloud.startEditor(acct.id) },
                    )
                    DropdownMenuItem(text = { Text("Remove…") }, onClick = { menu = false; onRemove() })
                }
            }
        },
    )
    val health = cloud.health[acct.id]
    when {
        testing != null -> {
            val secs = rememberSeconds(testing.startedAt)
            Row(Modifier.padding(start = 37.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = colors.accent)
                Spacer(Modifier.width(8.dp))
                Text("${testing.stage}  $secs s", style = MaterialTheme.typography.bodySmall, color = colors.muted)
            }
        }
        health != null -> Text(
            if (health.ok) {
                "✓ ${health.text} · checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(health.at))}"
            } else {
                "Couldn't connect: ${health.text}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (health.ok) colors.accent else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 37.dp, bottom = 10.dp),
        )
    }
    Hairline(inset = 37)
}

private fun blurb(kind: Kind): String = when (kind) {
    Kind.Folder -> "A folder on this phone, or one a NAS, Nextcloud or SMB app offers in the system " +
        "folder picker. Documents open straight from it."
    Kind.WebDav -> "Nextcloud, ownCloud, Synology, QNAP, TrueNAS and most NAS boxes. Use the full " +
        "address of the folder, starting with http:// or https://."
    Kind.S3 -> "Cloudflare R2, Wasabi, AWS S3, MinIO, iDrive e2 — anything that speaks S3."
    Kind.B2 -> "Your own private Backblaze bucket — 10 GB free. The desktop app can use the same bucket."
    Kind.Ssh -> "Another computer or a NAS you can reach with ssh. Documents go in a folder there."
}

/** The add / edit form. "Connect and save" checks first and saves only once it answers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountForm(cloud: CloudViewModel, ed: AccountEditor, onPickFolder: () -> Unit) {
    val colors = PaperTheme.colors
    val testing = cloud.test?.takeIf { it.fromForm }
    val busy = testing != null
    val d = ed.draft
    fun set(change: (Account) -> Account) { ed.draft = change(ed.draft) }
    val savedSecret = remember(ed.editing, d.kind) { cloud.hasSavedSecret(ed) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(colors.surface, MaterialTheme.shapes.medium)
            .border(1.dp, colors.hairline, MaterialTheme.shapes.medium)
            .padding(16.dp),
    ) {
        Text(
            if (ed.editing != null) "Edit ${d.name}" else "Add an account",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(10.dp))
        if (ed.editing == null) {
            FlowRow {
                Kind.entries.forEach { k ->
                    PaperChoice(k.label, selected = d.kind == k, onClick = { if (!busy) set { it.copy(kind = k) } })
                }
            }
        }
        Text(blurb(d.kind), style = MaterialTheme.typography.bodySmall, color = colors.muted)
        Spacer(Modifier.height(10.dp))

        @Composable
        fun field(label: String, value: String, hint: String = "", number: Boolean = false, on: (String) -> Unit) {
            PaperField(
                value = value,
                onValueChange = { if (!busy) on(it) },
                label = label,
                placeholder = hint.ifEmpty { null },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (number) KeyboardType.Number else KeyboardType.Uri,
                ),
            )
        }

        field("Name", d.name, when (d.kind) {
            Kind.Ssh -> "e.g. Laptop"
            Kind.WebDav -> "e.g. Nextcloud"
            Kind.Folder -> "e.g. NAS share"
            Kind.S3 -> "e.g. R2"
            Kind.B2 -> "Backblaze B2"
        }) { v -> set { it.copy(name = v) } }

        when (d.kind) {
            Kind.Folder -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        if (d.address.isBlank()) "No folder chosen" else folderLabel(d.address),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    PaperOutlinedButton("Choose folder…", enabled = !busy) { onPickFolder() }
                }
            }
            Kind.WebDav -> {
                field("Server address", d.address, "https://nas.local:5006/PaperView") { v -> set { it.copy(address = v) } }
                field("User name", d.user) { v -> set { it.copy(user = v) } }
            }
            Kind.S3 -> {
                field("Endpoint", d.address, "https://<account id>.r2.cloudflarestorage.com") { v -> set { it.copy(address = v) } }
                field("Region", d.region, "“auto” for R2 · empty = us-east-1") { v -> set { it.copy(region = v) } }
                field("Bucket", d.bucket) { v -> set { it.copy(bucket = v) } }
                field("Access key ID", d.user) { v -> set { it.copy(user = v) } }
            }
            Kind.B2 -> {
                field("Key ID", d.user, "e.g. 005a1b2c3d4e5f60000000001") { v -> set { it.copy(user = v) } }
                field("Bucket name", d.bucket, "Exactly as it appears in Backblaze") { v -> set { it.copy(bucket = v) } }
            }
            Kind.Ssh -> {
                field("Host", d.address, "e.g. myhost.duckdns.org or 192.168.1.20") { v -> set { it.copy(address = v) } }
                field("User", d.user, "The account to sign in as") { v -> set { it.copy(user = v) } }
                field("Port (optional)", d.port, "22", number = true) { v -> set { it.copy(port = v.filter(Char::isDigit)) } }
            }
        }

        if (d.kind.hasSecret) {
            val label = when (d.kind) {
                Kind.S3 -> "Secret access key"
                Kind.B2 -> "Application key"
                Kind.Ssh -> "Password (optional)"
                else -> "Password"
            }
            PaperField(
                value = ed.secret,
                onValueChange = { if (!busy) ed.secret = it },
                label = label,
                placeholder = when {
                    savedSecret -> "Saved — leave empty to keep it"
                    d.kind == Kind.Ssh -> "Only if the server doesn't take this phone's key"
                    d.kind == Kind.B2 -> "Starts with K"
                    else -> null
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                visualTransformation = if (ed.showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = { InlineTextButton(if (ed.showSecret) "Hide" else "Show") { ed.showSecret = !ed.showSecret } },
            )
            if (d.kind == Kind.B2 && ed.secret.isNotBlank() && !ed.secret.trim().startsWith("K")) {
                Text(
                    "Application keys start with “K” — this looks like the Key ID.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        when (d.kind) {
            Kind.S3, Kind.B2 -> field("Folder in the bucket (optional)", d.folder, "Empty uses the whole bucket") { v ->
                set { it.copy(folder = v) }
            }
            Kind.Ssh -> field("Folder on the server", d.folder, "PaperView (in your home folder)") { v ->
                set { it.copy(folder = v) }
            }
            else -> {}
        }
        if (d.kind == Kind.WebDav || d.kind == Kind.S3) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Trust a self-signed certificate (a NAS on your own network)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = d.insecureTls,
                    onCheckedChange = { v -> if (!busy) set { it.copy(insecureTls = v) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent),
                )
            }
        }
        if (d.kind == Kind.Ssh) PhoneKey()

        Spacer(Modifier.height(10.dp))
        val needsSecret = (d.kind == Kind.S3 || d.kind == Kind.B2) && !savedSecret && ed.secret.isBlank()
        val ok = d.ready && !needsSecret
        if (testing != null) {
            val secs = rememberSeconds(testing.startedAt)
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                Spacer(Modifier.width(10.dp))
                Text("${testing.stage}  $secs s", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "PaperView saves the account once it answers. If nothing answers, it gives up " +
                    "within about 30 seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            PaperOutlinedButton("Stop") { cloud.stopTest() }
        } else {
            ed.failed?.let { why ->
                Text(
                    "Couldn't connect — nothing was saved",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text(
                    "Fix the details and try again, or save it anyway to connect later (say, when the NAS is on).",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
            PaperButton(
                text = if (ed.failed != null) "Try again" else "Connect and save",
                onClick = { cloud.connectAndSave() },
                enabled = ok,
            )
            Row(Modifier.padding(top = 6.dp)) {
                if (ed.failed != null) {
                    PaperOutlinedButton("Save anyway", enabled = ok) { cloud.saveAnyway() }
                    Spacer(Modifier.width(8.dp))
                }
                PaperOutlinedButton("Cancel") { cloud.stopTest(); cloud.editor = null }
            }
        }
    }
}

/** This phone's public SSH key, to add to the server's authorized_keys. */
@Composable
private fun PhoneKey() {
    val colors = PaperTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val key by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { SshKey.publicKey(context) }.getOrNull() }
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("This phone's key", style = MaterialTheme.typography.titleSmall)
        Text(
            "PaperView signs in with this key. Add it as a line to ~/.ssh/authorized_keys on the " +
                "server (the desktop app's computer can do it with one paste), then no password is " +
                "needed. The server's own key is remembered the first time.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
        )
        Text(
            key ?: "Making a key…",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = colors.ink,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .border(1.dp, colors.hairline, MaterialTheme.shapes.small)
                .padding(8.dp),
        )
        Row {
            InlineTextButton("Copy", enabled = key != null) {
                key?.let { clipboard.setText(AnnotatedString(it)) }
            }
            InlineTextButton("Share", enabled = key != null) {
                key?.let {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, it)
                    }
                    context.startActivity(Intent.createChooser(send, "Share this phone's SSH key"))
                }
            }
        }
    }
}
