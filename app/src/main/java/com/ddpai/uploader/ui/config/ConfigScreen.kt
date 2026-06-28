package com.ddpai.uploader.ui.config

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ddpai.uploader.data.config.AppConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(vm: ConfigViewModel = viewModel()) {
    val configState by vm.config.collectAsState()
    val isAuthorized by vm.isAuthorized.collectAsState()
    val testStatus by vm.testStatus.collectAsState()

    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var gateway by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf("private") }
    var deleteAfterUpload by remember { mutableStateOf(true) }
    var wifiAutoStart by remember { mutableStateOf(true) }
    var maxRetries by remember { mutableStateOf(5) }

    var clientIdVisible by remember { mutableStateOf(false) }
    var clientSecretVisible by remember { mutableStateOf(false) }
    var privacyExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(configState) {
        clientId = configState.youtubeClientId
        clientSecret = configState.youtubeClientSecret
        gateway = configState.dashcamGateway
        privacy = configState.uploadPrivacy
        deleteAfterUpload = configState.deleteAfterUpload
        wifiAutoStart = configState.wifiAutoStart
        maxRetries = configState.maxRetries
    }

    val oauthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.let { intent ->
            vm.handleAuthResponse(intent)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Console Configuration",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )

        // Configuration Status Banner
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (clientId.isNotBlank() && isAuthorized)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (clientId.isNotBlank() && isAuthorized) "Status: CONFIGURATION READY ✓" else "Status: INCOMPLETE CONFIGURATION ✗",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (clientId.isNotBlank() && isAuthorized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (isAuthorized) "YouTube OAuth: Authorized" else "YouTube OAuth: Unauthorized",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Credentials Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "YouTube API Credentials",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = clientId,
                    onValueChange = { clientId = it },
                    label = { Text("Client ID") },
                    visualTransformation = if (clientIdVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { clientIdVisible = !clientIdVisible }) {
                            Icon(
                                imageVector = if (clientIdVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = "Toggle Client ID visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = clientSecret,
                    onValueChange = { clientSecret = it },
                    label = { Text("Client Secret (Optional if using PKCE)") },
                    visualTransformation = if (clientSecretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { clientSecretVisible = !clientSecretVisible }) {
                            Icon(
                                imageVector = if (clientSecretVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = "Toggle Client Secret visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    ExposedDropdownMenuBox(
                        expanded = privacyExpanded,
                        onExpandedChange = { privacyExpanded = !privacyExpanded }
                    ) {
                        OutlinedTextField(
                            value = privacy.replaceFirstChar { it.uppercase() },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Upload Privacy") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = privacyExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = privacyExpanded,
                            onDismissRequest = { privacyExpanded = false }
                        ) {
                            listOf("private", "unlisted", "public").forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.replaceFirstChar { it.uppercase() }) },
                                    onClick = {
                                        privacy = p
                                        privacyExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Connection Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Connection Settings",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = gateway,
                    onValueChange = { gateway = it },
                    label = { Text("Dashcam Gateway IP") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Delete Local File after Upload")
                    Switch(
                        checked = deleteAfterUpload,
                        onCheckedChange = { deleteAfterUpload = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Auto-Start pipeline on Wi-Fi")
                    Switch(
                        checked = wifiAutoStart,
                        onCheckedChange = { wifiAutoStart = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Max Retry Attempts (${maxRetries})")
                    Slider(
                        value = maxRetries.toFloat(),
                        onValueChange = { maxRetries = it.toInt() },
                        valueRange = 1f..10f,
                        steps = 8,
                        modifier = Modifier.width(150.dp)
                    )
                }
            }
        }

        // Actions Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    vm.save(
                        AppConfig(
                            youtubeClientId = clientId,
                            youtubeClientSecret = clientSecret,
                            uploadPrivacy = privacy,
                            dashcamGateway = gateway,
                            deleteAfterUpload = deleteAfterUpload,
                            wifiAutoStart = wifiAutoStart,
                            maxRetries = maxRetries
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save")
            }

            if (!isAuthorized) {
                Button(
                    onClick = {
                        if (clientId.isNotBlank()) {
                            val intent = vm.auth.buildAuthIntent()
                            oauthLauncher.launch(intent)
                        }
                    },
                    enabled = clientId.isNotBlank(),
                    modifier = Modifier.weight(1.5f)
                ) {
                    Text("Authorize YouTube")
                }
            } else {
                Button(
                    onClick = { vm.signOut() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1.5f)
                ) {
                    Text("Sign Out")
                }
            }
        }

        // Test Connection Button
        Button(
            onClick = { vm.testConnection() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Test Dashcam Connection")
        }

        testStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.startsWith("Success")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        // Google Cloud setup guide
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Google Cloud Setup Instructions",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "1. Create a project at console.cloud.google.com.\n" +
                            "2. Enable YouTube Data API v3.\n" +
                            "3. Configure OAuth consent screen: External type, add your email as a test user, add scope: .../auth/youtube.upload.\n" +
                            "4. Create OAuth client ID -> type: Web or Desktop application.\n" +
                            "5. Copy Client ID here, set redirect URI in Google Console to: com.ddpai.uploader:/oauth2redirect.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
