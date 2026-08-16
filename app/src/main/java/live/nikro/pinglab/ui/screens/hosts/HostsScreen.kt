package live.nikro.pinglab.ui.screens.hosts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import live.nikro.pinglab.R
import live.nikro.pinglab.core.util.Formatters
import live.nikro.pinglab.ui.components.EmptyState
import live.nikro.pinglab.ui.components.MonoTag
import live.nikro.pinglab.ui.components.ProtocolSelector
import live.nikro.pinglab.ui.components.SectionCard
import live.nikro.pinglab.ui.components.VSpace

/** Host list with an add/edit bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    onOpenHost: (Long) -> Unit,
    modifier: Modifier = Modifier,
    openEditorOnLaunch: Boolean = false,
    viewModel: HostsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(openEditorOnLaunch) {
        if (openEditorOnLaunch && state.editor == null) viewModel.openEditor()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_hosts)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.openEditor() },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.action_add_host)) },
            )
        },
    ) { innerPadding ->
        if (state.hosts.isEmpty() && !state.loading) {
            EmptyState(
                icon = Icons.Rounded.Dns,
                title = "No monitored hosts",
                body = "Hosts added here are checked in the background and can raise notifications when they go down.",
                actionLabel = stringResource(R.string.action_add_host),
                onAction = { viewModel.openEditor() },
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items = state.hosts, key = { it.id }) { host ->
                    SectionCard(modifier = Modifier.clickable { onOpenHost(host.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = host.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = host.displayTarget,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = host.enabled,
                                onCheckedChange = { viewModel.setEnabled(host.id, it) },
                            )
                        }
                        VSpace(8)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            MonoTag(text = host.protocol.label)
                            MonoTag(text = "every " + Formatters.interval(host.intervalMs))
                            MonoTag(text = "timeout " + host.timeoutMs + "ms")
                            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                                IconButton(onClick = { viewModel.move(host.id, up = true) }) {
                                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move up")
                                }
                                IconButton(onClick = { viewModel.move(host.id, up = false) }) {
                                    Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move down")
                                }
                                IconButton(onClick = { viewModel.delete(host.id) }) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.action_delete),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        VSpace(4)
                        TextButton(onClick = { viewModel.openEditor(host) }) {
                            Text(stringResource(R.string.action_edit))
                        }
                    }
                }
            }
        }

        val editor = state.editor
        if (editor != null) {
            ModalBottomSheet(
                onDismissRequest = { viewModel.closeEditor() },
                sheetState = sheetState,
            ) {
                HostEditorSheet(
                    editor = editor,
                    onChange = viewModel::updateEditor,
                    onSave = { viewModel.save() },
                    onCancel = { viewModel.closeEditor() },
                )
            }
        }
    }
}

@Composable
private fun HostEditorSheet(
    editor: HostEditorState,
    onChange: ((HostEditorState) -> HostEditorState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        Text(
            text = if (editor.isNew) "New host" else "Edit host",
            style = MaterialTheme.typography.headlineSmall,
        )
        VSpace(14)

        OutlinedTextField(
            value = editor.target,
            onValueChange = { value -> onChange { it.copy(target = value, targetError = null) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.live_target_hint)) },
            isError = editor.targetError != null,
            supportingText = { editor.targetError?.let { Text(it) } },
            singleLine = true,
        )
        VSpace(10)

        OutlinedTextField(
            value = editor.label,
            onValueChange = { value -> onChange { it.copy(label = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Display name (optional)") },
            singleLine = true,
        )
        VSpace(12)

        ProtocolSelector(
            selected = editor.protocol,
            onSelect = { protocol ->
                onChange {
                    it.copy(
                        protocol = protocol,
                        port = it.port.ifBlank { protocol.defaultPort?.toString().orEmpty() },
                    )
                }
            },
        )
        VSpace(12)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (editor.protocol.needsPort) {
                OutlinedTextField(
                    value = editor.port,
                    onValueChange = { value -> onChange { it.copy(port = value.filter(Char::isDigit)) } },
                    modifier = Modifier.weight(1f),
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            OutlinedTextField(
                value = editor.timeoutMs,
                onValueChange = { value -> onChange { it.copy(timeoutMs = value.filter(Char::isDigit)) } },
                modifier = Modifier.weight(1f),
                label = { Text("Timeout, ms") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        VSpace(14)

        Text(
            text = "Check every " + Formatters.interval((editor.intervalSeconds * 1000).toLong()),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = editor.intervalSeconds,
            onValueChange = { value -> onChange { it.copy(intervalSeconds = value) } },
            valueRange = 1f..300f,
            steps = 0,
        )
        VSpace(6)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = editor.failureThreshold,
                onValueChange = { value -> onChange { it.copy(failureThreshold = value.filter(Char::isDigit)) } },
                modifier = Modifier.weight(1f),
                label = { Text("Fails before DOWN") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = editor.degradedLatencyMs,
                onValueChange = { value -> onChange { it.copy(degradedLatencyMs = value.filter(Char::isDigit)) } },
                modifier = Modifier.weight(1f),
                label = { Text("Degraded above, ms") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        VSpace(12)

        ToggleRow(
            title = "Notify when down",
            checked = editor.notifyOnDown,
            onCheckedChange = { value -> onChange { it.copy(notifyOnDown = value) } },
        )
        ToggleRow(
            title = "Notify on recovery",
            checked = editor.notifyOnRecovery,
            onCheckedChange = { value -> onChange { it.copy(notifyOnRecovery = value) } },
        )
        ToggleRow(
            title = "Monitoring enabled",
            checked = editor.enabled,
            onCheckedChange = { value -> onChange { it.copy(enabled = value) } },
        )

        VSpace(18)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
