package com.raikaai.app.ui.environment

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.raikaai.app.core.container.ContainerManager
import com.raikaai.app.ui.chat.ChatViewModel
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated Linux Environment screen (Fase 3–4): manual Terminal + /workspace Files.
 * Terminal is shell-in-a-box over [ContainerManager.run] (no PTY yet);
 * interactive programs (vim/htop) are out of scope for this milestone.
 */
@Composable
fun EnvironmentScreen(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit = {},
    onNavigateToChat: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val manager = remember { viewModel.containerManager }
    val installState by manager.installState.collectAsState()
    val servedPort by manager.servedPort.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var filesTick by remember { mutableIntStateOf(0) }
    val outdated = remember(settings.alpineInstalled, installState) { manager.isOutdated() }
    val fsInstalled = remember(settings.alpineInstalled, installState) { manager.isInstalled() }
    val scope = rememberCoroutineScope()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenDrawer, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Menu, contentDescription = "Open Menu")
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Linux Environment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = servedPort?.let { "Serving :$it · /workspace" }
                            ?: if (fsInstalled) "Alpine ${settings.alpineVersion} · /workspace" else "Alpine via PRoot (no root)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val text = manager.diagnostics()
                            withContext(Dispatchers.Main) {
                                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("env-diag", text))
                                android.widget.Toast.makeText(context, "Diagnostics copied", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Default.Info, contentDescription = "Copy diagnostics")
                }
            }

            if (!fsInstalled || outdated) {
                NotInstalledCard(
                    installState = installState,
                    onInstall = { manager.install() },
                    onCancel = { manager.cancelInstall() },
                    title = if (outdated) "Environment update available" else "Linux Environment not installed",
                    desc = if (outdated) {
                        "The on-device rootfs uses an old format. Press Update to reinstall it (downloads ~3 MB, /workspace is kept)."
                    } else {
                        "Downloads the Alpine minirootfs (~3 MB). proot ships inside the app and can be removed anytime."
                    },
                    actionLabel = if (outdated) "Update Alpine" else "Install Alpine",
                )
                return@Column
            }

            SecondaryTabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Terminal", fontWeight = FontWeight.SemiBold)
                    }
                })
                Tab(selected = tab == 1, onClick = { tab = 1; filesTick++ }, text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Files", fontWeight = FontWeight.SemiBold)
                    }
                })
            }

            if (tab == 0) {
                TerminalTab(manager = manager)
            } else {
                FilesTab(
                    key = filesTick,
                    manager = manager,
                    viewModel = viewModel,
                    onNavigateToChat = onNavigateToChat,
                )
            }
        }
    }
}

