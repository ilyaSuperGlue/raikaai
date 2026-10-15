package com.raikaai.app.ui.environment

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
                        if (settings.alpineInstalled) "Alpine ${settings.alpineVersion} · /workspace" else "Alpine via PRoot (tanpa root)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        text = when {
                            servedPort != null -> "Serving :$servedPort"
                            settings.alpineInstalled -> "Ready"
                            else -> "Belum terpasang"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (settings.alpineInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            if (!settings.alpineInstalled) {
                NotInstalledCard(
                    installState = installState,
                    onInstall = { manager.install() },
                    onCancel = { manager.cancelInstall() },
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
                TerminalTab(
                    manager = manager,
                    servedPort = servedPort,
                    onCommandDone = { filesTick++ },
                )
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
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Linux Environment belum terpasang", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Download Alpine minirootfs + proot (±5 MB). Tidak termasuk di APK, bisa dihapus kapan saja.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val st = installState) {
                is ContainerManager.InstallState.Downloading -> {
                    Text("Mengunduh ${st.label}… ${(st.progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = onCancel) { Text("Batal") }
                }
                is ContainerManager.InstallState.Extracting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Mengekstrak ${st.label}…", style = MaterialTheme.typography.labelSmall)
                    }
                }
                is ContainerManager.InstallState.Error -> {
                    Text("Gagal: ${st.message}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onInstall) { Text("Coba Lagi") }
                }
                else -> {
                    Button(onClick = onInstall) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Install Alpine")
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalTab(
    manager: ContainerManager,
    servedPort: Int?,
    onCommandDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var servePortInput by remember { mutableStateOf("8081") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    fun exec(cmd: String) {
        val clean = cmd.trim()
        if (clean.isEmpty() || running) return
        running = true
        lines.add("$ $clean")
        input = ""
        scope.launch(Dispatchers.IO) {
            val res = manager.run(clean, timeoutSec = 60)
            withContext(Dispatchers.Main) {
                if (res.stdout.isNotBlank()) lines.add(res.stdout.take(3000))
                if (res.stderr.isNotBlank()) lines.add("[stderr] ${res.stderr.take(1000)}")
                lines.add("[exit=${res.exitCode}${if (res.timedOut) " timed out" else ""}]")
                running = false
                onCommandDone()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            if (lines.isEmpty()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Terminal /workspace — ketik perintah non-interaktif (ls, cat, python3, ffmpeg).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Contoh: ls -la /workspace",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    items(lines.size) { i ->
                        Text(
                            lines[i],
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("perintah…") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { exec(input) }, enabled = !running) {
                Icon(Icons.Default.Send, contentDescription = "Run")
            }
        }
        Spacer(Modifier.height(8.dp))
        // Serve row (Fase 4)
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = servePortInput,
                    onValueChange = { servePortInput = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.width(110.dp),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (servedPort != null) {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$servedPort")))
                    }) {
                        Text("Buka :$servedPort", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { scope.launch(Dispatchers.IO) { manager.stopServe() } }) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop server")
                    }
                } else {
                    IconButton(onClick = {
                        val p = servePortInput.toIntOrNull() ?: 8081
                        scope.launch(Dispatchers.IO) { manager.serve(p) }
                    }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Serve /workspace")
                    }
                }
            }
        }
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
    var files by remember { mutableStateOf(listOf<File>()) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val list = manager.workspaceFiles()
            withContext(Dispatchers.Main) { files = list }
        }
    }
    LaunchedEffect(key) { refresh() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val name = sanitizeFileName(queryDisplayName(context, uri) ?: "import_${System.currentTimeMillis()}")
                val dest = File(manager.workspaceDir, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) { refresh() }
            } catch (_: Throwable) {}
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("/workspace (${files.size} file)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import") }
        }
        Spacer(Modifier.height(4.dp))
        if (files.isEmpty()) {
            Text(
                "Kosong. Import file atau minta AI menyimpan hasil ke /workspace.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.absolutePath }) { f ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (isTextFile(f)) {
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
                                    }.getOrNull() ?: "(tidak bisa dibaca)"
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
                                    "${formatBytes(f.length())} · ${java.text.SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(java.util.Date(f.lastModified()))}",
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
                                Icon(Icons.Default.Send, contentDescription = "Kirim ke Chat", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    f.delete()
                                    withContext(Dispatchers.Main) { refresh() }
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
                    previewText ?: "Memuat…",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { previewFile = null; previewText = null }) { Text("Tutup") }
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
    val ext = f.extension.lowercase()
    return ext in setOf("txt", "md", "csv", "json", "xml", "yml", "yaml", "log", "py", "js", "ts", "sh", "kt", "java", "html", "css", "toml", "ini", "conf", "rc") || f.length() < 200_000 && ext.isEmpty()
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
        context.startActivity(Intent.createChooser(intent, "Buka ${f.name}"))
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
        context.startActivity(Intent.createChooser(intent, "Bagikan ${f.name}"))
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
