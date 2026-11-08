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
            listOf("1.21.1", "1.20.1", "1.19.2", "1.18.2", "1.16.5").forEach { v ->
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
