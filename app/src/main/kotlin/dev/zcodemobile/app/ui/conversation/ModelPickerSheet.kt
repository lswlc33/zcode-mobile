package dev.zcodemobile.app.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.zcodemobile.app.ui.theme.LocalZcTokens
import dev.zcodemobile.protocol.ModelOption

/**
 * The model picker as its own component: a bottom sheet, not a dropdown.
 *
 * Level one is a list of provider cards; tapping one slides the sheet content
 * to that provider's models, where the current model carries a check and
 * vision-capable models carry the 视觉 badge. Everything is flat — surface
 * blocks and type, no borders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    models: List<ModelOption>,
    loading: Boolean,
    currentProviderId: String?,
    currentModelId: String?,
    onPick: (ModelOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalZcTokens.current
    var openProvider by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    ModalBottomSheet(
        onDismissRequest = {
            openProvider = null
            onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tokens.secondaryText.copy(alpha = 0.4f)),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .padding(horizontal = 16.dp),
        ) {
            val openId = openProvider
            val groupModels = openId?.let { id -> models.filter { it.providerId == id } }.orEmpty()
            if (openId == null) {
                SheetHeading("选择模型", "按桌面端供应商分组")
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { openProvider = null }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(tokens.chipBackground),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.ArrowBack, "返回供应商列表",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        groupModels.firstOrNull()?.providerName ?: openId,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
                when {
                    loading && models.isEmpty() -> {
                        item {
                            SheetEmpty("正在读取桌面端模型…")
                        }
                    }
                    openId == null && models.isEmpty() -> {
                        item { SheetEmpty("桌面端没有可用模型") }
                    }
                    openId == null -> {
                        val groups = models.groupBy { it.providerId }
                        items(groups.size) { i ->
                            val entry = groups.entries.toList()[i]
                            val group = entry.value
                            SheetRow(
                                onClick = { openProvider = entry.key },
                            ) {
                                Text(
                                    group.first().providerName ?: entry.key,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                Text(
                                    "${group.size} 个模型",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tokens.secondaryText,
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Default.ChevronRight, null,
                                    tint = tokens.secondaryText,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    else -> {
                        items(groupModels.size) { i ->
                            val m = groupModels[i]
                            val current = m.providerId == currentProviderId && m.modelId == currentModelId
                            SheetRow(onClick = { onPick(m) }) {
                                Text(
                                    m.shortName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                )
                                if (m.supportsVision) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(7.dp),
                                        color = tokens.chipBackground,
                                    ) {
                                        Text(
                                            "视觉",
                                            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = tokens.secondaryText,
                                        )
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                if (current) {
                                    Icon(
                                        Icons.Default.Check, "当前模型",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SheetHeading(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = LocalZcTokens.current.secondaryText,
        )
    }
}

@Composable
private fun SheetEmpty(text: String) {
    val tokens = LocalZcTokens.current
    Box(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tokens.secondaryText)
    }
}

/** One flat, borderless row: the whole block is the touch target. */
@Composable
private fun SheetRow(
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
