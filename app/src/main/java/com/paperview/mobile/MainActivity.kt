package com.paperview.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.paperview.mobile.landing.ThumbnailStore
import com.paperview.mobile.ui.components.Hairline
import com.paperview.mobile.ui.theme.PaperTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.paperview.mobile.assoc.DefaultApp
import com.paperview.mobile.cloud.B2Client
import com.paperview.mobile.cloud.CloudViewModel
import com.paperview.mobile.files.BrowserScreen
import com.paperview.mobile.files.FileAccess
import com.paperview.mobile.landing.LandingScreen
import com.paperview.mobile.pdf.DocSource
import com.paperview.mobile.print.PrintPdf
import com.paperview.mobile.scan.ScanScreen
import com.paperview.mobile.scan.ScanFilter
import com.paperview.mobile.scan.ScanPipeline
import com.paperview.mobile.scan.ScanReviewScreen
import com.paperview.mobile.scan.rememberDocumentScanLauncher
import com.paperview.mobile.settings.SettingsScreen
import com.paperview.mobile.store.RecentEntry
import com.paperview.mobile.store.Recents
import com.paperview.mobile.store.Shelf
import com.paperview.mobile.tools.Tool
import com.paperview.mobile.tools.ToolFlowScreen
import com.paperview.mobile.tools.ToolHistory
import com.paperview.mobile.tools.ToolId
import com.paperview.mobile.tools.ToolInput
import com.paperview.mobile.tools.ToolViewModel
import com.paperview.mobile.tools.ToolsScreen
import com.paperview.mobile.ui.theme.PaperViewTheme
import com.paperview.mobile.ui.theme.ThemeChoice
import com.paperview.mobile.viewer.DocState
import com.paperview.mobile.viewer.ViewerActions
import com.paperview.mobile.viewer.ViewerScreen
import com.paperview.mobile.viewer.ViewerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen destinations that sit outside the tabbed shell. */
private enum class Route { Shell, Viewer, Browser, ToolFlow, ScanReview }

private enum class Tab(val label: String, val icon: ImageVector) {
    Library("Library", Icons.Outlined.LibraryBooks),
    Scan("Scan", Icons.Outlined.DocumentScanner),
    Tools("Tools", Icons.Outlined.Tune),
    Settings("Settings", Icons.Outlined.Settings),
}

class MainActivity : ComponentActivity() {

    private val viewer: ViewerViewModel by viewModels()
    private val cloud: CloudViewModel by viewModels()
    private val toolVm: ToolViewModel by viewModels()

    /** A document handed to us by another app, waiting for Compose to start. */
    private var pendingIntentUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingIntentUri = intent?.pdfUri()

