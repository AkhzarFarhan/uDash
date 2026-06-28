package com.ddpai.uploader.ui.logs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ddpai.uploader.ui.theme.MonospaceFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogConsoleScreen(vm: LogConsoleViewModel = viewModel()) {
    val logs by vm.logs.collectAsState()
    val filterLevel by vm.filterLevel.collectAsState()
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    val levels = listOf(null, "DEBUG", "INFO", "WARN", "ERROR")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Developer Log Console",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )

        // Filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            levels.forEach { level ->
                FilterChip(
                    selected = filterLevel == level,
                    onClick = { vm.setFilterLevel(level) },
                    label = { Text(level ?: "ALL") }
                )
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { vm.copyLogsToClipboard() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Copy Logs")
            }

            Button(
                onClick = { vm.clearLogs() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f)
            ) {
                Text("Clear Logs")
            }
        }

        // Monospace Log Console View
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Box(modifier = Modifier.padding(12.dp).fillMaxSize()) {
                if (logs.isEmpty()) {
                    Text(
                        text = "Console empty.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs) { log ->
                            val color = when (log.level) {
                                "ERROR" -> MaterialTheme.colorScheme.error
                                "WARN" -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                "INFO" -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val dateStr = dateFormat.format(Date(log.timestamp))
                            Text(
                                text = "$dateStr [${log.level}] ${log.tag}: ${log.message}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonospaceFamily),
                                color = color
                            )
                        }
                    }
                }
            }
        }
    }
}
