package com.nebulaforge.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nebulaforge.app.R
import com.nebulaforge.core.environment.BootstrapInstaller
import com.nebulaforge.core.environment.BootstrapSource
import com.nebulaforge.core.environment.BootstrapSourceCatalog
import com.nebulaforge.core.environment.BootstrapSourceResolver
import com.nebulaforge.core.environment.BootstrapSourceStore
import com.nebulaforge.core.environment.SourceProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「设置 → 工具链下载源」：把内置的多个真实下载源暴露给用户。
 *
 * 行为约定：
 * - 进入面板即异步探测一次（真实 Range 请求，不是假状态），显示每个源的可用性与实测延迟；
 * - 选择「自动」时按「上次成功 → 实测最快」排序自动选源，安装失败自动跳到下一个；
 * - 自定义源必须 HTTPS 且同时含 {tag} 与 {arch}，保存后立即参与排序。
 */
@Composable
fun ToolchainSourcePanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resolver = remember { BootstrapSourceResolver(context) }
    val installer = remember { BootstrapInstaller(context) }
    val store: BootstrapSourceStore = resolver.store

    var tag by remember { mutableStateOf(store.cachedReleaseTag.orEmpty()) }
    var architecture by remember { mutableStateOf("") }
    var probes by remember { mutableStateOf<Map<String, SourceProbe>>(emptyMap()) }
    var probing by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf(store.selectedSourceId) }
    var customTemplate by remember { mutableStateOf(store.customTemplate) }
    var customError by remember { mutableStateOf<String?>(null) }
    var preferEmbedded by remember { mutableStateOf(store.preferEmbedded) }
    var note by remember { mutableStateOf("") }

    val sources = remember(selectedId, note) { resolver.allSources() }

    fun probeAll() {
        scope.launch {
            probing = true
            note = ""
            val resolvedTag = withContext(Dispatchers.IO) {
                runCatching { installer.resolveReleaseTag() }.getOrDefault(tag.ifBlank { "" })
            }
            val arch = withContext(Dispatchers.IO) { installer.detectArchitectureSafe() }
            tag = resolvedTag
            architecture = arch
            probes = withContext(Dispatchers.IO) {
                runCatching { resolver.probeAll(resolver.allSources(), resolvedTag, arch) }
                    .getOrDefault(emptyList())
                    .associateBy { it.sourceId }
            }
            probing = false
        }
    }

    LaunchedEffect(Unit) {
        architecture = withContext(Dispatchers.IO) { installer.detectArchitectureSafe() }
        if (tag.isBlank()) {
            tag = withContext(Dispatchers.IO) {
                runCatching { installer.resolveReleaseTag() }.getOrDefault("")
            }
            store.cachedReleaseTag = tag
        }
        probeAll()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_toolchain_sources), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_toolchain_sources_desc), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { probeAll() }, enabled = !probing) {
                    Text(stringResource(if (probing) R.string.settings_toolchain_probing else R.string.settings_toolchain_probe))
                }
            }
            Text(
                stringResource(R.string.settings_toolchain_version, tag.ifBlank { "—" }, architecture.ifBlank { "—" }),
                style = MaterialTheme.typography.labelSmall
            )
            store.lastWorkingSourceId?.let { last ->
                Text(stringResource(R.string.settings_toolchain_last_working, last), style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_toolchain_prefer_embedded), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.settings_toolchain_prefer_embedded_desc), style = MaterialTheme.typography.labelSmall)
                }
                Switch(checked = preferEmbedded, onCheckedChange = { preferEmbedded = it; store.preferEmbedded = it })
            }

            SourceRow(
                id = BootstrapSourceStore.AUTO,
                title = stringResource(R.string.settings_toolchain_auto),
                subtitle = stringResource(R.string.settings_toolchain_auto_desc),
                badge = "",
                probe = null,
                selected = selectedId == BootstrapSourceStore.AUTO,
                probing = probing,
                onSelect = { selectedId = it; store.selectedSourceId = it }
            )

            sources.forEach { source ->
                SourceRow(
                    id = source.id,
                    title = source.label,
                    subtitle = source.description,
                    badge = source.kind.display,
                    probe = probes[source.id],
                    selected = selectedId == source.id,
                    probing = probing,
                    onSelect = { selectedId = it; store.selectedSourceId = it }
                )
            }

            OutlinedTextField(
                value = customTemplate,
                onValueChange = { customTemplate = it; customError = null },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.settings_toolchain_custom)) },
                placeholder = { Text(BootstrapSourceCatalog.TEMPLATE_HINT) },
                isError = customError != null,
                supportingText = {
                    Text(
                        customError?.let { stringResource(R.string.settings_toolchain_custom_invalid, it) }
                            ?: stringResource(R.string.settings_toolchain_custom_hint)
                    )
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val error = BootstrapSourceCatalog.validateTemplate(customTemplate)
                    if (error != null) {
                        customError = error
                    } else {
                        store.customTemplate = customTemplate
                        selectedId = BootstrapSourceCatalog.CUSTOM_ID
                        store.selectedSourceId = selectedId
                        note = context.getString(R.string.settings_toolchain_custom_saved)
                        probeAll()
                    }
                }) { Text(stringResource(R.string.settings_toolchain_custom_save)) }
            }
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SourceRow(
    id: String,
    title: String,
    subtitle: String,
    badge: String,
    probe: SourceProbe?,
    selected: Boolean,
    probing: Boolean,
    onSelect: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = { onSelect(id) })
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                if (badge.isNotBlank()) {
                    Text(" · $badge", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
            Text(
                probeLabel(probe, probing, id),
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    probe?.reachable == true -> MaterialTheme.colorScheme.primary
                    probe != null -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun probeLabel(probe: SourceProbe?, probing: Boolean, id: String): String = when {
    id == BootstrapSourceStore.AUTO -> ""
    probe == null && probing -> stringResource(R.string.settings_toolchain_probe_probing_one)
    probe == null -> stringResource(R.string.settings_toolchain_probe_pending)
    probe.reachable -> stringResource(R.string.settings_toolchain_probe_ok, probe.latencyMs.toInt(), probe.httpCode)
    else -> stringResource(R.string.settings_toolchain_probe_fail, probe.message)
}