@Composable
private fun NotInstalledCard(
    installState: ContainerManager.InstallState,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    title: String = "Linux Environment not installed",
    desc: String = "Downloads the Alpine minirootfs (~3 MB). proot ships inside the app and can be removed anytime.",
    actionLabel: String = "Install Alpine",
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val st = installState) {
                is ContainerManager.InstallState.Downloading -> {
                    Text("Downloading ${st.label}… ${(st.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                is ContainerManager.InstallState.Extracting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Extracting ${st.label}…", style = MaterialTheme.typography.labelSmall)
                    }
                }
                is ContainerManager.InstallState.Error -> {
                    Text("Failed: ${st.message}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onInstall) { Text("Retry") }
                }
                else -> {
                    Button(onClick = onInstall) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalTab(
    manager: ContainerManager,
) {
    val controller = remember { PtyController(manager) }
    DisposableEffect(Unit) { onDispose { controller.finish() } }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PtyTerminalView(controller = controller, modifier = Modifier.fillMaxSize())
                val exited = controller.session?.exited == true
                if (exited) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Shell exited.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { controller.restart() }) {
                            Text("Restart shell")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // Termux-style extra keys in three compact rows (all fit without clipping).
        // CTRL/ALT/SHIFT are sticky: tap one, then tap the target key (or type a letter).
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            termKey("ESC") { controller.write("\u001B") }
            termKey("TAB") { controller.write("\t") }
            termKey("CTRL", active = controller.ctrlActive) {
                val on = !controller.ctrlActive
                controller.clearModifiers()
                controller.ctrlActive = on
            }
            termKey("ALT", active = controller.altActive) {
                val on = !controller.altActive
                controller.clearModifiers()
                controller.altActive = on
            }
            termKey("SHIFT", active = controller.shiftActive) {
                val on = !controller.shiftActive
                controller.clearModifiers()
                controller.shiftActive = on
            }
            termKey("↑") { controller.write("\u001B[A") }
            termKey("↓") { controller.write("\u001B[B") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            termKey("←") { controller.write("\u001B[C") }
            termKey("→") { controller.write("\u001B[D") }
            termKey("END") { controller.write("\u001B[F") }
            termKey("PGUP") { controller.pageUp() }
            termKey("PGDN") { controller.pageDown() }
            termKey("/") { controller.write("/") }
            termKey("|") { controller.write("|") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            termKey("~") { controller.write("~") }
            termKey("-") { controller.write("-") }
            termKey("_") { controller.write("_") }
            termKey(".") { controller.write(".") }
            termKey("*") { controller.write("*") }
            termKey("$") { controller.write("$") }
            termKey("\"") { controller.write("\"") }
            termKey("'") { controller.write("'") }
        }
    }
}
@Composable
private fun termKey(label: String, active: Boolean = false, onTap: () -> Unit) {
    TextButton(
        onClick = onTap,
        modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 32.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun FilesTab(
    key: Int,
    manager: ContainerManager,
    viewModel: ChatViewModel,
    onNavigateToChat: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootDir = remember { manager.workspaceDir }
    var currentDir by remember { mutableStateOf(manager.workspaceDir) }
    var files by remember { mutableStateOf(listOf<File>()) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val list = manager.workspaceFiles(currentDir)
            withContext(Dispatchers.Main) { files = list }
        }
    }
    LaunchedEffect(key, currentDir) { refresh() }

    val relPath = remember(currentDir) {
        runCatching {
            val rel = File(currentDir.canonicalPath.removePrefix(rootDir.canonicalPath)).path
                .removePrefix(File.separator)
            if (rel.isBlank()) "/workspace" else "/workspace/$rel"
        }.getOrDefault("/workspace")
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val name = sanitizeFileName(queryDisplayName(context, uri) ?: "import_${System.currentTimeMillis()}")
                val dest = File(currentDir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) {
                    toast(context, "Imported $name")
                    refresh()
                }
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) { toast(context, "Import failed") }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$relPath (${files.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import") }
        }
        Spacer(Modifier.height(4.dp))
        if (currentDir != rootDir && isWithinRoot(rootDir, currentDir)) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                onClick = {
                    val parent = currentDir.parentFile
                    if (parent != null && isWithinRoot(rootDir, parent)) currentDir = parent else currentDir = rootDir
                },
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("↑", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(".. (up)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (files.isEmpty()) {
            Text(
                "This folder is empty. Import a file or ask the AI to save results here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.absolutePath }) { f ->
                    val isDir = f.isDirectory
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (isDir) {
                                if (isWithinRoot(rootDir, f)) currentDir = f
                            } else if (isTextFile(f)) {
                                scope.launch(Dispatchers.IO) {
                                    val text = runCatching {
                                        f.bufferedReader().use { r ->
                                            val sb = StringBuilder()
                                            var c: Int
                                            var n = 0
                                            while (r.read().also { c = it } != -1 && n < 100_000) {
                                                sb.append(c.toChar())
                                                n++
                                            }
                                            sb.toString()
                                        }
                                    }.getOrNull() ?: "(unreadable)"
                                    withContext(Dispatchers.Main) {
                                        previewFile = f
                                        previewText = text
                                    }
                                }
                            } else {
                                openWith(context, f)
                            }
                        },
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isTextFile(f)) Icons.Default.InsertDriveFile else Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = if (isDir) {
                                        "Folder · ${java.text.SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(java.util.Date(f.lastModified()))}"
                                    } else {
                                        "${formatBytes(f.length())} · ${java.text.SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(java.util.Date(f.lastModified()))}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { shareFile(context, f) }) {
                                Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    saveToDownload(context, f)
                                    withContext(Dispatchers.Main) { refresh() }
                                }
                            }) {
                                Icon(Icons.Default.CloudDownload, contentDescription = "Save", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                val uri = fileUri(context, f)
                                viewModel.addAttachments(listOf(uri))
                                onNavigateToChat()
                            }) {
                                Icon(Icons.Default.Send, contentDescription = "Send to Chat", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val ok = runCatching {
                                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                                    }.getOrDefault(false)
                                    withContext(Dispatchers.Main) {
                                        toast(context, if (ok) "Deleted ${f.name}" else "Delete failed")
                                        refresh()
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (previewFile != null) {
        AlertDialog(
            onDismissRequest = { previewFile = null; previewText = null },
            title = { Text(previewFile!!.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(
                    previewText ?: "Loading…",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { previewFile = null; previewText = null }) { Text("Close") }
            },
        )
    }
}

// ── helpers ───────────────────────────────────────────────────────────

private fun sanitizeFileName(name: String): String {
    val clean = name.substringAfterLast('/').take(128).trim()
    if (clean.isEmpty() || clean == "." || clean == "..") return "import_${System.currentTimeMillis()}"
    return clean.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

private fun isTextFile(f: File): Boolean {
    if (f.isDirectory) return false
    val ext = f.extension.lowercase()
    return ext in setOf("txt", "md", "csv", "json", "xml", "yml", "yaml", "log", "py", "js", "ts", "sh", "kt", "java", "html", "css", "toml", "ini", "conf", "rc") || f.length() < 200_000 && ext.isEmpty()
}

/** True when [f] lives inside [root] (prevents escaping /workspace). */
private fun isWithinRoot(root: File, f: File): Boolean {
    return runCatching {
        f.canonicalPath == root.canonicalPath || f.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }.getOrDefault(false)
}

private fun toast(context: Context, msg: String) {
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}

private fun formatBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val kb = n / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024)
}

private fun fileUri(context: Context, f: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)

private fun openWith(context: Context, f: File) {
    runCatching {
        val uri = fileUri(context, f)
        val mime = java.net.URLConnection.guessContentTypeFromName(f.name) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open ${f.name}"))
    }
}

private fun shareFile(context: Context, f: File) {
    runCatching {
        val uri = fileUri(context, f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = java.net.URLConnection.guessContentTypeFromName(f.name) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${f.name}"))
    }
}

private fun saveToDownload(context: Context, f: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = android.content.ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, java.net.URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RaikaAI")
            put(MediaStore.MediaColumns.SIZE, f.length())
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return
        resolver.openOutputStream(itemUri)?.use { out ->
            f.inputStream().use { input -> input.copyTo(out) }
        }
    } else {
        @Suppress("DEPRECATION")
        val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RaikaAI/${f.name}")
        dest.parentFile?.mkdirs()
        f.copyTo(dest, overwrite = true)
        // Make it visible to gallery/file managers.
        context.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dest)))
    }
}
