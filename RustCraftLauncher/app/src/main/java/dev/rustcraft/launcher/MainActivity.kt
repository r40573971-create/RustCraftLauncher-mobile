package dev.rustcraft.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.concurrent.thread

private const val MANIFEST_URL =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

private data class VersionInfo(val id: String, val url: String, val kind: String = "release")

// Запасной список на случай, если нет сети или Rust-библиотека не загрузилась.
private val FALLBACK_VERSIONS = listOf(
    "1.21.8", "1.21.7", "1.21.6", "1.21.5", "1.21.4", "1.21.3", "1.21.1", "1.21",
    "1.20.6", "1.20.4", "1.20.2", "1.20.1", "1.20",
    "1.19.4", "1.19.3", "1.19.2", "1.19",
    "1.18.2", "1.18.1", "1.18",
    "1.17.1", "1.17",
    "1.16.5", "1.16.4", "1.16.1",
    "1.15.2", "1.14.4", "1.13.2", "1.12.2",
    "1.8.9", "1.7.10"
)

/** Разбор манифеста выполняет Rust. Возвращает null при любой ошибке. */
private fun parseWithRust(json: String, includeSnapshots: Boolean): List<VersionInfo>? {
    val response = JSONObject(RustBridge.parseVersions(json, includeSnapshots))
    if (!response.optBoolean("ok")) return null
    val array = response.getJSONArray("versions")
    return List(array.length()) { index ->
        val item = array.getJSONObject(index)
        VersionInfo(item.getString("id"), item.getString("url"), item.optString("kind", "release"))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RustCraftApp() }
    }
}

@Composable
private fun RustCraftApp() {
    val nativeStatus = remember { RustBridge.status() }
    var selectedVersion by remember { mutableStateOf("1.21.8") }
    var selectedLoader by remember { mutableStateOf("Vanilla") }
    var showSnapshots by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var installFilter by remember { mutableStateOf("Release") }
    var installLoader by remember { mutableStateOf("Vanilla") }

    var manifestJson by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        thread {
            try {
                manifestJson = httpGetText(MANIFEST_URL)
            } catch (_: Exception) {
                loadFailed = true
            }
        }
    }

    val rustVersions: List<VersionInfo>? = remember(manifestJson, showSnapshots) {
        val json = manifestJson
        if (json != null && RustBridge.isLoaded) {
            runCatching { parseWithRust(json, showSnapshots) }.getOrNull()
        } else {
            null
        }
    }
    val versionIds = rustVersions?.map { it.id } ?: FALLBACK_VERSIONS
    // Для окна установки читаем типы версий непосредственно из официального манифеста Mojang.
    val allManifestVersions = remember(manifestJson) {
        runCatching {
            val array = JSONObject(manifestJson ?: "{}").optJSONArray("versions")
            if (array == null) emptyList() else List(array.length()) { i ->
                val item = array.getJSONObject(i)
                VersionInfo(item.getString("id"), item.getString("url"), item.optString("type", "release"))
            }
        }.getOrDefault(emptyList())
    }
    val installVersions = when (installFilter) {
        "Snapshot" -> allManifestVersions.filter { it.kind == "snapshot" }
        "Old Beta" -> allManifestVersions.filter { it.kind == "old_beta" }
        "Old Alpha" -> allManifestVersions.filter { it.kind == "old_alpha" }
        else -> allManifestVersions.filter { it.kind == "release" }
    }
    val installVersionIds = installVersions.map { it.id }.ifEmpty { versionIds }
    val selectedUrl = rustVersions?.firstOrNull { it.id == selectedVersion }?.url
    val installVersionUrl = installVersions.firstOrNull { it.id == selectedVersion }?.url
        ?: allManifestVersions.firstOrNull { it.id == selectedVersion }?.url
        ?: selectedUrl

    val versionsStatus = when {
        rustVersions != null -> "${versionIds.size} версий с серверов Mojang (разбор в Rust)"
        loadFailed -> "нет сети — встроенный список (${versionIds.size})"
        manifestJson != null -> "Rust не загружен — встроенный список (${versionIds.size})"
        else -> "загрузка списка версий…"
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text("RUSTCRAFT", style = MaterialTheme.typography.headlineMedium)
                    Text("Minecraft Java launcher · Android", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    ProfilesCard(
                        currentVersion = selectedVersion,
                        currentLoader = selectedLoader,
                        onSelect = { profile ->
                            selectedVersion = profile.minecraftVersion
                            selectedLoader = profile.loader
                        }
                    )
                }
                item {
                    Button(
                        onClick = { showInstallDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 18.dp)
                    ) { Text("Скачать", style = MaterialTheme.typography.titleMedium) }
                }
                item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Устройство", style = MaterialTheme.typography.titleLarge)
                            Text("ARM64 · Android")
                            Text("Native core: $nativeStatus")
                        }
                    }
                }
                item {
                    Button(
                        onClick = { /* Запуск игры появится после M3 (Java) и M4 (графика). */ },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false
                    ) { Text("Запуск — следующий этап разработки") }
                    Text(
                        "Сейчас можно скачать файлы игры. Java и графика для запуска ещё не подключены.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (showInstallDialog) {
            InstallDialog(
                versions = installVersionIds,
                selectedVersion = selectedVersion,
                selectedLoader = installLoader,
                filter = installFilter,
                versionUrl = installVersionUrl,
                onVersionChange = { selectedVersion = it },
                onLoaderChange = { installLoader = it },
                onFilterChange = { type ->
                    installFilter = type
                    val kind = when (type) {
                        "Snapshot" -> "snapshot"
                        "Old Beta" -> "old_beta"
                        "Old Alpha" -> "old_alpha"
                        else -> "release"
                    }
                    allManifestVersions.firstOrNull { it.kind == kind }?.let { selectedVersion = it.id }
                },
                onDismiss = { showInstallDialog = false }
            )
        }
    }
}

