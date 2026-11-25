package dev.rustcraft.launcher

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Plain data for the UI. Filled in MainActivity from LaunchPreparation.
 */
data class PerformanceSummary(
    val recommendedRamMb: Int?,
    val jvmArgs: List<String>,
    val problems: List<String>
)

fun deviceTotalRamMb(context: Context): Int {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val info = ActivityManager.MemoryInfo()
    am.getMemoryInfo(info)
    return (info.totalMem / (1024L * 1024L)).toInt()
}

private fun mbFmt(bytes: Long): String = "%.1f".format(bytes / 1_048_576.0)

@Composable
fun PerformanceCard(
    minecraftVersion: String,
    loader: String,
    summary: PerformanceSummary?
) {
    val context = LocalContext.current
    val totalRam = remember { deviceTotalRamMb(context) }
    val installVm: JavaInstallViewModel = viewModel()
    val installState by installVm.state.collectAsState()

    val javaMajor = remember(minecraftVersion) {
        runCatching { RuntimeCatalog.requiredJavaMajor(minecraftVersion) }.getOrNull()
    }
    val javaState = remember(javaMajor, installState.running, installState.status) {
        javaMajor?.let { JavaRuntimeManager.stateFor(context, it) }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Производительность", style = MaterialTheme.typography.titleLarge)
            Text("Профиль: $minecraftVersion · $loader")
            Text("ОЗУ устройства: $totalRam МБ")

            if (javaMajor != null && javaState != null) {
                val label = when (javaState) {
                    JavaRuntimeManager.State.INSTALLED -> "установлена"
                    JavaRuntimeManager.State.NOT_INSTALLED -> "не установлена"
                    JavaRuntimeManager.State.NOT_CONFIGURED -> "источник не настроен"
                }
                Text("Java $javaMajor: $label")

                installState.progress?.let { progress ->
                    if (progress.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${mbFmt(progress.doneBytes)} / ${mbFmt(progress.totalBytes)} МБ")
                    } else Text(progress.message)
                }
                if (installState.status.isNotEmpty()) Text(installState.status)

                if (javaState == JavaRuntimeManager.State.NOT_INSTALLED || installState.running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !installState.running,
                            onClick = { installVm.install(javaMajor) }
                        ) { Text("Установить Java $javaMajor") }
                        if (installState.running) {
                            OutlinedButton(onClick = installVm::cancel) { Text("Отмена") }
                        }
                    }
                }
            }

            if (summary == null) {
                Text(
                    "Расчёт параметров ещё не подключён.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("Рекомендуемая память: ${summary.recommendedRamMb?.let { "$it МБ" } ?: "—"}")
                Text("JVM-аргументов: ${summary.jvmArgs.size}")
                if (summary.problems.isEmpty()) {
                    Text("Проверка профиля: замечаний нет")
                } else {
                    summary.problems.forEach {
                        Text("• $it", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
