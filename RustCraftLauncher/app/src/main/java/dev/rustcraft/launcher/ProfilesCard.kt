package dev.rustcraft.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Карточка «Профили». Временный простой вид: вся логика в ProfileStore,
 * так что внешний вид можно полностью заменить своим.
 *
 * Новый профиль берёт версию и загрузчик с главного экрана.
 * [onSelect] вызывается, когда пользователь выбирает сохранённый профиль.
 */
@Composable
fun ProfilesCard(
    currentVersion: String,
    currentLoader: String,
    onSelect: (Profile) -> Unit
) {
    val context = LocalContext.current
    val store = remember { ProfileStore(File(context.filesDir, "profiles.json")) }
    val initial = remember { store.load() }

    var profiles by remember { mutableStateOf(initial.profiles) }
    var message by remember { mutableStateOf(initial.error ?: "") }
    var name by remember { mutableStateOf("") }
    var memory by remember { mutableStateOf(4096f) }
    var renderDistance by remember { mutableStateOf(12f) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Профили", style = MaterialTheme.typography.titleLarge)

            if (profiles.isEmpty()) {
                Text(
                    "Пока нет профилей. Выбери версию и загрузчик выше, придумай имя и сохрани.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            profiles.forEach { profile ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.id)
                        Text(
                            "${profile.minecraftVersion} · ${profile.loader} · " +
                                "${profile.memoryMb} МБ · Java ${profile.javaMajor}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onSelect(profile) }) { Text("Выбрать") }
                    TextButton(onClick = {
                        val result = store.delete(profile.id)
                        profiles = result.profiles
                        message = result.error ?: "Удалён: ${profile.id}"
                    }) { Text("Удалить") }
                }
            }

            HorizontalDivider()

            Text("Новый профиль: $currentVersion · $currentLoader")
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Имя профиля") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Память: ${memory.toInt()} МБ")
            Slider(
                value = memory,
                onValueChange = { memory = it },
                valueRange = 1024f..8192f,
                steps = 13 // шаг 512 МБ
            )
            Text("Дальность прорисовки: ${renderDistance.toInt()} чанков")
            Slider(
                value = renderDistance,
                onValueChange = { renderDistance = it },
                valueRange = 2f..32f,
                steps = 29 // шаг 1 чанк
            )
            Button(
                enabled = RustBridge.isLoaded && name.isNotBlank(),
                onClick = {
                    val profile = Profile(
                        id = name.trim(),
                        minecraftVersion = currentVersion,
                        loader = currentLoader,
                        loaderVersion = null,
                        javaMajor = RustBridge.suggestJava(currentVersion),
                        memoryMb = memory.toInt(),
                        renderDistance = renderDistance.toInt()
                    )
                    val result = store.upsert(profile)
                    profiles = result.profiles
                    message = result.error ?: "Сохранено: ${profile.id}"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить профиль") }

            if (message.isNotEmpty()) Text(message)
        }
    }
}
