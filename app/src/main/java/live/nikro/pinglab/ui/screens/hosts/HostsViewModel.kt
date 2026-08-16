package live.nikro.pinglab.ui.screens.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.nikro.pinglab.core.model.MonitoredHost
import live.nikro.pinglab.core.model.Protocol
import live.nikro.pinglab.core.util.HostValidator
import live.nikro.pinglab.core.util.TargetValidation
import live.nikro.pinglab.di.ServiceLocator

/** Form model for the add/edit sheet. Strings, not numbers, because the user is typing. */
data class HostEditorState(
    val id: Long = 0L,
    val label: String = "",
    val target: String = "",
    val protocol: Protocol = Protocol.ICMP,
    val port: String = "",
    val intervalSeconds: Float = 5f,
    val timeoutMs: String = "2000",
    val payloadSize: String = "32",
    val failureThreshold: String = "3",
    val degradedLatencyMs: String = "150",
    val tag: String = "",
    val notifyOnDown: Boolean = true,
    val notifyOnRecovery: Boolean = true,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val targetError: String? = null,
) {
    val isNew: Boolean get() = id == 0L
}

data class HostsUiState(
    val hosts: List<MonitoredHost> = emptyList(),
    val editor: HostEditorState? = null,
    val message: String? = null,
    val loading: Boolean = true,
)

/** CRUD for monitored hosts. The monitor service picks up changes through the same Flow. */
class HostsViewModel : ViewModel() {

    private val hostRepository = ServiceLocator.hostRepository
    private val settingsRepository = ServiceLocator.settingsRepository

    private val _state = MutableStateFlow(HostsUiState())
    val state: StateFlow<HostsUiState> = _state.asStateFlow()

    private var defaultTimeoutMs: Int = 2_000
    private var defaultPayload: Int = 32
    private var defaultProtocol: Protocol = Protocol.ICMP

    init {
        hostRepository.hosts
            .onEach { hosts -> _state.update { it.copy(hosts = hosts, loading = false) } }
            .launchIn(viewModelScope)

        settingsRepository.settings
            .onEach { settings ->
                defaultTimeoutMs = settings.defaultTimeoutMs
                defaultPayload = settings.defaultPayloadSize
                defaultProtocol = settings.defaultProtocol
            }
            .launchIn(viewModelScope)
    }

    fun openEditor(host: MonitoredHost? = null, prefillTarget: String? = null) {
        val editor = if (host == null) {
            HostEditorState(
                target = prefillTarget.orEmpty(),
                protocol = defaultProtocol,
                timeoutMs = defaultTimeoutMs.toString(),
                payloadSize = defaultPayload.toString(),
                port = defaultProtocol.defaultPort?.toString().orEmpty(),
            )
        } else {
            HostEditorState(
                id = host.id,
                label = host.label,
                target = host.target,
                protocol = host.protocol,
                port = host.port?.toString().orEmpty(),
                intervalSeconds = (host.intervalMs / 1_000f).coerceIn(1f, 300f),
                timeoutMs = host.timeoutMs.toString(),
                payloadSize = host.payloadSize.toString(),
                failureThreshold = host.failureThreshold.toString(),
                degradedLatencyMs = host.degradedLatencyMs.toString(),
                tag = host.tag.orEmpty(),
                notifyOnDown = host.notifyOnDown,
                notifyOnRecovery = host.notifyOnRecovery,
                enabled = host.enabled,
                sortOrder = host.sortOrder,
                createdAt = host.createdAt,
            )
        }
        _state.update { it.copy(editor = editor) }
    }

    fun closeEditor() {
        _state.update { it.copy(editor = null) }
    }

    fun updateEditor(transform: (HostEditorState) -> HostEditorState) {
        _state.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(editor = transform(editor))
        }
    }

    fun save() {
        val editor = _state.value.editor ?: return
        val validation = HostValidator.validate(editor.target)
        if (validation !is TargetValidation.Valid) {
            val reason = (validation as? TargetValidation.Invalid)?.reason
            updateEditor {
                it.copy(targetError = reason?.let(HostValidator::describe) ?: "Invalid host")
            }
            return
        }

        val parsed = validation.target
        val host = MonitoredHost(
            id = editor.id,
            label = editor.label.ifBlank { parsed.host },
            target = parsed.host,
            protocol = editor.protocol,
            port = editor.port.toIntOrNull() ?: parsed.port ?: editor.protocol.defaultPort,
            intervalMs = (editor.intervalSeconds * 1_000f).toLong().coerceAtLeast(1_000L),
            timeoutMs = editor.timeoutMs.toIntOrNull()?.coerceIn(200, 30_000) ?: 2_000,
            payloadSize = editor.payloadSize.toIntOrNull()?.coerceIn(0, 1_472) ?: 32,
            enabled = editor.enabled,
            notifyOnDown = editor.notifyOnDown,
            notifyOnRecovery = editor.notifyOnRecovery,
            failureThreshold = editor.failureThreshold.toIntOrNull()?.coerceIn(1, 20) ?: 3,
            degradedLatencyMs = editor.degradedLatencyMs.toIntOrNull()?.coerceIn(1, 10_000) ?: 150,
            tag = editor.tag.takeIf { it.isNotBlank() },
            sortOrder = editor.sortOrder,
            createdAt = editor.createdAt,
        )

        viewModelScope.launch {
            hostRepository.upsert(host)
            _state.update {
                it.copy(
                    editor = null,
                    message = if (editor.isNew) "Host added" else "Host updated",
                )
            }
        }
    }

    fun delete(hostId: Long) {
        viewModelScope.launch {
            hostRepository.delete(hostId)
            _state.update { it.copy(message = "Host removed") }
        }
    }

    fun setEnabled(hostId: Long, enabled: Boolean) {
        viewModelScope.launch { hostRepository.setEnabled(hostId, enabled) }
    }

    /** Moves a host one slot up or down and persists the whole order. */
    fun move(hostId: Long, up: Boolean) {
        val hosts = _state.value.hosts.toMutableList()
        val index = hosts.indexOfFirst { it.id == hostId }
        if (index < 0) return
        val target = if (up) index - 1 else index + 1
        if (target !in hosts.indices) return
        val moved = hosts.removeAt(index)
        hosts.add(target, moved)
        viewModelScope.launch { hostRepository.reorder(hosts.map { it.id }) }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}
