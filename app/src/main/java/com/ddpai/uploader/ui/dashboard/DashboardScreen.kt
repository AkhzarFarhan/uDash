package com.ddpai.uploader.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ddpai.uploader.network.NetworkType
import com.ddpai.uploader.ui.theme.MonospaceFamily
import com.ddpai.uploader.ui.theme.WarningAmber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(navController: NavController, vm: DashboardViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "uDash Console",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )

        if (state.needsReauth) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
            ) {
                Text(
                    "YouTube session expired — re-authorize in Config.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        if (state.quotaPausedUntil > System.currentTimeMillis()) {
            val until = remember(state.quotaPausedUntil) {
                java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US)
                    .format(java.util.Date(state.quotaPausedUntil))
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.15f))
            ) {
                Text(
                    "Uploads paused (YouTube quota) until $until.",
                    color = WarningAmber,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Link Status:", style = MaterialTheme.typography.bodyLarge)
                    val (netText, netColor) = when (state.networkType) {
                        NetworkType.DASHCAM_AP -> Pair("DASHCAM AP (193.168.0.1)", MaterialTheme.colorScheme.primary)
                        NetworkType.HOME_WIFI -> Pair("HOME INTERNET", MaterialTheme.colorScheme.primary)
                        NetworkType.OTHER -> Pair("OTHER WI-FI / NETWORK", MaterialTheme.colorScheme.error)
                        NetworkType.NONE -> Pair("DISCONNECTED", MaterialTheme.colorScheme.error)
                    }
                    Text(netText, color = netColor, style = MaterialTheme.typography.bodyLarge)
                }

                state.progress?.let { progress ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (progress.kind == "download") "Downloading: ${progress.fileName}" else "Uploading: ${progress.fileName}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (progress.total > 0) {
                        val pct = progress.current.toFloat() / progress.total.toFloat()
                        LinearProgressIndicator(
                            progress = { pct },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = String.format(
                                java.util.Locale.US,
                                "%.1f MB / %.1f MB (%.1f%%)",
                                progress.current / 1024f / 1024f,
                                progress.total / 1024f / 1024f,
                                pct * 100
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.End)
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                } ?: run {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    Text(
                        text = "Pipeline Action: IDLE",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Counters Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CounterItem(label = "Discovered", count = state.discovered, modifier = Modifier.weight(1f))
            CounterItem(label = "Downloaded", count = state.downloaded, modifier = Modifier.weight(1f))
            CounterItem(label = "Uploaded", count = state.uploaded, modifier = Modifier.weight(1f))
            CounterItem(label = "Failed", count = state.failed, modifier = Modifier.weight(1f))
        }

        // Quick Actions Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Quick Actions",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { vm.scanNow() },
                        enabled = state.networkType == NetworkType.DASHCAM_AP,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Scan Dashcam")
                    }

                    Button(
                        onClick = { vm.uploadNow() },
                        enabled = state.networkType == NetworkType.HOME_WIFI && state.isAuthorized,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Start Upload")
                    }
                }

                if (!state.isConfigured || !state.isAuthorized) {
                    Text(
                        text = "YouTube connection or Config needs attention.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }

        // Mini Log Console
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clickable { navController.navigate("logs") }
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "System Logs",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Tap to expand",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(state.recentLogs) { log ->
                        val color = when (log.level) {
                            "ERROR" -> MaterialTheme.colorScheme.error
                            "WARN" -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = "[${log.level}] ${log.tag}: ${log.message}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonospaceFamily),
                            color = color
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CounterItem(label: String, count: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
