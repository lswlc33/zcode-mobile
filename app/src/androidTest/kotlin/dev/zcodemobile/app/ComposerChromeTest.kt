package dev.zcodemobile.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zcodemobile.app.session.ConnState
import dev.zcodemobile.app.session.ConversationUiState
import dev.zcodemobile.app.ui.conversation.ConversationScreen
import dev.zcodemobile.app.ui.theme.ZCodeTheme
import dev.zcodemobile.protocol.ConversationReducer
import dev.zcodemobile.protocol.ConversationState
import dev.zcodemobile.protocol.ContextUsage
import dev.zcodemobile.protocol.GoalState
import dev.zcodemobile.protocol.ModelOption
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the conversation chrome — floating header, transcript, floating
 * composer — with a fabricated session, so the two bars can be compared
 * against each other without a live relay.
 *
 * The transcript is long enough that it scrolls behind both bars, which is the
 * property under test: a bar that is opaque hides the text, a bar that is
 * translucent lets it show through.
 *
 *   adb pull /sdcard/Android/data/dev.zcodemobile.app/files/composer-dark-short.png
 */
@RunWith(AndroidJUnit4::class)
class ComposerChromeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shortModelNameDark() = render(dark = true, "glm-5.3", "composer-dark-short.png")

    @Test
    fun shortModelNameLight() = render(dark = false, "glm-5.3", "composer-light-short.png")

    @Test
    fun longModelNameDark() =
        render(dark = true, "deepseek-v4.1-flash", "composer-dark-long.png")

    private fun render(dark: Boolean, modelLabel: String, name: String) {
        setScreen(dark, modelLabel)
        compose.waitForIdle()
        capture(name)

        // Scrolled up a little, the tail of the transcript lands behind the
        // composer: the bar has to stay translucent there too, or the text is
        // cut off by a slab exactly like before.
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(centerX, centerY - 250f),
                end = Offset(centerX, centerY + 450f),
                durationMillis = 250,
            )
        }
        compose.waitForIdle()
        capture(name.replace(".png", "-scrolled.png"))
    }

    private fun setScreen(dark: Boolean, modelLabel: String) {
        compose.setContent {
            ZCodeTheme(darkTheme = dark) {
                ConversationScreen(
                    title = "完善 gitingro 和 README",
                    workspace = "E:\\open_trae_m\\zcode-mobile",
                    state = demoState(modelLabel),
                    connState = ConnState.Ready,
                    onBack = {},
                    onOpenSidebar = {},
                    onSend = { _, _, _ -> },
                    onSendWithQueueDecision = { _, _ -> },
                    onStop = {},
                    onResolve = { _, _ -> },
                    onSnooze = {},
                    onLoadOlder = {},
                    onAttach = {},
                    onRemoveAttachment = {},
                    onOpenModelPicker = {},
                    onPickModel = {},
                    onPickMode = {},
                    onPickThought = {},
                    onPickBranch = {},
                    onRowAction = { _, _, _, _ -> },
                    onQueueAction = { _, _, _ -> },
                    onSetGoal = {},
                    onGoalAction = {},
                    onCompact = {},
                    onCancelBackgroundWork = {},
                )
            }
        }
    }

    private fun demoState(modelLabel: String) = ConversationUiState(
        conversation = ConversationState(
            sessionId = "sess_demo",
            title = "完善 gitingro 和 README",
            phase = "running",
            canStop = true,
            rows = demoRows(),
            totalCount = demoRows().size,
            model = "glm-5.3-flash",
            provider = "zai",
            thoughtLevel = "low",
            mode = "yolo",
            usage = ContextUsage(usedTokens = 602_000, maxTokens = 1_000_000),
            contextUsed = 602_000,
            contextMax = 1_000_000,
            thoughtLevels = listOf("disabled", "low", "high", "max"),
            hasSnapshot = true,
            goal = GoalState(
                targetId = "goal_1",
                objective = "Run protocol and app JVM unit tests",
                summaryTitle = "Run protocol and app JVM unit tests",
                status = "active",
                iteration = 2,
                timeUsedSeconds = 240,
            ),
        ),
        models = listOf(
            ModelOption(
                providerId = "zai",
                providerName = "Z.ai",
                modelId = "glm-5.3-flash",
                modelName = modelLabel,
                thoughtLevels = listOf("low", "high"),
            ),
        ),
    )

    /**
     * Enough rows that the transcript overflows the viewport: the tail ends up
     * behind the composer, which is what makes a non-translucent bar obvious.
     */
    private fun demoRows() = listOf(
        row(1L, "userInput", mapOf("text" to "继续了解项目的整体结构和细节，以便把 README 写得更像一份面向用户的项目介绍。")),
        row(2L, "reasoning", mapOf("text" to "Let me check what's currently untracked.", "state" to "complete")),
        row(
            3L, "toolCall",
            mapOf(
                "toolName" to "Bash",
                "status" to "success",
                "inputText" to """{"command":"ls docs app && git status"}""",
                "outputText" to "app\ndocs\n---\n?? .gradle/\n?? logs/",
            ),
        ),
        row(4L, "reasoning", mapOf("text" to "Let me check the relay-probe tool.", "state" to "complete")),
        row(
            5L, "toolCall",
            mapOf(
                "toolName" to "Read",
                "status" to "success",
                "inputText" to """{"file_path":"E:/open_trae_m/zcode-mobile/README.md"}""",
                "outputText" to "# zcode-mobile",
            ),
        ),
        row(
            6L, "assistantText",
            mapOf(
                "text" to "项目的模块划分已经看清楚了：协议层是纯 JVM，UI 层在 app 模块里，" +
                    "两者之间只通过 ConversationState 这个投影通信。\n\n" +
                    "接下来我把抓包产物和交接文档对照一遍，确认没有遗漏的通道方法，" +
                    "然后再动手改 README 的结构。",
                "state" to "complete",
            ),
        ),
        row(7L, "reasoning", mapOf("text" to "Checking the ignore rules now.", "state" to "complete")),
        row(
            8L, "toolCall",
            mapOf(
                "toolName" to "Bash",
                "status" to "success",
                "inputText" to """{"command":"cat .gitignore"}""",
                "outputText" to "build/\n.gradle/\nlogs/",
            ),
        ),
        row(
            9L, "assistantText",
            mapOf(
                "text" to "已经核对完了：抓包产物没被忽略，日志目录也没进 .gitignore。" +
                    "再看一眼交接文档和应用源码，然后重写 README。",
                "state" to "streaming",
            ),
        ),
    )

    private fun row(id: Long, kind: String, extra: Map<String, Any?>): dev.zcodemobile.protocol.Row =
        ConversationReducer.toRow(linkedMapOf<String, Any?>("rowId" to id, "kind" to kind) + extra)

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
