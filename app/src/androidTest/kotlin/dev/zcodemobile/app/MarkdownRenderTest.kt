package dev.zcodemobile.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zcodemobile.app.ui.conversation.ConversationRow
import dev.zcodemobile.app.ui.theme.ZCodeTheme
import dev.zcodemobile.protocol.ConversationReducer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders row kinds whose text relies on an inherited content colour, in both
 * themes.
 *
 * This is the test that caught the dark-mode black-text bug: a `Surface` with a
 * custom colour seeds `LocalContentColor` with `Unspecified`, so any child
 * `Text` that does not name a colour painted black. Rendering only one theme
 * would have missed it.
 *
 *   adb pull /sdcard/Android/data/dev.zcodemobile.app/files/diff-dark.png
 */
@RunWith(AndroidJUnit4::class)
class MarkdownRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun chipsRenderDark() = render(dark = true, name = "chips-dark.png")

    @Test
    fun chipsRenderLight() = render(dark = false, name = "chips-light.png")

    private fun render(dark: Boolean, name: String) {
        compose.setContent {
            ZCodeTheme(darkTheme = dark) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(12.dp)
                ) {
                    // File-change chip: custom surface colour, text from inherit.
                    row(
                        1L, "toolCall",
                        mapOf(
                            "toolName" to "Edit",
                            "status" to "success",
                            "input" to linkedMapOf<String, Any?>(
                                "file_path" to "E:/p/ConversationScreen.kt",
                                "old_string" to "a\nb\nc",
                                "new_string" to "a\nb\nc\nd\ne",
                            ),
                        )
                    )
                    row(
                        2L, "toolCall",
                        mapOf(
                            "toolName" to "Write",
                            "status" to "success",
                            "input" to linkedMapOf<String, Any?>(
                                "file_path" to "E:/p/PROTOCOL.md",
                                "content" to "1\n2\n3\n4\n5\n6\n7",
                            ),
                        )
                    )
                    // Reasoning pill, collapsed then expanded text.
                    row(
                        3L, "reasoning",
                        mapOf("text" to "折叠态的文字颜色必须与主题一致。", "state" to "complete")
                    )
                    row(
                        4L, "userInput",
                        mapOf("text" to "用户气泡的文字也要跟着主题走")
                    )
                    row(
                        5L, "toolCall",
                        mapOf(
                            "toolName" to "Bash",
                            "status" to "running",
                            "inputText" to """{"command":"ls -la"}""",
                        )
                    )
                }
            }
        }
        compose.waitForIdle()
        capture(name)
    }

    @Composable
    private fun row(id: Long, kind: String, extra: Map<String, Any?>) {
        ConversationRow(
            ConversationReducer.toRow(
                linkedMapOf<String, Any?>("rowId" to id, "kind" to kind) + extra
            )
        )
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("captured bitmap is empty", bitmap.width > 0 && bitmap.height > 0)
        val dir = InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null)
        val out = File(dir, name)
        out.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue("$name was not written", out.length() > 0)
    }
}
