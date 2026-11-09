package dev.rustcraft.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private const val MANIFEST_URL =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

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

private fun downloadManifest(): String {
    val connection = URL(MANIFEST_URL).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    try {
        if (connection.responseCode != 200) {
            throw IllegalStateException("HTTP ${connection.responseCode}")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

/** Разбор манифеста выполняет Rust. Возвращает null при любой ошибке. */
private fun parseWithRust(json: String, includeSnapshots: Boolean): List<String>? {
    val response = JSONObject(RustBridge.parseVersions(json, includeSnapshots))
    if (!response.optBoolean("ok")) return null
    val array = response.getJSONArray("versions")
    return List(array.length()) { index -> array.getJSONObject(index).getString("id") }
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

    var manifestJson by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        thread {
            try {
                manifestJson = downloadManifest()
            } catch (_: Exception) {
                loadFailed = true
            }
        }
    }

    val rustVersions: List<String>? = remember(manifestJson, showSnapshots) {
        val json = manifestJson
        if (json != null && RustBridge.isLoaded) {
            runCatching { parseWithRust(json, showSnapshots) }.getOrNull()
        } else {
            null
        }
    }
    val versions = rustVersions ?: FALLBACK_VERSIONS

    val versionsStatus = when {
        rustVersions != null -> "${versions.size} версий с серверов Mojang (разбор в Rust)"
        loadFailed -> "нет сети — встроенный список (${versions.size})"
        manifestJson != null -> "Rust не загружен — встроенный список (${versions.size})"
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
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Игровой профиль", style = MaterialTheme.typography.titleLarge)
                            Text("Версия Minecraft")
                            VersionSelector(versions, selectedVersion) { selectedVersion = it }
                            Text(
                                versionsStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = showSnapshots, onCheckedChange = { showSnapshots = it })
                                Spacer(Modifier.width(8.dp))
                                Text("Показывать снапшоты")
                            }
                            Text("Загрузчик модов")
                            LoaderSelector(selectedLoader) { selectedLoader = it }
                        }
                    }
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
                        onClick = { /* Actual launch is intentionally not wired in milestone 0.1. */ },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false
                    ) { Text("Запуск — следующий этап разработки") }
                    Text(
                        "Пока это интерфейс, список версий и проверка Rust/JNI. Игровой runtime ещё не подключён.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
