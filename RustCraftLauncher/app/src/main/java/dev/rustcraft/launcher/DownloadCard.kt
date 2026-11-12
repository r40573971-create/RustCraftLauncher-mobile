package dev.rustcraft.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DownloadCard(
    versionId: String,
    versionUrl: String?,
    fabricLoaderVersion: String? = null,
    showCard: Boolean = true,
    buttonLabel: String = "Скачать $versionId"
) {
    val downloadViewModel: DownloadViewModel = viewModel()
    val state by downloadViewModel.state.collectAsState()
    val fabricChosen = fabricLoaderVersion != null
    val canStart = !state.running && versionUrl != null &&
        RustBridge.isLoaded && (!fabricChosen || !fabricLoaderVersion.isNullOrBlank())

    @Composable
    fun Content() {
        Column(
            Modifier.padding(if (showCard) 16.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (fabricChosen) "Установка Fabric" else "Загрузка игры",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                if (fabricChosen) "Minecraft $versionId · Fabric Loader $fabricLoaderVersion"
                else "Версия $versionId скачается в память приложения. Это может занять несколько сотен МБ, лучше по Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.progress?.let { progress ->
                if (progress.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${progress.doneFiles}/${progress.totalFiles} файлов · ${mb(progress.doneBytes)} / ${mb(progress.totalBytes)} МБ")
                } else Text(progress.message)
            }
            if (state.status.isNotEmpty()) Text(state.status)
            if (versionUrl == null) {
                Text(
                    "Нужен интернет: список версий ещё не загружен с серверов Mojang.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (fabricChosen && fabricLoaderVersion.isNullOrBlank()) {
                Text(
                    "Сначала дождись списка версий Fabric и выбери версию загрузчика.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = canStart,
                    onClick = {
                        versionUrl?.let { url ->
                            downloadViewModel.start(url, fabricLoaderVersion)
                        }
                    }
                ) { Text(buttonLabel) }
                if (state.running) {
                    OutlinedButton(onClick = downloadViewModel::cancel) { Text("Отмена") }
                }
            }
        }
    }

    if (showCard) Card { Content() } else Content()
}

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)
