package dev.rustcraft.launcher

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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

@Composable
fun PerformanceCard(
    minecraftVersion: String,
    loader: String,
    summary: PerformanceSummary?
) {
    val context = LocalContext.current
    val totalRam = remember { deviceTotalRamMb(context) }
    val javaLine = remember(minecraftVersion) {
        runCatching {
            val major = RuntimeCatalog.requiredJavaMajor(minecraftVersion)
            val label = when (JavaRuntimeManager.stateFor(context, major)) {
                JavaRuntimeManager.State.INSTALLED -> "установлена"
                JavaRuntimeManager.State.NOT_INSTALLED -> "не установлена"
                JavaRuntimeManager.State.NOT_CONFIGURED -> "источник не настроен"
            }
            "Java $major: $label"
        }.getOrNull()
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Производительность", style = MaterialTheme.typography.titleLarge)
            Text("Профиль: $minecraftVersion · $loader")
            Text("ОЗУ устройства: $totalRam МБ")
            if (javaLine != null) Text(javaLine)
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
