package dev.zcodemobile.shared.ui

import androidx.compose.ui.graphics.vector.PathBuilder

/**
 * Minimal SVG path-data parser (full command set M/L/H/V/C/S/Q/T/A/Z, both
 * cases, implicit repeats) feeding a Compose [PathBuilder].
 *
 * Hand-written because `PathNode` data-class field names differ across
 * AndroidX/CMP releases, which made reflective mapping fragile; the inputs
 * are our own generated icon constants, so a small strict parser is the
 * dependable route. `S`/`T` reflection is computed here — only the base
 * commands are emitted to the builder, keeping this parser independent of
 * the builder's reflective* semantics.
 */
internal object PathParser {

    fun parse(d: String, b: PathBuilder) {
        val t = Tokenizer(d)
        var cmd = ' '
        var cx = 0f
        var cy = 0f
        var lastCtrlX = 0f
        var lastCtrlY = 0f
        var lastKind = 0 // 0 none, 1 cubic ctrl, 2 quad ctrl

        while (!t.atEnd()) {
            if (t.peekCommand()) cmd = t.nextCommand()
            when (cmd) {
                'M' -> {
                    cx = t.nextFloat(); cy = t.nextFloat(); b.moveTo(cx, cy); cmd = 'L'
                }
                'm' -> {
                    cx += t.nextFloat(); cy += t.nextFloat(); b.moveToRelative(cx, cy); cmd = 'l'
                }
                'L' -> { cx = t.nextFloat(); cy = t.nextFloat(); b.lineTo(cx, cy) }
                'l' -> { cx += t.nextFloat(); cy += t.nextFloat(); b.lineToRelative(cx, cy) }
                'H' -> { cx = t.nextFloat(); b.horizontalLineTo(cx) }
                'h' -> { cx += t.nextFloat(); b.horizontalLineToRelative(cx) }
                'V' -> { cy = t.nextFloat(); b.verticalLineTo(cy) }
                'v' -> { cy += t.nextFloat(); b.verticalLineToRelative(cy) }
                'C' -> {
                    val x1 = t.nextFloat(); val y1 = t.nextFloat()
                    val x2 = t.nextFloat(); val y2 = t.nextFloat()
                    cx = t.nextFloat(); cy = t.nextFloat()
                    b.curveTo(x1, y1, x2, y2, cx, cy)
                    lastCtrlX = x2; lastCtrlY = y2; lastKind = 1
                }
                'c' -> {
                    val dx1 = t.nextFloat(); val dy1 = t.nextFloat()
                    val dx2 = t.nextFloat(); val dy2 = t.nextFloat()
                    val dx = t.nextFloat(); val dy = t.nextFloat()
                    b.curveToRelative(dx1, dy1, dx2, dy2, dx, dy)
                    lastCtrlX = cx + dx2; lastCtrlY = cy + dy2
                    cx += dx; cy += dy; lastKind = 1
                }
                'S' -> {
                    val x2 = t.nextFloat(); val y2 = t.nextFloat()
                    val x = t.nextFloat(); val y = t.nextFloat()
                    val rx = if (lastKind == 1) 2 * cx - lastCtrlX else cx
                    val ry = if (lastKind == 1) 2 * cy - lastCtrlY else cy
                    b.curveTo(rx, ry, x2, y2, x, y)
                    cx = x; cy = y
                    lastCtrlX = x2; lastCtrlY = y2; lastKind = 1
                }
                's' -> {
                    val x2 = cx + t.nextFloat(); val y2 = cy + t.nextFloat()
                    val x = cx + t.nextFloat(); val y = cy + t.nextFloat()
                    val rx = if (lastKind == 1) 2 * cx - lastCtrlX else cx
                    val ry = if (lastKind == 1) 2 * cy - lastCtrlY else cy
                    b.curveToRelative(rx - cx, ry - cy, x2 - cx, y2 - cy, x - cx, y - cy)
                    cx = x; cy = y
                    lastCtrlX = x2; lastCtrlY = y2; lastKind = 1
                }
                'Q' -> {
                    val x1 = t.nextFloat(); val y1 = t.nextFloat()
                    cx = t.nextFloat(); cy = t.nextFloat()
                    b.quadTo(x1, y1, cx, cy)
                    lastCtrlX = x1; lastCtrlY = y1; lastKind = 2
                }
                'q' -> {
                    val x1 = cx + t.nextFloat(); val y1 = cy + t.nextFloat()
                    cx += t.nextFloat(); cy += t.nextFloat()
                    b.quadToRelative(x1 - cx, y1 - cy, cx, cy)
                    lastCtrlX = x1; lastCtrlY = y1; lastKind = 2
                }
                'T' -> {
                    val x = t.nextFloat(); val y = t.nextFloat()
                    val rx = if (lastKind == 2) 2 * cx - lastCtrlX else cx
                    val ry = if (lastKind == 2) 2 * cy - lastCtrlY else cy
                    b.quadTo(rx, ry, x, y)
                    cx = x; cy = y
                    lastCtrlX = rx; lastCtrlY = ry; lastKind = 2
                }
                't' -> {
                    val x = cx + t.nextFloat(); val y = cy + t.nextFloat()
                    val rx = if (lastKind == 2) 2 * cx - lastCtrlX else cx
                    val ry = if (lastKind == 2) 2 * cy - lastCtrlY else cy
                    b.quadToRelative(rx - cx, ry - cy, x - cx, y - cy)
                    cx = x; cy = y
                    lastCtrlX = rx; lastCtrlY = ry; lastKind = 2
                }
                'A' -> {
                    val rx = t.nextFloat(); val ry = t.nextFloat()
                    val theta = t.nextFloat()
                    val large = t.nextFloat() != 0f
                    val sweep = t.nextFloat() != 0f
                    cx = t.nextFloat(); cy = t.nextFloat()
                    b.arcTo(rx, ry, theta, large, sweep, cx, cy)
                    lastKind = 0
                }
                'a' -> {
                    val rx = t.nextFloat(); val ry = t.nextFloat()
                    val theta = t.nextFloat()
                    val large = t.nextFloat() != 0f
                    val sweep = t.nextFloat() != 0f
                    val dx = t.nextFloat(); val dy = t.nextFloat()
                    b.arcToRelative(rx, ry, theta, large, sweep, dx, dy)
                    cx += dx; cy += dy
                    lastKind = 0
                }
                'Z', 'z' -> b.close()
                else -> throw IllegalArgumentException("path: unsupported command '$cmd'")
            }
        }
    }

    private class Tokenizer(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean {
            skipWs()
            return i >= s.length
        }

        fun peekCommand(): Boolean {
            skipWs()
            return i < s.length && s[i].isLetter()
        }

        fun nextCommand(): Char {
            skipWs()
            return s[i++]
        }

        fun nextFloat(): Float {
            skipWs()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                        ((s[i] == '-' || s[i] == '+') && (s[i - 1] == 'e' || s[i - 1] == 'E')))
            ) i++
            if (start == i) throw IllegalArgumentException("path: expected number at $i")
            return s.substring(start, i).toFloat()
        }

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == ',' || s[i] == '\n' || s[i] == '\t' || s[i] == '\r')) i++
        }
    }
}
