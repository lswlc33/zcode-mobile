package dev.zcodemobile.app.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.zcodemobile.app.ui.theme.LocalZcTokens

/**
 * Manual entry for a desktop pairing link.
 *
 * The link is a credential, so it is parsed and stored but never echoed back
 * to the screen or the log.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddLinkScreen(
    onBack: () -> Unit,
    onScan: () -> Unit,
    onSubmit: (String) -> Unit,
    parseError: String?,
) {
    var text by remember { mutableStateOf("") }
    val tokens = LocalZcTokens.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("添加连接") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onScan) {
                        Icon(Icons.Default.QrCodeScanner, "扫描二维码")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .padding(16.dp),
        ) {
            Text("粘贴桌面端的连接地址", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "桌面端侧栏点手机图标 → 复制连接地址。该地址等同于一把临时钥匙，" +
                    "刷新二维码会立即作废旧地址。",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.secondaryText,
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                placeholder = {
                    Text(
                        "https://zcode.z.ai/remote/v4?sid=…",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                },
                isError = parseError != null,
                supportingText = parseError?.let {
                    { Text(it, color = MaterialTheme.colorScheme.error) }
                },
                shape = RoundedCornerShape(12.dp),
            )

            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        tokens.chipBackground
                    },
                    modifier = Modifier.clickable(enabled = text.isNotBlank()) {
                        onSubmit(text.trim())
                    },
                ) {
                    Text(
                        "连接",
                        Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
                        color = if (text.isNotBlank()) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            tokens.secondaryText
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
