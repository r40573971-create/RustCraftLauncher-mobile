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

// Временный статический список. В M1 его заменит официальный манифест версий Mojang.
private val MINECRAFT_VERSIONS = listOf(
    // 1.21.x
    "1.21.8", "1.21.7", "1.21.6", "1.21.5", "1.21.4", "1.21.3", "1.21.1", "1.21",
    // 1.20.x
    "1.20.6", "1.20.4", "1.20.2", "1.20.1", "1.20",
    // 1.19.x
    "1.19.4", "1.19.3", "1.19.2", "1.19",
    // 1.18.x
    "1.18.2", "1.18.1", "1.18",
    // 1.17.x
    "1.17.1", "1.17",
    // 1.16.x
    "1.16.5", "1.16.4", "1.16.1",
    // 1.15.x – 1.12.x
    "1.15.2", "1.14.4", "1.13.2", "1.12.2",
    // старые популярные
    "1.8.9", "1.7.10"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RustCraftApp() }
    }
}

@Composable
private fun RustCraftApp() {
    val nativeStatus = remember { RustBridge.status() }
    var selectedVersion by remember { mutableStateOf("1.21.1") }
    var selectedLoader by remember { mutableStateOf("NeoForge") }

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
                            VersionSelector(selectedVersion) { selectedVersion = it }
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
                        "Пока это интерфейс и проверка Rust/JNI. Игровой runtime ещё не подключён.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionSelector(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(value) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MINECRAFT_VERSIONS.forEach { v ->
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