@Composable
private fun InstallDialog(
    versions: List<String>, selectedVersion: String, selectedLoader: String,
    filter: String, versionUrl: String?, onVersionChange: (String) -> Unit,
    onLoaderChange: (String) -> Unit, onFilterChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 14.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Vortex Launcher", style = MaterialTheme.typography.titleLarge)
                            Text("Загрузки", style = MaterialTheme.typography.headlineMedium)
                            Text("Создай новую игровую установку", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onDismiss) { Text("Закрыть") }
                    }
                }
                item {
                    Text("Версия Minecraft", style = MaterialTheme.typography.titleMedium)
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selectedVersion, modifier = Modifier.weight(1f))
                            Text("⌄")
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            versions.take(100).forEach { v ->
                                DropdownMenuItem(text = { Text(v) }, onClick = { onVersionChange(v); expanded = false })
                            }
                        }
                    }
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Release", "Snapshot", "Old Beta", "Old Alpha").forEach { type ->
                            FilterChip(selected = filter == type, onClick = { onFilterChange(type) }, label = { Text(type) })
                        }
                    }
                }
                item { Text("Тип установки", style = MaterialTheme.typography.titleLarge) }
                item {
                    val loaders = listOf("Vanilla", "Forge", "NeoForge", "Fabric", "Quilt")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        loaders.chunked(2).forEach { rowLoaders ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowLoaders.forEach { loader ->
                                    val description = when (loader) {
                                        "Vanilla" -> "Без модов"
                                        "Forge" -> "Моды Forge"
                                        "NeoForge" -> "Моды NeoForge"
                                        "Fabric" -> "Моды Fabric"
                                        else -> "Моды Quilt"
                                    }
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        onClick = { onLoaderChange(loader) },
                                        border = androidx.compose.foundation.BorderStroke(
                                            if (selectedLoader == loader) 2.dp else 1.dp,
                                            if (selectedLoader == loader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                        ),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (selectedLoader == loader) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                        )
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(loader, style = MaterialTheme.typography.titleLarge)
                                            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                if (rowLoaders.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                item {
                    Text("Версия загрузчика", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false) {
                        Text(if (selectedLoader == "Vanilla") "Не требуется" else "Последняя совместимая — каталог будет добавлен")
                    }
                }
                item {
                    if (selectedLoader == "Vanilla") {
                        DownloadCard(versionId = selectedVersion, versionUrl = versionUrl, showCard = false, buttonLabel = "Скачать и установить")
                    } else {
                        Text("Выбор $selectedLoader уже доступен. Установка этого загрузчика будет добавлена следующим этапом; сейчас скачивание доступно для Vanilla.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Скачать и установить") }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionSelector(versions: List<String>, value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(value) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            versions.forEach { v ->
                DropdownMenuItem(text = { Text(v) }, onClick = {
                    onChange(v); expanded = false
                })
            }
        }
    }
}

@Composable
private fun LoaderSelector(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(value) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("Vanilla", "NeoForge", "Forge", "Fabric").forEach { loader ->
                DropdownMenuItem(text = { Text(loader) }, onClick = {
                    onChange(loader); expanded = false
                })
            }
        }
    }
}
