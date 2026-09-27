package dev.zcodemobile.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zcodemobile.app.data.LinkStore
import dev.zcodemobile.app.net.OkHttpWebSocketFactory
import dev.zcodemobile.protocol.ConversationApi
import dev.zcodemobile.protocol.Json
import dev.zcodemobile.protocol.RemoteLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies the Android-only layers against the live relay: the OkHttp
 * WebSocket adapter (the JVM verifier uses java.net.http instead) and the
 * Keystore-backed link store.
 *
 * Requires a relay link pushed to the device first, because the link is a
 * credential and must not be baked into the APK:
 *
 *   adb push tools/relay-probe/link.txt /sdcard/Android/data/dev.zcodemobile.app/files/link.txt
 *
 * The test skips itself when no link is present, so it is safe to run in CI.
 */
@RunWith(AndroidJUnit4::class)
class RelayInstrumentedTest {

    private val TAG = "RelayInstrumentedTest"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The link arrives as an instrumentation argument (`-PrelayLink=…`) rather
     * than a file: it is a credential, and app-private external storage is not
     * reliably readable by the test process.
     */
    private fun readLink(): RemoteLink? {
        val fromArgs = InstrumentationRegistry.getArguments().getString("relayLink")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (fromArgs != null) {
            return runCatching { RemoteLink.parse(fromArgs) }.getOrNull()
        }
        // Fallback for a manual invocation that pushed a file.
        val candidates = listOf(
            File(context.getExternalFilesDir(null), "link.txt"),
            File("/data/local/tmp/link.txt"),
        )
        val file = candidates.firstOrNull { it.exists() && it.length() > 0 } ?: return null
        return runCatching { RemoteLink.parse(file.readText().trim()) }.getOrNull()
    }

    // `: Unit` is required: runBlocking would otherwise infer the last
    // expression's type, and JUnit rejects a non-void test method.
    @Test
    fun okhttpTransportCompletesHandshakeAndReachesConversation(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        Log.e(
            TAG,
            "arg keys=${args.keySet()} relayLink=${
                args.getString("relayLink")?.let { "len=${it.length}" } ?: "ABSENT"
            }",
        )
        val link = readLink()
        Log.e(TAG, "parsed=${link?.let { "sid=${it.deviceSid}" } ?: "null"}")
        assumeTrue("no relay link on device — skipping", link != null)
        link!!

        assertNotNull(link.deviceMid)

        // ── Keystore-backed storage ──
        val store = LinkStore(context)
        store.save(link, "instrumented")
        val saved = store.links.value.firstOrNull()
        assertNotNull("link did not survive EncryptedSharedPreferences", saved)
        assertEquals(link.deviceSid, saved!!.link.deviceSid)
        assertEquals(link.passHash, saved.link.passHash)

        // ── OkHttp transport, end to end ──
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = dev.zcodemobile.protocol.RelayTransport(
            link,
            OkHttpWebSocketFactory(),
        ) { _, _ -> }

        try {
            withTimeout(30_000) { transport.connect(scope) }
            assertEquals("relay rejected the handshake", "matched", transport.pairStatus)
            assertNotNull(transport.terminalSid)

            val boot = withTimeout(30_000) { transport.bootstrap() }
            assertTrue("desktop reported no workspaces", boot.workspaces.isNotEmpty())

            val workspace = boot.activeWorkspaceKey
                ?: boot.workspaces.first().workspacePath
            val sessionId = boot.activeTaskId
                ?: boot.tasks.firstOrNull()?.taskId
            assertNotNull("desktop reported no sessions", sessionId)
            val sid = sessionId!!

            withTimeout(30_000) { transport.openBridge(workspace, sid) }

            val channel = dev.zcodemobile.protocol.ChannelClient(transport, scope) { }
            channel.start()
            withTimeout(30_000) { channel.awaitInitialized() }

            val api = ConversationApi(channel)
            withTimeout(30_000) { api.handshake(clientId = "instrumented-test") }

            // Listener before subscribe: the snapshot follows the ack immediately.
            var rows = -1
            api.listenFrames(workspace) { candidate ->
                val payload = Json.asMap(Json.asMap(candidate["frame"])["payload"])
                if (Json.asString(payload["kind"]) == "snapshot") {
                    val snap = Json.asMap(payload["snapshot"])
                    rows = Json.asList(Json.asMap(snap["rows"])["window"]).size
                }
            }

            val sub = withTimeout(30_000) { api.subscribe(workspace, sid) }
            assertEquals("snapshot", Json.asString(Json.asMap(sub["ack"])["mode"]))

            delay(10_000)
            assertTrue("no snapshot rows arrived over the OkHttp transport", rows > 0)

            withTimeout(30_000) {
                api.sendCommand(
                    workspace,
                    dev.zcodemobile.protocol.Commands.envelopeFor(
                        type = "pauseGoal",
                        clientId = api.clientId!!,
                        sessionId = sid,
                        payload = emptyMap<String, Any?>(),
                    ),
                )
            }.also { ack ->
                // A rejection still proves the write path reached the host.
                assertTrue(
                    "unexpected ack status: ${ack.status}",
                    ack.status in setOf("accepted", "rejected", "noop", "stale", "duplicate"),
                )
            }

            // ── attachment transaction: begin → chunk → commit ──
            val payload = ("zcode-mobile attachment probe " + "x".repeat(400)).toByteArray()
            val ref = withTimeout(60_000) {
                api.uploadAttachment(
                    workspacePath = workspace,
                    sessionId = sid,
                    fileName = "probe.txt",
                    mime = "text/plain",
                    bytes = payload,
                )
            }
            Log.e(TAG, "attachment ref=${ref.ref} bytes=${ref.bytes}")
            assertTrue("host returned an empty attachment ref", ref.ref.isNotBlank())
            assertEquals(
                "host reported a different byte count",
                payload.size.toLong(),
                ref.bytes,
            )
        } finally {
            transport.close()
            // `-e keepLink true` leaves the saved link in place so the UI can be
            // driven against a real connection without retyping the credential.
            val keep = InstrumentationRegistry.getArguments().getString("keepLink") == "true"
            if (!keep) runCatching { store.remove(saved.id) }
        }
    }
}
