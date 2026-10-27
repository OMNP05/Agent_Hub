package dev.agenthub.app.chat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.repeatOnLifecycle
import dev.agenthub.app.voice.VoiceEffect
import dev.agenthub.app.voice.VoiceUiState
import dev.agenthub.app.voice.VoiceViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agenthub.app.R
import dev.agenthub.app.core.model.Conversation
import dev.agenthub.app.core.ui.components.EmptyState
import dev.agenthub.app.core.ui.components.ErrorState
import dev.agenthub.app.core.ui.components.SkeletonList
import dev.agenthub.app.core.ui.messageRes

@Composable
fun ChatScreen(
    startListening: Boolean,
    onBack: () -> Unit,
    onOpenAgent: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
    voiceViewModel: VoiceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val voice by voiceViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voiceViewModel.onMicTap() else voiceViewModel.onPermissionDenied()
    }
    val onMic: () -> Unit = {
        if (voice.isListening) {
            voiceViewModel.stopListening()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceViewModel.onMicTap()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    // Opened from the mini-bar mic: start listening once, not again after rotation.
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startListening) {
        if (startListening && !autoStarted) {
            autoStarted = true
            onMic()
        }
    }
    LaunchedEffect(voiceViewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            voiceViewModel.effects.collect { effect ->
                when (effect) {
                    is VoiceEffect.Transcript -> viewModel.onTranscript(effect.text)
                }
            }
        }
    }
    // Read the reply aloud only when this run was started from a voice draft; stop when leaving.
    val running = state.isRunning
    LaunchedEffect(running) {
        if (!running && state.replyByVoice) viewModel.lastAssistantText()?.let(voiceViewModel::speak)
    }
    DisposableEffect(Unit) {
        onDispose {
            voiceViewModel.stopSpeaking()
            voiceViewModel.stopListening()
        }
    }
    LaunchedEffect(voice.error) {
        voice.error?.let {
            Toast.makeText(context, it.messageRes(), Toast.LENGTH_LONG).show()
            voiceViewModel.onErrorShown()
        }
    }

    LaunchedEffect(state.actionError) {
        state.actionError?.let {
            Toast.makeText(context, it.messageRes(), Toast.LENGTH_LONG).show()
            viewModel.onActionErrorShown()
        }
    }

    ChatContent(
        state = state,
        voice = voice,
        onMic = onMic,
        onLanguage = voiceViewModel::onLanguageToggle,
        onBack = onBack,
        onOpenAgent = onOpenAgent,
        onInputChange = viewModel::onInputChange,
        onSend = viewModel::onSend,
        onStop = viewModel::onStop,
        onNewChat = viewModel::onNewChat,
        onRetry = viewModel::onRetry,
        onHistoryToggle = viewModel::onHistoryToggle,
        onSelectConversation = viewModel::onSelectConversation,
        onDeleteConversation = viewModel::onDeleteConversation,
        onClearRequest = viewModel::onClearHistoryRequest,
        onClearDismiss = viewModel::onClearHistoryDismiss,
        onClearConfirm = viewModel::onClearHistoryConfirm,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    state: ChatUiState,
    voice: VoiceUiState,
    onMic: () -> Unit,
    onLanguage: () -> Unit,
    onBack: () -> Unit,
    onOpenAgent: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onRetry: () -> Unit,
    onHistoryToggle: (Boolean) -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onClearRequest: () -> Unit,
    onClearDismiss: () -> Unit,
    onClearConfirm: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.agentName.ifBlank { stringResource(R.string.chat_title_fallback) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_back))
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat, enabled = !state.isRunning && state.items.isNotEmpty()) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chat_new))
                    }
                    IconButton(onClick = { onHistoryToggle(true) }) {
                        Icon(Icons.Filled.History, contentDescription = stringResource(R.string.chat_history))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.chat_open_agent))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_open_agent)) },
                                onClick = { menuOpen = false; onOpenAgent() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_clear_history)) },
                                enabled = !state.isRunning,
                                onClick = { menuOpen = false; onClearRequest() },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { Composer(state, voice, onMic, onLanguage, onInputChange, onSend, onStop) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading && state.items.isEmpty() -> SkeletonList()
                state.loadError != null -> ErrorState(state.loadError, onRetry = onRetry)
                state.isEmpty -> EmptyState(
                    icon = Icons.Outlined.Chat,
                    title = stringResource(R.string.chat_empty_title),
                    message = stringResource(R.string.chat_empty_message),
                )
                else -> MessageList(state.items)
            }
        }
    }

    if (state.showHistory) {
        ModalBottomSheet(onDismissRequest = { onHistoryToggle(false) }) {
            HistorySheet(state.conversations, state.currentConversationId, state.isRunning, onSelectConversation, onDeleteConversation)
        }
    }
    if (state.showClearConfirm) {
        AlertDialog(
            onDismissRequest = onClearDismiss,
            title = { Text(stringResource(R.string.chat_clear_title)) },
            text = { Text(stringResource(R.string.chat_clear_message)) },
            confirmButton = {
                TextButton(onClick = onClearConfirm) {
                    Text(stringResource(R.string.chat_clear_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onClearDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun MessageList(items: List<ChatItem>) {
    val listState = rememberLazyListState()
    // Follow the stream only while the user is already at the bottom, so reading older text is not interrupted.
    LaunchedEffect(items.size, items.lastOrNull()) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (items.isNotEmpty() && last >= items.lastIndex - 1) listState.animateScrollToItem(items.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is ChatItem.User -> Bubble(item.text, fromUser = true)
                is ChatItem.Assistant -> Bubble(item.text, fromUser = false, streaming = item.isStreaming)
                is ChatItem.Tool -> ToolCard(item)
                is ChatItem.ApprovalPending -> InfoCard(
                    stringResource(R.string.chat_approval_waiting, item.tool),
                    stringResource(R.string.chat_approval_hint),
                )
                is ChatItem.Failure -> Text(
                    stringResource(item.error.messageRes()),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@Composable
private fun Bubble(text: String, fromUser: Boolean, streaming: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = if (fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (fromUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = if (streaming && text.isEmpty()) "…" else text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ToolCard(item: ChatItem.Tool) {
    val label = when {
        item.isRunning -> stringResource(R.string.chat_tool_running, item.tool)
        !item.ok -> stringResource(R.string.chat_tool_stopped, item.tool.ifBlank { item.summary.orEmpty() })
        item.summary.isNullOrBlank() -> item.tool
        else -> stringResource(R.string.chat_tool_done, item.summary)
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (item.isRunning) {
                CircularProgressIndicator(Modifier.padding(2.dp).heightIn(max = 20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun InfoCard(title: String, subtitle: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.HourglassTop, contentDescription = null)
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Composer(
    state: ChatUiState,
    voice: VoiceUiState,
    onMic: () -> Unit,
    onLanguage: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val languageDescription = stringResource(R.string.voice_language, voice.language.tag)
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            // Live words while speaking, or a reminder to check a finished transcript before sending.
            when {
                voice.isListening -> Text(
                    voice.partial.ifBlank { stringResource(R.string.voice_listening) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                state.voiceDraft -> Text(
                    stringResource(R.string.voice_review_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onLanguage,
                enabled = !voice.isListening && !state.isRunning,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(voice.language.label, modifier = Modifier.semantics { contentDescription = languageDescription })
            }
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                placeholder = { Text(stringResource(R.string.chat_input_hint, state.agentName)) },
                maxLines = 5,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onMic, enabled = !state.isRunning, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(
                    if (voice.isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = stringResource(if (voice.isListening) R.string.voice_stop else R.string.voice_mic),
                    tint = if (voice.isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            if (state.isRunning) {
                FilledIconButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.chat_stop))
                }
            } else {
                FilledIconButton(onClick = onSend, enabled = state.canSend, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send))
                }
            }
        }
        }
    }
}

@Composable
private fun HistorySheet(
    conversations: List<Conversation>,
    currentId: String?,
    locked: Boolean,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            stringResource(R.string.chat_history),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (conversations.isEmpty()) {
            Text(
                stringResource(R.string.chat_history_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
        conversations.forEach { conversation ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 24.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onSelect(conversation.id) }, enabled = !locked, modifier = Modifier.weight(1f)) {
                    Text(
                        conversation.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.chat_history_untitled),
                        style = if (conversation.id == currentId) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(onClick = { onDelete(conversation.id) }, enabled = !locked) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.chat_delete_conversation))
                }
            }
        }
    }
}
