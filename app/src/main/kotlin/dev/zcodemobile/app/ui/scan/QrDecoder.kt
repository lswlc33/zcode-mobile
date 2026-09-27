package dev.zcodemobile.app.ui.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * The QR decode step, separated from the camera.
 *
 * Takes the Y plane of a YUV frame — exactly what CameraX hands the analyzer —
 * so the decoding logic can be tested without a device camera or a real
 * printed code. `QrDecodeTest` round-trips generated codes through here.
 *
 * Rotation is deliberately not compensated: ZXing's QR detector locates finder
 * patterns at any orientation, so a rotated frame still decodes.
 */
object QrDecoder {

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * @param data the Y (luma) plane
     * @param rowStride bytes per row, which may exceed [width] due to padding
     */
    fun decode(
        data: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
    ): String? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        if (data.size < rowStride * height) return null

        val reader = MultiFormatReader()
        return try {
            reader.setHints(hints)
            val source = PlanarYUVLuminanceSource(
                data,
                rowStride,
                height,
                0,
                0,
                width,
                height,
                false,
            )
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
                ?.text
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            // No code in frame, or an unreadable one. Both are normal; the
            // analyzer just keeps looking at the next frame.
            null
        } finally {
            reader.reset()
        }
    }
}
