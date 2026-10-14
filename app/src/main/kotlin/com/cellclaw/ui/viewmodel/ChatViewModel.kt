package com.cellclaw.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cellclaw.agent.AgentEvent
import com.cellclaw.agent.AgentLoop
import com.cellclaw.agent.AgentState
import com.cellclaw.agent.HeartbeatResult
import com.cellclaw.approval.ApprovalQueue
import com.cellclaw.approval.ApprovalRequest
import com.cellclaw.approval.ApprovalResult
import com.cellclaw.config.AppConfig
import com.cellclaw.memory.ConversationStore
import com.cellclaw.provider.ProviderManager
import com.cellclaw.ui.screens.ChatMessage
import com.cellclaw.voice.VoiceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentLoop: AgentLoop,
    private val approvalQueue: ApprovalQueue,
    private val appConfig: AppConfig,
    private val providerManager: ProviderManager,
    private val conversationStore: ConversationStore,
    private val voiceManager: VoiceManager
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _agentState = MutableStateFlow("idle")
    val agentState: StateFlow<String> = _agentState.asStateFlow()

    private val _thinkingText = MutableStateFlow<String?>(null)
    val thinkingText: StateFlow<String?> = _thinkingText.asStateFlow()

    val pendingApprovals: StateFlow<List<ApprovalRequest>> = approvalQueue.requests

    private val _currentModel = MutableStateFlow(appConfig.model)
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    private val _activeProvider = MutableStateFlow(providerManager.activeType())
    val activeProvider: StateFlow<String> = _activeProvider.asStateFlow()

    private var messageCounter = 0L

    val isListening: StateFlow<Boolean> = voiceManager.isListening
    val voiceEnabled: Boolean get() = appConfig.voiceEnabled

    init {
        loadHistory()
        observeAgentEvents()
        observeAgentState()
        observeVoiceInput()
        if (appConfig.voiceEnabled) {
            voiceManager.initialize()
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            agentLoop.loadHistory()
            val stored = conversationStore.getRecentMessages(50)
            if (stored.isNotEmpty()) {
                _messages.value = stored.map {
                    ChatMessage(
                        id = it.id,
                        role = it.role,
                        content = it.content,
                        timestamp = it.timestamp
                    )
                }
                messageCounter = (stored.maxOfOrNull { it.id } ?: 0L)
            }
        }
    }

    fun startNewChat() {
        agentLoop.clearHistory()
        _messages.value = emptyList()
        _thinkingText.value = null
    }

    fun setModel(newModel: String) {
        appConfig.model = newModel
        _currentModel.value = newModel
    }

    fun getAvailableModels(): List<String> {
        val activeType = providerManager.activeType()
        val providerInfo = providerManager.availableProviders().firstOrNull { it.type == activeType }
        val models = providerInfo?.models
        return if (!models.isNullOrEmpty()) models else listOf(appConfig.model)
    }

    private fun observeAgentEvents() {
        viewModelScope.launch {
            agentLoop.events.collect { event ->
                when (event) {
                    is AgentEvent.UserMessage -> addMessage("user", event.text)
                    is AgentEvent.AssistantText -> addMessage("assistant", event.text)
                    is AgentEvent.ToolCallStart -> addMessage(
                        "tool", "Calling ${event.name}...",
                        toolName = event.name
                    )
                    is AgentEvent.ToolCallResult -> addMessage(
                        "tool",
                        if (event.result.success) "Done" else "Error: ${event.result.error}",
                        toolName = event.name
                    )
                    is AgentEvent.ToolCallDenied -> addMessage(
                        "tool", "Denied by user",
                        toolName = event.name
                    )
                    is AgentEvent.ThinkingText -> _thinkingText.value = event.text
                    is AgentEvent.Error -> addMessage("error", event.message)
                    is AgentEvent.HeartbeatStart -> {} // Silent — don't show in chat
                    is AgentEvent.HeartbeatComplete -> {
                        val note = event.detection.statusNote
                        if (note != null && event.detection.heartbeatResult != HeartbeatResult.OK_NOTHING_TO_DO) {
                            addMessage("assistant", note)
                        }
                    }
                }
            }
        }
    }

    private fun observeAgentState() {
        viewModelScope.launch {
            agentLoop.state.collect { state ->
                _agentState.value = when (state) {
                    AgentState.IDLE -> "idle"
                    AgentState.THINKING -> "thinking"
                    AgentState.EXECUTING_TOOLS -> "executing_tools"
                    AgentState.WAITING_APPROVAL -> "waiting_approval"
                    AgentState.PAUSED -> "paused"
                    AgentState.ERROR -> "error"
                }
                if (state == AgentState.IDLE || state == AgentState.ERROR) {
                    _thinkingText.value = null
                }
            }
        }
    }

    fun sendMessage(text: String) {
        agentLoop.submitMessage(text)
    }

    fun stopAgent() {
        agentLoop.stop()
        _thinkingText.value = null
    }

    fun respondToApproval(requestId: String, result: ApprovalResult) {
        approvalQueue.respond(requestId, result)
    }

    private fun observeVoiceInput() {
        viewModelScope.launch {
            voiceManager.recognizedText.collect { text ->
                if (text.isNotBlank()) {
                    sendMessage(text)
                }
            }
        }
    }

    fun startVoiceInput() {
        if (!appConfig.voiceEnabled) return
        voiceManager.startListening()
    }

    fun stopVoiceInput() {
        voiceManager.stopListening()
    }

    private fun addMessage(role: String, content: String, toolName: String? = null) {
        messageCounter++
        _messages.value = _messages.value + ChatMessage(
            id = messageCounter,
            role = role,
            content = content,
            toolName = toolName
        )
        // Auto-speak assistant responses if enabled
        if (role == "assistant" && appConfig.autoSpeakResponses) {
            voiceManager.speak(content)
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.destroy()
    }
}
