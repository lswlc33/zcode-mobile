package dev.zcodemobile.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zcodemobile.app.ui.components.ZcAlertDialog
import dev.zcodemobile.app.ui.components.ZcDropdownMenu
import dev.zcodemobile.app.ui.theme.LocalZcTokens
import dev.zcodemobile.protocol.ProviderEntry
import dev.zcodemobile.protocol.ProviderSettingsView

/**
 * 模型设置: the desktop's provider registry, read-only except for the
 * mutations the protocol supports end to end (add/remove providers, add/
 * remove/enable models, connectivity test).
 *
 * Credentials are write-only: the API key typed here goes into
 * `savePersonalProviderOverlay` and is never echoed back — `getView`'s raw
 * payload carries keys in plaintext, and this screen never touches that field.
 */
@Composable
fun ModelSettingsScreen(
    view: ProviderSettingsView?,
    loading: Boolean,
    error: String?,
    testRunningKey: String?,
    testResult: Pair<String, String>?,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onAddProvider: (name: String, apiKey: String, baseUrl: String) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onAddModel: (providerId: String, modelId: String) -> Unit,
    onDeleteModel: (providerId: String, modelId: String) -> Unit,
    onSetModelEnabled: (providerId: String, modelId: String, enabled: Boolean) -> Unit,
    onTestModel: (providerId: String, modelId: String) -> Unit,
) {
    val tokens = LocalZcTokens.current
    var expandedProvider by remember { mutableStateOf<String?>(null) }
    var addProviderOpen by remember { mutableStateOf(false) }
    var addModelFor by remember { mutableStateOf<String?>(null) }
    var deletingProvider by remember { mutableStateOf<ProviderEntry?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (view == null) onLoad()
    }

    addProviderOpen.let { open ->
        if (!open) return@let
        AddProviderDialog(
            onDismiss = { addProviderOpen = false },
            onConfirm = { name, key, url ->
                onAddProvider(name, key, url)
                addProviderOpen = false
            },
        )
    }

    addModelFor?.let { providerId ->
        AddModelDialog(
            onDismiss = { addModelFor = null },
            onConfirm = { modelId ->
                onAddModel(providerId, modelId)
                addModelFor = null
            },
        )
    }

    deletingProvider?.let { provider ->
        ZcAlertDialog(
            onDismissRequest = { deletingProvider = null },
            title = { Text("删除供应商") },
            text = { Text("将删除「${provider.name}」及其全部模型配置。") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteProvider(provider.providerId)
                    deletingProvider = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingProvider = null }) { Text("取消") }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.ArrowBack, "返回", modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("模型设置", style = MaterialTheme.typography.titleMedium)
                Text(
                    view?.let { "${it.providers.size} 个供应商" } ?: "管理桌面端的供应商与模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.secondaryText,
                )
            }
            TextAction("添加供应商") { addProviderOpen = true }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        )

        error?.let {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                Text(
                    it,
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        if (loading && view == null) {
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("读取供应商配置…", style = MaterialTheme.typography.bodySmall, color = tokens.secondaryText)
            }
        }

        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
            view?.providers?.forEach { provider ->
                val key = provider.providerId
                item(key = "p-$key") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Column {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedProvider = if (expandedProvider == key) null else key
                                    }
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        provider.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "${provider.models.size} 个模型",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = tokens.secondaryText,
                                    )
                                }
                                IconButton(
                                    onClick = { deletingProvider = provider },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Delete, "删除供应商",
                                        tint = tokens.secondaryText,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Icon(
                                    if (expandedProvider == key) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    null,
                                    tint = tokens.secondaryText,
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            if (expandedProvider == key) {
                                provider.models.forEach { model ->
                                    val testKey = "$key/${model.modelId}"
                                    ModelRow(
                                        modelId = model.modelId,
                                        enabled = model.enabled,
                                        vision = model.supportsVision,
                                        testing = testRunningKey == testKey,
                                        result = testResult?.takeIf { it.first == testKey }?.second,
                                        onEnabled = { onSetModelEnabled(key, model.modelId, it) },
                                        onDelete = { onDeleteModel(key, model.modelId) },
                                        onTest = { onTestModel(key, model.modelId) },
                                    )
                                }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { addModelFor = key }
                                        .padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Add, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "添加模型",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    modelId: String,
    enabled: Boolean,
    vision: Boolean,
    testing: Boolean,
    result: String?,
    onEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    val tokens = LocalZcTokens.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    modelId,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (vision) {
                        Text(
                            "视觉",
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.secondaryText,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    result?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = tokens.secondaryText)
                    }
                }
            }

            if (testing) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))
            } else {
                Text(
                    "测试",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onTest)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.width(8.dp))

            Switch(
                checked = enabled,
                onCheckedChange = onEnabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    uncheckedThumbColor = tokens.secondaryText,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier.size(40.dp),
            )

            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.Close, "删除模型",
                    tint = tokens.secondaryText,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, apiKey: String, baseUrl: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    ZcAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加供应商") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Base URL（如 https://api.example.com/v1）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && key.isNotBlank() && url.isNotBlank(),
                onClick = { onConfirm(name.trim(), key.trim(), url.trim()) },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AddModelDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var modelId by remember { mutableStateOf("") }
    ZcAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加模型") },
        text = {
            OutlinedTextField(
                value = modelId, onValueChange = { modelId = it },
                label = { Text("模型 ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = modelId.isNotBlank(),
                onClick = { onConfirm(modelId.trim()) },
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