        setContent {
            val prefs = (application as PaperViewApp).prefs
            val themeKey by prefs.theme.collectAsState(initial = "system")
            val skin by prefs.skin.collectAsState(initial = "paper")
            val theme = ThemeChoice.from(themeKey)
            val allFilesPref by prefs.allFilesBrowser.collectAsState(initial = false)
            val context = LocalContext.current

            var route by remember { mutableStateOf(Route.Shell) }
            var tab by remember { mutableStateOf(Tab.Library) }
            var recents by remember { mutableStateOf<List<RecentEntry>>(emptyList()) }
            var toolHistory by remember { mutableStateOf<List<ToolId>>(emptyList()) }
            var isDefaultApp by remember { mutableStateOf(false) }
            var pendingTool by remember { mutableStateOf<Tool?>(null) }
            var shelfStats by remember { mutableStateOf(0 to 0L) }
            var scanPages by remember { mutableStateOf<List<Uri>>(emptyList()) }
            var scanSaving by remember { mutableStateOf(false) }
            var scanDone by remember { mutableStateOf(0) }
            val scanFilterKey by prefs.scanFilter.collectAsState(initial = "document")

            suspend fun reloadRecents() {
                recents = Recents.list(prefs).filter { FileAccess.exists(context, it.uri) }
            }
            suspend fun reloadHistory() { toolHistory = ToolHistory.list(prefs) }
            fun reloadShelfStats() { shelfStats = Shelf.stats(context) }

            fun openDoc(source: DocSource, name: String, restore: RecentEntry? = null) {
                lifecycleScope.launch {
                    // Reading a copy counts as using it, so eviction drops the
                    // documents you've stopped coming back to.
                    if (source is DocSource.Local && Shelf.holds(context, source.file)) {
                        withContext(Dispatchers.IO) { Shelf.touch(source.file) }
                    }
                    val remembered = restore ?: Recents.find(prefs, source.key)
                    viewer.open(this@MainActivity, source, name, remembered)
                    Recents.record(
                        prefs,
                        remembered?.copy(name = name, timestamp = System.currentTimeMillis())
                            ?: RecentEntry(source.key, name, 0, "fit_page", 0, System.currentTimeMillis()),
                    )
                    route = Route.Viewer
                }
            }

            /**
             * Opens a document the system handed us, keeping a private copy
             * when Android won't let us read it a second time.
             *
             * The picker's grant can be made permanent, so those documents are
             * read in place and show any edit made to them elsewhere. An intent
             * from a file manager or a chat app brings a grant that dies with
             * this task; that one gets copied, or the document would disappear
             * off the shelf at the next launch.
             */
            fun openIncoming(uri: Uri) {
                lifecycleScope.launch {
                    // Every step here talks to another app's ContentProvider,
                    // which can be slow to wake; none of it belongs on the main
                    // thread.
                    val opened = withContext(Dispatchers.IO) {
                        val name = FileAccess.displayName(context, uri)
                        val source = if (FileAccess.persist(context, uri)) {
                            DocSource.Content(uri)
                        } else {
                            val size = FileAccess.sizeOf(context, uri)
                            // Too large to be worth copying, or the read
                            // failed: the live grant still works this session.
                            Shelf.store(context, uri, size)?.let { DocSource.Local(it) }
                                ?: DocSource.Content(uri)
                        }
                        source to name
                    }
                    openDoc(opened.first, opened.second)
                }
            }

            // ── Pickers ──────────────────────────────────────────────────────

            val openPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                val uri = result.data?.data
                if (result.resultCode == RESULT_OK && uri != null) openIncoming(uri)
            }

