package dev.rustcraft.launcher

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class JavaInstallViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    @Volatile private var installer: JavaRuntimeInstaller? = null

    fun install(javaMajor: Int) {
        if (_state.value.running) return
        val job = JavaRuntimeInstaller(File(getApplication<Application>().filesDir, "runtimes"))
        installer = job
        _state.value = DownloadUiState(running = true)

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    job.install(javaMajor) { update ->
                        _state.value = _state.value.copy(progress = update)
                    }
                }
                _state.value = DownloadUiState(status = "Java $javaMajor установлена")
            } catch (_: DownloadCancelled) {
                _state.value = DownloadUiState(status = "Отменено")
            } catch (e: Exception) {
                _state.value = DownloadUiState(status = "Ошибка: ${e.message ?: "неизвестная ошибка"}")
            } finally {
                installer = null
            }
        }
    }

    fun cancel() { installer?.cancel() }
}
