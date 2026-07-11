package com.ddpai.uploader.ui.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ddpai.uploader.data.db.entity.VideoFileEntity
import com.ddpai.uploader.data.model.FileStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(navController: NavController, vm: FileListViewModel = viewModel()) {
    val files by vm.files.collectAsState()
    var fileToDelete by remember { mutableStateOf<String?>(null) }

    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete Local File") },
            text = { Text("Are you sure you want to delete the downloaded video file for ${fileToDelete}? If it has not been uploaded, it will need to be downloaded again.") },
            confirmButton = {
                TextButton(onClick = {
                    fileToDelete?.let { vm.deleteLocal(it) }
                    fileToDelete = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Video Files Queue",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No files discovered yet. Connect to dashcam AP.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(files) { file ->
                    VideoFileCard(
                        file = file,
                        onPlay = { navController.navigate("player/${file.fileName}") },
                        onRetry = { vm.retryFile(file.fileName) },
                        onDeleteLocal = { fileToDelete = file.fileName }
                    )
                }
            }
        }
    }
}

@Composable
fun VideoFileCard(
    file: VideoFileEntity,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onDeleteLocal: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val dateStr = if (file.capturedAtEpoch > 0) dateFormat.format(Date(file.capturedAtEpoch)) else "Unknown"

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(file.fileName, style = MaterialTheme.typography.bodyLarge)
                StatusChip(status = file.status)
            }

            Text("Captured At: $dateStr", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (file.sizeBytes > 0) {
                Text(
                    text = String.format(Locale.US, "Size: %.1f MB", file.sizeBytes / 1024f / 1024f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (file.status == FileStatus.DOWNLOADING.name || file.status == FileStatus.UPLOADING.name) {
                val pct = if (file.sizeBytes > 0) file.downloadedBytes.toFloat() / file.sizeBytes.toFloat() else 0f
                LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
            }

            file.errorMessage?.let { error ->
                Text("Error: $error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (file.localPath != null) {
                    TextButton(onClick = onPlay) {
                        Text("Play")
                    }
                    TextButton(onClick = onDeleteLocal) {
                        Text("Delete Local", color = MaterialTheme.colorScheme.error)
                    }
                }
                if (file.status == FileStatus.FAILED.name) {
                    Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
fun StatusChip(status: String) {
    val (color, text) = when (status) {
        "DISCOVERED" -> Pair(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), "Discovered")
        "DOWNLOADING" -> Pair(MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), "Downloading")
        "DOWNLOADED" -> Pair(MaterialTheme.colorScheme.primary, "Downloaded")
        "UPLOADING" -> Pair(MaterialTheme.colorScheme.secondary, "Uploading")
        "UPLOADED" -> Pair(MaterialTheme.colorScheme.primary, "Uploaded")
        "PENDING" -> Pair(MaterialTheme.colorScheme.error.copy(alpha = 0.6f), "Pending")
        "FAILED" -> Pair(MaterialTheme.colorScheme.error, "Failed")
        else -> Pair(MaterialTheme.colorScheme.onSurfaceVariant, status)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.2f)),
        border = BorderStroke(1.dp, color),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
