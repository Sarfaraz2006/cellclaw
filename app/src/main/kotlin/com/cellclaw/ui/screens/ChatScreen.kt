package com.cellclaw.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cellclaw.approval.ApprovalResult
import com.cellclaw.service.overlay.OverlayService
import com.cellclaw.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToApprovals: () -> Unit,
    onNavigateToSetup: (() -> Unit)? = null,
    initialMessage: String? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val agentState by viewModel.agentState.collectAsState()
    val pendingApprovals by viewModel.pendingApprovals.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()

    // Auto-send initial message from intent (e.g. adb)
    LaunchedEffect(initialMessage) {
        if (!initialMessage.isNullOrBlank()) {
            viewModel.sendMessage(initialMessage)
        }
    }

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showModelDropdown by remember { mutableStateOf(false) }
    var showRestrictedSettingsDialog by remember { mutableStateOf(false) }

    // Poll accessibility service status via system API (works cross-process)
    var accessibilityConnected by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val am = context.getSystemService(AccessibilityManager::class.java)
        while (true) {
            val enabled = am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            accessibilityConnected = enabled.any {
                it.id?.contains("com.cellclaw") == true
            }
            delay(2_000)
        }
    }

    // Overlay permission state
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            overlayGranted = Settings.canDrawOverlays(context)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp)
            ) {
                // Drawer Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "APEX AI",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Gemini 3.8 Flash • OmniRoute",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // "New Chat" Prominent Button
                    FilledTonalButton(
                        onClick = {
                            scope.launch { drawerState.close() }
                            viewModel.startNewChat()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New Chat", fontWeight = FontWeight.SemiBold)
                    }
                }

                HorizontalDivider()

                // Navigation Items
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "AI & Capabilities",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onNavigateToSettings()
                        }
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.Star, contentDescription = null) },
                        label = { Text("Skills & Plugins") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onNavigateToSkills()
                        }
                    )

                    NavigationDrawerItem(
                        icon = {
                            if (pendingApprovals.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text("${pendingApprovals.size}") } }) {
                                    Icon(Icons.Default.Shield, contentDescription = null)
                                }
                            } else {
                                Icon(Icons.Default.Shield, contentDescription = null)
                            }
                        },
                        label = { Text("Approvals & Security") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            onNavigateToApprovals()
                        }
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        "Deep Android Integration",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                Icons.Default.Build,
                                contentDescription = null,
                                tint = if (accessibilityConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        },
                        label = {
                            Column {
                                Text("Restricted Settings Guide")
                                Text(
                                    if (accessibilityConnected) "Accessibility Active" else "Fix 'Uninstall for all users'",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (accessibilityConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            showRestrictedSettingsDialog = true
                        }
                    )

                    NavigationDrawerItem(
                        icon = { Icon(Icons.Default.BatteryChargingFull, contentDescription = null) },
                        label = {
                            Column {
                                Text("Background Battery Survival")
                                Text("Exempt from OEM task killing", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            requestIgnoreBatteryOptimization(context)
                        }
                    )

                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                Icons.Default.Layers,
                                contentDescription = null,
                                tint = if (overlayGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        label = {
                            Column {
                                Text("Floating Bubble Overlay")
                                Text(if (overlayGranted) "Active" else "Tap to grant permission", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (!overlayGranted) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } else {
                                context.startForegroundService(Intent(context, OverlayService::class.java))
                            }
                        }
                    )

                    if (onNavigateToSetup != null) {
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.Info, contentDescription = null) },
                            label = { Text("Setup Wizard") },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                onNavigateToSetup()
                            }
                        )
                    }
                }

                // Drawer Footer
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        "APEX AI v0.3.2 (Build 5)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Server: 91.99.52.110:20128",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open Drawer")
                        }
                    },
                    title = {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showModelDropdown = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = currentModel.ifBlank { "gemini-3.8-flash-high" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Switch model",
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showModelDropdown,
                                onDismissRequest = { showModelDropdown = false }
                            ) {
                                val availableModels = viewModel.getAvailableModels()
                                availableModels.forEach { modelOption ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = modelOption,
                                                    fontWeight = if (modelOption == currentModel) FontWeight.Bold else FontWeight.Normal
                                                )
                                                if (modelOption == currentModel) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = "Active",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.setModel(modelOption)
                                            showModelDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        if (pendingApprovals.isNotEmpty()) {
                            BadgedBox(badge = {
                                Badge { Text("${pendingApprovals.size}") }
                            }) {
                                IconButton(onClick = onNavigateToApprovals) {
                                    Icon(Icons.Default.Shield, "Approvals")
                                }
                            }
                        }
                        IconButton(onClick = { viewModel.startNewChat() }) {
                            Icon(Icons.Default.EditNote, "New Chat")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Restricted settings warning card when accessibility is not connected
                if (!accessibilityConnected) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Accessibility service disabled",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "ColorOS / Android 13+ restricted setting",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                )
                            }
                            TextButton(
                                onClick = { showRestrictedSettingsDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("Fix Issue")
                            }
                            Button(
                                onClick = { openAccessibilitySettings(context) },
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("Enable")
                            }
                        }
                    }
                }

                // Empty state or messages list
                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "What can I help with today?",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Powered by Gemini 3.8 Flash • OmniRoute",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(Modifier.height(28.dp))

                            // ChatGPT-style suggestion prompt cards
                            val suggestions = listOf(
                                "📱 Automate an app on my phone",
                                "🔍 Summarize what's on my screen",
                                "⚙️ Check Android permissions & overlay",
                                "✨ Ask Gemini 3.8 Flash anything"
                            )

                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                suggestions.forEach { prompt ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                inputText = prompt
                                            }
                                    ) {
                                        Text(
                                            text = prompt,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Messages list
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            Box(modifier = Modifier.animateItem()) {
                                ChatBubble(message = message)
                            }
                        }
                    }

                    // Auto-scroll on new messages
                    LaunchedEffect(messages.size) {
                        if (messages.isNotEmpty()) {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }
                }

                // Thinking overlay
                val thinkingText by viewModel.thinkingText.collectAsState()
                val isAgentRunning = agentState != "idle"
                var thinkingExpanded by remember { mutableStateOf(false) }

                AnimatedVisibility(
                    visible = isAgentRunning,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (agentState != "waiting_approval") {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Shield,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = when (agentState) {
                                        "thinking" -> "Thinking..."
                                        "executing_tools" -> "Running tools..."
                                        "waiting_approval" -> "Approval needed"
                                        "paused" -> "Paused"
                                        else -> agentState
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (agentState == "waiting_approval")
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.stopAgent() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Stop",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Inline approval card
                            if (agentState == "waiting_approval" && pendingApprovals.isNotEmpty()) {
                                val request = pendingApprovals.first()
                                Spacer(Modifier.height(6.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "Allow ${request.toolName}?",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Text(
                                            text = request.parameters.toString().take(120),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            OutlinedButton(
                                                onClick = { viewModel.respondToApproval(request.id, ApprovalResult.DENIED) },
                                                modifier = Modifier.weight(1f),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("Deny", style = MaterialTheme.typography.labelSmall)
                                            }
                                            Button(
                                                onClick = { viewModel.respondToApproval(request.id, ApprovalResult.APPROVED) },
                                                modifier = Modifier.weight(1f),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("Approve", style = MaterialTheme.typography.labelSmall)
                                            }
                                            TextButton(
                                                onClick = { viewModel.respondToApproval(request.id, ApprovalResult.ALWAYS_ALLOW) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("Always", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }

                            // Thinking text preview
                            if (thinkingText != null && agentState != "waiting_approval") {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = thinkingText ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (thinkingExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                        .clickable { thinkingExpanded = !thinkingExpanded }
                                        .padding(8.dp)
                                        .then(
                                            if (thinkingExpanded) Modifier
                                                .heightIn(max = 200.dp)
                                                .verticalScroll(rememberScrollState())
                                            else Modifier
                                        )
                                )
                            }
                        }
                    }
                }

                // ChatGPT-style Input bar
                val isListening by viewModel.isListening.collectAsState()
                val voiceEnabled = viewModel.voiceEnabled

                val micPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        viewModel.startVoiceInput()
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            if (voiceEnabled) {
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (isListening) {
                                            viewModel.stopVoiceInput()
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                ) {
                                    Icon(
                                        if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                                        contentDescription = if (isListening) "Stop voice" else "Voice input",
                                        tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            TextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier.weight(1f),
                                placeholder = {
                                    Text(
                                        if (isAgentRunning) "Send instructions..." else "Message APEX AI...",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                maxLines = 5
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    FilledIconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.sendMessage(inputText.trim())
                                inputText = ""
                                scope.launch {
                                    if (messages.isNotEmpty()) {
                                        listState.animateScrollToItem(messages.size - 1)
                                    }
                                }
                            }
                        },
                        enabled = inputText.isNotBlank(),
                        shape = CircleShape,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    // ColorOS / Android 13+ Restricted Settings Interactive Helper Dialog
    if (showRestrictedSettingsDialog) {
        RestrictedSettingsDialog(
            onDismiss = { showRestrictedSettingsDialog = false },
            onOpenAccessibility = { openAccessibilitySettings(context) },
            onOpenAppInfo = { openAppInfo(context) }
        )
    }
}

@Composable
fun RestrictedSettingsDialog(
    onDismiss: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Fix Restricted Setting", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Why 'Uninstall for all users' appears:\nOn ColorOS & Android 13+, Android temporarily hides 'Allow restricted settings' until you first attempt to toggle APEX AI in Accessibility.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                // Step 1
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Step 1: Trigger the OS Dialog", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Tap button below -> Find 'APEX AI' -> Try to turn toggle ON -> When 'Restricted setting' popup appears, tap OK.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onOpenAccessibility,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("1. Open Accessibility Settings")
                        }
                    }
                }

                // Step 2
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Step 2: Allow Restricted Settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Tap button below -> In top-right corner, tap 3 dots (⋮) -> Tap 'Allow restricted settings' -> Enter PIN/Fingerprint.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onOpenAppInfo,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("2. Open App Info (3 Dots ⋮)")
                        }
                    }
                }

                // Step 3
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Step 3: Enable the Service", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Return to Accessibility Settings and turn APEX AI toggle ON. It is now unlocked!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onOpenAccessibility,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("3. Turn APEX AI ON")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        Color(0xFF131826)
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
    }

    val clipboardManager = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(bubbleShape)
                .then(
                    if (!isUser) Modifier.border(0.75.dp, Color(0xFF2A364F), bubbleShape)
                    else Modifier
                )
                .background(bgColor)
                .clickable {
                    clipboardManager.setText(AnnotatedString(message.content))
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (message.toolName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = if (isUser) textColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = message.toolName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUser) textColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

data class ChatMessage(
    val id: Long,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
