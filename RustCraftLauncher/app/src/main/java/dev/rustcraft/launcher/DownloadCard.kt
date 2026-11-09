package dev.rustcraft.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.concurrent.thread

/**
 * Карточка «Загрузка игры». Это временный простой вид: вся логика в GameDownloader,
 * так что внешний вид можно полностью заменить своим.
 */
@Composable
fun DownloadCard(versionId: String, versionUrl: String?, showCard: Boolean = true, buttonLabel: String = "Скачать $versionId") {
    val context = LocalContext.current
    var progress by remember { mutableStateOf<DownloadProgress?>(null) }
    var status by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var downloader by remember { mutableStateOf<GameDownloader?>(null) }

    @Composable
    fun Content() {
        Column(Modifier.padding(if (showCard) 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Загрузка игры", style = MaterialTheme.typography.titleLarge)
            Text(
                "Версия $versionId скачается в память приложения. Это может занять " +
                    "несколько сотен МБ, лучше по Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            progress?.let { p ->
                if (p.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { p.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${p.doneFiles}/${p.totalFiles} файлов · ${mb(p.doneBytes)} / ${mb(p.totalBytes)} МБ")
                } else {
                    Text(p.message)
                }
            }
            if (status.isNotEmpty()) Text(status)
            if (versionUrl == null) {
                Text(
                    "Нужен интернет: список версий ещё не загружен с серверов Mojang.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !running && versionUrl != null && RustBridge.isLoaded,
                    onClick = {
                        val url = versionUrl ?: return@Button
                        val game = GameDownloader(File(context.filesDir, "minecraft"))
                        downloader = game
                        running = true
                        status = ""
                        progress = null
                        thread {
                            try {
                                val result = game.download(url) { p -> progress = p }
                                status = "Готово: ${result.id} (нужна Java ${result.javaMajor})"
                            } catch (_: DownloadCancelled) {
                                status = "Отменено"
                            } catch (e: Exception) {
                                status = "Ошибка: ${e.message}"
                            } finally {
                                running = false
                            }
                        }
                    }
                ) { Text(buttonLabel) }

                if (running) {
                    OutlinedButton(onClick = { downloader?.cancel() }) { Text("Отмена") }
                }
            }
        }
    }
    if (showCard) Card { Content() } else Content()
}

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)
