package dev.zcodemobile.app.ui.scan

import dev.zcodemobile.protocol.RemoteLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trips generated QR codes through the same decode path the camera
 * analyzer uses.
 *
 * This is the camera-independent half of scanner verification: it proves the
 * Y-plane → binarize → decode pipeline and that a real desktop pairing URL
 * survives it. It does not prove CameraX frames arrive as expected, which
 * still needs a physical code.
 */
class QrDecodeTest {

    /** Render [text] to a QR code and return it as an ARGB-free luma plane. */
    private fun qrLuma(text: String, size: Int = 480): Triple<ByteArray, Int, Int> {
        val matrix = com.google.zxing.qrcode.QRCodeWriter()
            .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)

        val width = matrix.width
        val height = matrix.height
        // ZXing luminance convention: 0x00 black, 0xFF white.
        val luma = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                luma[y * width + x] = if (matrix.get(x, y)) 0x00 else 0xFF.toByte()
            }
        }
        return Triple(luma, width, height)
    }

    @Test
    fun `decodes a generated qr code`() {
        val payload = "hello zcode"
        val (luma, w, h) = qrLuma(payload)
        assertEquals(payload, QrDecoder.decode(luma, w, h, w))
    }

    @Test
    fun `decodes a real desktop pairing link`() {
        val url = "https://zcode.z.ai/remote/v4" +
            "?sid=d_QVhdHsDAk7nrURU3dThDmU" +
            "&hash=lTfwvocMJkZhz7eVHu%2BwR2SNPGq3AG5g%2FUgHF%2FGO2js%3D" +
            "&t=1790325109310" +
            "&mid=6765fd52-8970-4479-9050-4b2a6c45d89b" +
            "&name=DESKTOP-PA49OL0&app_version=3.14.3"

        val (luma, w, h) = qrLuma(url, size = 720)
        val decoded = QrDecoder.decode(luma, w, h, w)
        assertNotNull("a real pairing link failed to decode", decoded)
        assertEquals(url, decoded)

        // And it must survive the app's own parser, which is what the scanner
        // hands its result to.
        val link = RemoteLink.parse(decoded!!)
        assertEquals("d_QVhdHsDAk7nrURU3dThDmU", link.deviceSid)
        assertEquals(1790325109310L, link.timestamp)
    }

    @Test
    fun `padded row stride does not break decoding`() {
        // CameraX commonly hands back a stride wider than the visible width.
        val payload = "stride test"
        val (tight, w, h) = qrLuma(payload, size = 360)

        val padding = 24
        val stride = w + padding
        val padded = ByteArray(stride * h) { 0xFF.toByte() }
        for (y in 0 until h) {
            System.arraycopy(tight, y * w, padded, y * stride, w)
        }

        assertEquals(payload, QrDecoder.decode(padded, w, h, stride))
    }

    @Test
    fun `a blank frame yields null rather than throwing`() {
        val (_, w, h) = qrLuma("x", size = 120)
        val blank = ByteArray(w * h) { 0xFF.toByte() }
        assertNull(QrDecoder.decode(blank, w, h, w))
    }

    @Test
    fun `degenerate geometry is rejected`() {
        val data = ByteArray(64)
        assertNull(QrDecoder.decode(data, 0, 8, 8))
        assertNull(QrDecoder.decode(data, 8, 0, 8))
        // stride smaller than width is not a valid Y plane
        assertNull(QrDecoder.decode(data, 32, 8, 8))
        // buffer shorter than stride*height
        assertNull(QrDecoder.decode(data, 8, 8, 64))
    }
}
