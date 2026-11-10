package dev.rustcraft.launcher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class DownloadUiState(
    val progress: DownloadProgress? = null,
    val status: String = "",
    val running: Boolean = false
)

class DownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    @Volatile private var downloader: GameDownloader? = null

    fun start(versionUrl: String) {
        if (_state.value.running) return
        val game = GameDownloader(File(getApplication<Application>().filesDir, "minecraft"))
        downloader = game
        _state.value = DownloadUiState(running = true)

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    game.download(versionUrl) { update ->
                        _state.value = _state.value.copy(progress = update)
                    }
                }
                _state.value = DownloadUiState(
                    status = "Готово: ${result.id} (нужна Java ${result.javaMajor})"
                )
            } catch (_: DownloadCancelled) {
                _state.value = DownloadUiState(status = "Отменено")
            } catch (e: Exception) {
                _state.value = DownloadUiState(status = "Ошибка: ${e.message ?: "неизвестная ошибка"}")
            } finally {
                downloader = null
            }
        }
    }

    fun cancel() { downloader?.cancel() }
}
