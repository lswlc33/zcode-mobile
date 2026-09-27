package dev.zcodemobile.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.zcodemobile.app.ui.components.ZcAlertDialog
import dev.zcodemobile.app.ui.components.ZcDropdownMenu
import dev.zcodemobile.app.ui.theme.LocalZcTokens
import dev.zcodemobile.protocol.AppSettings
import dev.zcodemobile.protocol.AppSettingsCatalog
import dev.zcodemobile.protocol.SettingKind
import dev.zcodemobile.protocol.SettingSpec

/**
 * Desktop application settings, rendered from a descriptor list.
 *
 * The catalog is deliberately curated: the full `setting.get` payload is mostly
 * desktop-local state (window geometry, tray behaviour, update channels) that a
 * phone has no business writing, plus `httpProxy`, which can embed credentials
 * and is therefore never read into the UI at all. Rendering from descriptors
 * rather than a hand-written row per key means the screen cannot drift out of
 * sync with what the client actually sends.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onOpenModels: () -> Unit,
    onBack: () -> Unit,
    onLoad: () -> Unit,
    onUpdate: (String, Any?) -> Unit,
) {
    val tokens = LocalZcTokens.current

    // Load once on entry; the desktop is the source of truth, so this is a
    // refresh rather than a hydration of client state.
    LaunchedEffect(Unit) { onLoad() }

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
                Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ArrowBack, "返回",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("桌面设置", style = MaterialTheme.typography.titleMedium)
                Text(
                    "改动直接写到配对的桌面端",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.secondaryText,
                )
            }
            if (settings.values.isEmpty()) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        )

        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            item(key = "entry-models") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenModels)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Memory, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("模型设置", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "管理供应商与模型，测试连通性",
                            style = MaterialTheme.typography.labelSmall,
                            color = tokens.secondaryText,
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight, null,
                        tint = tokens.secondaryText,
                        modifier = Modifier.size(18.dp),
                    )
                }
                HorizontalDivider()
            }
            item(key = "section-behavior") {
                SectionLabel("行为")
            }
            items(AppSettingsCatalog.editable.size, key = { AppSettingsCatalog.editable[it].key }) { i ->
                val spec = AppSettingsCatalog.editable[i]
                SettingRow(spec = spec, settings = settings, onUpdate = onUpdate)
            }

            item(key = "section-readonly") {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                SectionLabel("只读信息")
            }
            item(key = "readonly-body") {
                ReadOnlyBlock(settings)
            }

            item(key = "footnote") {
                Text(
                    "此处只列出可远程调整的项。桌面端的窗口、托盘、更新通道等" +
                        "本地状态不在手机端暴露；代理设置因为可能含凭据，也不读取。",
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.secondaryText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalZcTokens.current.secondaryText,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingRow(
    spec: SettingSpec,
    settings: AppSettings,
    onUpdate: (String, Any?) -> Unit,
) {
    val tokens = LocalZcTokens.current

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(spec.label, style = MaterialTheme.typography.bodyLarge)
            spec.description?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = tokens.secondaryText,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        when (spec.kind) {
            SettingKind.Bool -> {
                val value = settings.bool(spec.key) ?: false
                Switch(
                    checked = value,
                    onCheckedChange = { onUpdate(spec.key, it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        uncheckedThumbColor = LocalZcTokens.current.secondaryText,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            }
            SettingKind.Int -> {
                val value = settings.int(spec.key)
                NumberPicker(
                    value = value,
                    options = listOf(1, 3, 7, 14, 30),
                    onPick = { onUpdate(spec.key, it) },
                )
            }
            SettingKind.Text -> {
                val value = settings.string(spec.key)
                TextPicker(
                    value = value,
                    options = TEXT_OPTIONS[spec.key].orEmpty(),
                    onPick = { onUpdate(spec.key, it) },
                )
            }
        }
    }
}

/** A closed set of choices, for settings whose domain is small and fixed. */
private val TEXT_OPTIONS: Map<String, List<Pair<String, String>>> = mapOf(
    "zcodeInteractionBehavior" to listOf(
        "queue" to "排队",
        "guide" to "引导",
    ),
    "locale" to listOf(
        "zh-CN" to "简体中文",
        "en-US" to "English",
    ),
)

@Composable
private fun TextPicker(
    value: String?,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
) {
    if (options.isEmpty()) {
        Text(
            value ?: "—",
            style = MaterialTheme.typography.labelMedium,
            color = LocalZcTokens.current.secondaryText,
        )
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(text = options.firstOrNull { it.first == value }?.second ?: value ?: "—") { open = true }
        ZcDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (wire, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { open = false; onPick(wire) },
                )
            }
        }
    }
}

@Composable
private fun NumberPicker(value: Int?, options: List<Int>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(text = value?.let { "$it 天" } ?: "—") { open = true }
        ZcDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { n ->
                DropdownMenuItem(
                    text = { Text("$n 天") },
                    onClick = { open = false; onPick(n) },
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    val tokens = LocalZcTokens.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = tokens.chipBackground,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReadOnlyBlock(settings: AppSettings) {
    val tokens = LocalZcTokens.current
    val projects = settings.recentProjects()
    val domain = settings.string("providerFamilyDomain")

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        domain?.let {
            InfoLine("供应商域", it)
        }
        if (projects.isEmpty()) {
            Text(
                "暂无最近项目",
                style = MaterialTheme.typography.labelSmall,
                color = tokens.secondaryText,
            )
        } else {
            Text("最近项目", style = MaterialTheme.typography.labelSmall, color = tokens.secondaryText)
            projects.forEach { p ->
                Text(
                    p,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = LocalZcTokens.current.secondaryText,
        )
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
