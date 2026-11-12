package dev.rustcraft.launcher

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private data class ModHit(
    val id: String,
    val title: String,
    val description: String,
    val downloads: Long
)

@Composable
fun ModManagerDialog(
    minecraftVersion: String,
    loader: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modsDir = remember {
        File(context.filesDir, "minecraft/mods").apply { mkdirs() }
    }
    var tab by remember { mutableStateOf("Мои моды") }
    var mods by remember { mutableStateOf(emptyList<File>()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<ModHit>()) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        mods = modsDir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".jar", true) || it.name.endsWith(".jar.disabled", true)) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    LaunchedEffect(Unit) { refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val displayName = context.contentResolver.query(
                        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "mod.jar"
                    require(displayName.endsWith(".jar", true)) { "Выбери файл .jar" }
                    val safeName = displayName.substringAfterLast('/')
                        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    val destination = File(modsDir, safeName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Не удалось открыть файл")
                }
                message = "Мод добавлен"
                refresh()
            } catch (e: Exception) {
                message = e.message ?: "Ошибка добавления"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Моды · $minecraftVersion") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(tab == "Мои моды", { tab = "Мои моды" }, label = { Text("Мои моды") })
                    FilterChip(tab == "Каталог", { tab = "Каталог" }, label = { Text("Каталог") })
                }

                if (tab == "Мои моды") {
                    Button(
                        onClick = { picker.launch(arrayOf("application/java-archive", "application/octet-stream")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("＋ Добавить мод с телефона") }

                    if (mods.isEmpty()) {
                        Text("Модов пока нет", style = MaterialTheme.typography.titleMedium)
                        Text("Добавь .jar-файл с телефона или найди мод в каталоге.")
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(mods, key = { it.name }) { file ->
                                val disabled = file.name.endsWith(".disabled")
                                Card {
                                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                        Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(if (disabled) "Выключен" else "Включён",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(onClick = {
                                                val target = File(
                                                    modsDir,
                                                    if (disabled) file.name.removeSuffix(".disabled") else file.name + ".disabled"
                                                )
                                                if (file.renameTo(target)) refresh()
                                                else message = "Не удалось изменить файл"
                                            }) { Text(if (disabled) "Включить" else "Выключить") }
                                            TextButton(onClick = {
                                                if (file.delete()) refresh() else message = "Не удалось удалить файл"
                                            }) { Text("Удалить") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text("Каталог Modrinth · $loader", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("Название мода") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                message = ""
                                try {
                                    results = withContext(Dispatchers.IO) {
                                        val q = java.net.URLEncoder.encode(query, "UTF-8")
                                        val facetsJson = """[["project_type:mod"],["versions:$minecraftVersion"],["categories:$loader"]]"""
                                        val facets = java.net.URLEncoder.encode(facetsJson, "UTF-8")
                                        val json = JSONObject(httpGetText(
                                            "https://api.modrinth.com/v2/search?query=$q&limit=20&facets=$facets"
                                        ))
                                        val hits = json.optJSONArray("hits") ?: JSONArray()
                                        List(hits.length()) { i ->
                                            hits.getJSONObject(i).let {
                                                ModHit(
                                                    it.getString("project_id"),
                                                    it.optString("title"),
                                                    it.optString("description"),
                                                    it.optLong("downloads")
                                                )
                                            }
                                        }
                                    }
                                    if (results.isEmpty()) message = "Ничего не найдено. Попробуй другое название."
                                } catch (e: Exception) {
                                    message = "Ошибка каталога: ${e.message}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && query.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Найти моды") }

                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(results, key = { it.id }) { hit ->
                            Card {
                                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                    Text(hit.title, style = MaterialTheme.typography.titleSmall)
                                    Text(hit.description, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                                    Text("Загрузок: ${hit.downloads}")
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            scope.launch {
                                                busy = true
                                                try {
                                                    val installedName = withContext(Dispatchers.IO) {
                                                        val loaders = java.net.URLEncoder.encode("""["$loader"]""", "UTF-8")
                                                        val games = java.net.URLEncoder.encode("""["$minecraftVersion"]""", "UTF-8")
                                                        val versions = JSONArray(httpGetText(
                                                            "https://api.modrinth.com/v2/project/${hit.id}/version?loaders=$loaders&game_versions=$games"
                                                        ))
                                                        if (versions.length() == 0) {
                                                            error("Нет версии для выбранных Minecraft и загрузчика")
                                                        }
                                                        val files = versions.getJSONObject(0).getJSONArray("files")
                                                        var selected: JSONObject? = null
                                                        for (i in 0 until files.length()) {
                                                            if (files.getJSONObject(i).optBoolean("primary")) {
                                                                selected = files.getJSONObject(i)
                                                                break
                                                            }
                                                        }
                                                        val item = selected ?: files.getJSONObject(0)
                                                        val fileName = item.getString("filename")
                                                        require(fileName.endsWith(".jar", true)) { "Каталог вернул не .jar файл" }
                                                        val destination = File(modsDir, fileName)
                                                        val connection = URL(item.getString("url")).openConnection() as HttpURLConnection
                                                        connection.connectTimeout = 15_000
                                                        connection.readTimeout = 60_000
                                                        try {
                                                            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
                                                            connection.inputStream.use { input ->
                                                                destination.outputStream().use { output -> input.copyTo(output) }
                                                            }
                                                        } finally {
                                                            connection.disconnect()
                                                        }
                                                        fileName
                                                    }
                                                    message = "Установлен: $installedName"
                                                    refresh()
                                                    tab = "Мои моды"
                                                } catch (e: Exception) {
                                                    message = e.message ?: "Ошибка установки"
                                                } finally {
                                                    busy = false
                                                }
                                            }
                                        }
                                    ) { Text("Установить") }
                                }
                            }
                        }
                    }
                }
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } }
    )
}