            val toolPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                val tool = pendingTool
                if (result.resultCode != RESULT_OK || tool == null) return@rememberLauncherForActivityResult
                val uris = result.data.collectUris()
                if (uris.isEmpty()) return@rememberLauncherForActivityResult
                toolVm.begin(tool, uris)
                route = Route.ToolFlow
            }

            val addMorePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                if (result.resultCode == RESULT_OK) toolVm.addMore(result.data.collectUris())
            }

            val saveAsPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                val dest = result.data?.data
                val src = viewer.source
                if (result.resultCode == RESULT_OK && dest != null && src != null) {
                    toast(if (FileAccess.copyTo(context, src, dest)) "Saved a copy" else "Could not save")
                }
            }

            var savePending by remember { mutableStateOf<File?>(null) }
            val saveResultPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                val dest = result.data?.data
                val source = savePending
                if (result.resultCode == RESULT_OK && dest != null && source != null) {
                    toolVm.saveTo(dest, source) { ok ->
                        toast(if (ok) "Saved" else "Could not save")
                    }
                }
                savePending = null
            }

            fun launchTool(tool: Tool) {
                pendingTool = tool
                val intent = when (tool.input) {
                    ToolInput.SinglePdf -> FileAccess.openDocumentIntent()
                    ToolInput.MultiplePdfs -> FileAccess.openDocumentIntent().apply {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    ToolInput.Images -> FileAccess.openDocumentIntent().apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                toolPicker.launch(intent)
            }

            val startScan = rememberDocumentScanLauncher(
                onScanned = { pages, fallbackPdf ->
                    if (pages.isNotEmpty()) {
                        // Straight to review: the filter is chosen there, with
                        // the result on screen, before anything is committed.
                        scanPages = pages
                        scanDone = 0
                        route = Route.ScanReview
                    } else if (fallbackPdf != null) {
                        // No individual pages to clean; take what we were given.
                        val name = "Scan ${System.currentTimeMillis() / 1000}.pdf"
                        val file = FileAccess.materialize(context, DocSource.Content(fallbackPdf), name)
                        if (file == null) toast("Could not save the scan")
                        else openDoc(DocSource.Local(file), name)
                    }
                },
                onError = { toast(it) },
            )

            // ── Effects ──────────────────────────────────────────────────────

            LaunchedEffect(Unit) {
                // Repairs a shelf written before copies were named after their
                // contents: duplicates of one document collapse into the entry
                // holding the page last read. Runs once, then never again.
                Shelf.consolidate(context, prefs)
                reloadRecents()
                reloadHistory()
                reloadShelfStats()
                isDefaultApp = DefaultApp.isDefault(context)
            }

            LaunchedEffect(pendingIntentUri) {
                pendingIntentUri?.let { uri ->
                    openIncoming(uri)
                    pendingIntentUri = null
                }
            }

            LaunchedEffect(cloud.openRequest) {
                cloud.openRequest?.let { file ->
                    openDoc(DocSource.Local(file), file.name)
                    cloud.openRequest = null
                }
            }

            LaunchedEffect(route, tab) {
                if (route == Route.Shell) {
                    reloadRecents()
                    reloadHistory()
                    reloadShelfStats()
                }
            }

            BackHandler(enabled = route != Route.Shell) {
                when (route) {
                    Route.ScanReview -> if (!scanSaving) { scanPages = emptyList(); route = Route.Shell }
                    Route.ToolFlow -> { toolVm.reset(); route = Route.Shell }
                    Route.Browser -> route = Route.Shell
                    Route.Viewer -> {
                        viewer.persistState()
                        viewer.closeDocument()
                        route = Route.Shell
                    }
                    else -> Unit
                }
            }

            PaperViewTheme(theme, skin) {
                val paper = PaperTheme.colors
                Surface(color = paper.background) {
                    when (route) {
                        Route.Shell -> Scaffold(
                            containerColor = paper.background,
                            bottomBar = {
                                Column {
                                    Hairline()
                                    NavigationBar(
                                        containerColor = paper.background,
                                        tonalElevation = 0.dp,
                                    ) {
                                        Tab.entries.forEach { entry ->
                                            NavigationBarItem(
                                                selected = tab == entry,
                                                onClick = { tab = entry },
                                                icon = {
                                                    Icon(entry.icon, entry.label, Modifier.size(21.dp))
                                                },
                                                label = {
                                                    Text(
                                                        entry.label,
                                                        style = MaterialTheme.typography.labelMedium,
                                                    )
                                                },
                                                colors = NavigationBarItemDefaults.colors(
                                                    selectedIconColor = paper.accent,
                                                    selectedTextColor = paper.accent,
                                                    unselectedIconColor = paper.muted,
                                                    unselectedTextColor = paper.muted,
                                                    // No pill behind the active icon —
                                                    // colour alone marks the tab.
                                                    indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                                ),
                                            )
                                        }
                                    }
                                }
                            },
                        ) { padding ->
                            // The status-bar inset is applied once, here, rather
                            // than by each tab: relying on the Scaffold padding
                            // per-screen let one of them draw under the clock.
                            val tabPadding = androidx.compose.foundation.layout.PaddingValues(
                                bottom = padding.calculateBottomPadding(),
                            )
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .padding(top = padding.calculateTopPadding()),
                            ) {
                                when (tab) {
                                    Tab.Library -> LandingScreen(
                                        recents = recents,
                                        cloud = cloud,
                                        onOpenPicker = {
                                            openPicker.launch(FileAccess.openDocumentIntent())
                                        },
                                        onOpenRecent = { entry ->
                                            openDoc(
                                                FileAccess.sourceFor(entry.uri),
                                                entry.name,
                                                entry,
                                            )
                                        },
                                        onForgetRecent = { entry ->
                                            lifecycleScope.launch {
                                                Recents.remove(prefs, entry.uri)
                                                reloadRecents()
                                            }
                                        },
                                        onOpenLocalFile = { file ->
                                            openDoc(DocSource.Local(file), file.name)
                                        },
                                        onOpenCloudFile = { name, size ->
                                            val cached = B2Client.localPathFor(context, name)
                                            if (cached.exists() && cached.length() > 0) {
                                                openDoc(DocSource.Local(cached), name)
                                            } else {
                                                cloud.download(name, size, openAfter = true)
                                            }
                                        },
                                        onBrowseFiles =
                                            if (allFilesPref && FileAccess.hasAllFilesAccess()) {
                                                { route = Route.Browser }
                                            } else null,
                                        contentPadding = tabPadding,
                                    )

                                    Tab.Scan -> ScanScreen(
                                        onStartScan = startScan,
                                        contentPadding = tabPadding,
                                    )

                                    Tab.Tools -> ToolsScreen(
                                        history = toolHistory,
                                        onPick = ::launchTool,
                                        onClearHistory = {
                                            lifecycleScope.launch {
                                                ToolHistory.clear(prefs)
                                                reloadHistory()
                                            }
                                        },
                                        contentPadding = tabPadding,
                                    )

                                    Tab.Settings -> SettingsScreen(
                                        theme = theme,
                                        onThemeChange = {
                                            lifecycleScope.launch { prefs.setTheme(it.key) }
                                        },
                                        skin = skin,
                                        onSkinChange = {
                                            lifecycleScope.launch { prefs.setSkin(it) }
                                        },
                                        onClearCovers = {
                                            ThumbnailStore.clear(context)
                                            toast("Covers cleared")
                                        },
                                        onClearShelf = {
                                            Shelf.clear(context)
                                            reloadShelfStats()
                                            lifecycleScope.launch { reloadRecents() }
                                            toast("Shelf copies cleared")
                                        },
                                        shelfCount = shelfStats.first,
                                        shelfBytes = shelfStats.second,
                                        cloud = cloud,
                                        allFilesEnabled = allFilesPref,
                                        allFilesGranted = FileAccess.hasAllFilesAccess(),
                                        onAllFilesToggle = { want ->
                                            lifecycleScope.launch {
                                                prefs.setAllFilesBrowser(want)
                                            }
                                            if (want && !FileAccess.hasAllFilesAccess()) {
                                                FileAccess.allFilesSettingsIntent(context)?.let {
                                                    runCatching { startActivity(it) }
                                                }
                                            }
                                        },
                                        isDefaultPdfApp = isDefaultApp,
                                        onMakeDefault = {
                                            if (!DefaultApp.openDefaultsSettings(context)) {
                                                toast("Couldn't open settings on this device")
                                            }
                                        },
                                        defaultAppInstructions = DefaultApp.instructions(),
                                        onClearToolHistory = {
                                            lifecycleScope.launch {
                                                ToolHistory.clear(prefs)
                                                reloadHistory()
                                            }
                                        },
                                        onBack = null,
                                        contentPadding = tabPadding,
                                    )
                                }
                            }
                        }

                        Route.Viewer -> ViewerScreen(
                            vm = viewer,
                            actions = ViewerActions(
                                onHome = {
                                    viewer.persistState()
                                    viewer.closeDocument()
                                    route = Route.Shell
                                },
                                onPrint = { printCurrent() },
                                onShare = { shareCurrent() },
                                onSaveAs = {
                                    saveAsPicker.launch(FileAccess.createDocumentIntent(viewer.title))
                                },
                                onUploadToCloud = { uploadCurrent() },
                                onOpenSettings = { route = Route.Shell; tab = Tab.Settings },
                            ),
                        )

                        Route.Browser -> BrowserScreen(
                            onOpenFile = { file -> openDoc(DocSource.Local(file), file.name) },
                            onBack = { route = Route.Shell },
                        )

                        Route.ScanReview -> ScanReviewScreen(
                            pages = scanPages,
                            filter = ScanFilter.from(scanFilterKey),
                            onFilterChange = { picked ->
                                lifecycleScope.launch { prefs.setScanFilter(picked.key) }
                            },
                            saving = scanSaving,
                            savedPages = scanDone,
                            onDiscard = {
                                scanPages = emptyList()
                                route = Route.Shell
                            },
                            onSave = {
                                val pages = scanPages
                                val filter = ScanFilter.from(scanFilterKey)
                                scanSaving = true
                                scanDone = 0
                                lifecycleScope.launch {
                                    val out = ScanPipeline.outputFile(context)
                                    val built = runCatching {
                                        ScanPipeline.build(context, pages, filter, out) { done, _ ->
                                            scanDone = done
                                        }
                                    }
                                    scanSaving = false
                                    built.onSuccess { file ->
                                        scanPages = emptyList()
                                        openDoc(DocSource.Local(file), file.name)
                                    }.onFailure {
                                        toast(it.message ?: "Could not save the scan")
                                    }
                                }
                            },
                        )

                        Route.ToolFlow -> ToolFlowScreen(
                            vm = toolVm,
                            onBack = { toolVm.reset(); route = Route.Shell },
                            onOpenResult = { file ->
                                toolVm.reset()
                                openDoc(DocSource.Local(file), file.name)
                            },
                            onShareResult = { shareFile(it) },
                            onSaveResult = { file ->
                                savePending = file
                                saveResultPicker.launch(FileAccess.createDocumentIntent(file.name))
                            },
                            onAddMoreFiles = {
                                val tool = toolVm.tool ?: return@ToolFlowScreen
                                addMorePicker.launch(
                                    FileAccess.openDocumentIntent().apply {
                                        if (tool.input == ToolInput.Images) type = "image/*"
                                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntentUri = intent.pdfUri()
    }

    override fun onStop() {
        super.onStop()
        if (viewer.state == DocState.Ready) viewer.persistState()
    }

    private fun currentFile(): File? {
        val src = viewer.source ?: return null
        return FileAccess.materialize(this, src, viewer.title.ifBlank { "document.pdf" })
    }

    private fun printCurrent() {
        val file = currentFile() ?: run { toast("Nothing to print"); return }
        PrintPdf.print(this, file, viewer.title, viewer.pageCount)
    }

    private fun shareCurrent() {
        val file = currentFile() ?: run { toast("Nothing to share"); return }
        shareFile(file)
    }

    private fun shareFile(file: File) {
        if (!file.exists() || file.length() == 0L) {
            toast("That file is empty")
            return
        }
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (t: Throwable) {
            // Almost always a directory missing from res/xml/file_paths.xml.
            // Say so rather than swallowing it into a generic failure.
            android.util.Log.e("PaperView", "share: no FileProvider root for $file", t)
            toast("Can't share from that folder")
            return
        }
        val mime = if (file.extension.equals("pdf", true)) "application/pdf" else "image/*"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            // Some receivers — WhatsApp among them — only pick up the read
            // grant when the URI is in clipData as well as EXTRA_STREAM.
            clipData = android.content.ClipData.newUri(contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share")
        // The chooser forwards the intent, so it needs the grant flag too.
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(chooser)
    }

    private fun uploadCurrent() {
        val file = currentFile() ?: run { toast("Nothing to upload"); return }
        if (!cloud.config.ready) {
            toast("Add your Backblaze details in Settings first")
            return
        }
        cloud.upload(file)
        toast("Uploading to ${cloud.config.bucket}")
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/** The PDF this intent refers to, whether it was a VIEW or a SEND. */
private fun Intent.pdfUri(): Uri? = when (action) {
    Intent.ACTION_VIEW -> data
    Intent.ACTION_SEND -> @Suppress("DEPRECATION") (getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
    else -> null
}

/** Single or multi-select picker results, flattened. */
private fun Intent?.collectUris(): List<Uri> {
    if (this == null) return emptyList()
    clipData?.let { clip ->
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
    return listOfNotNull(data)
}
