package dev.zcodemobile.shared.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * The Material icons the UI uses, as inlined vector paths.
 *
 * `material-icons-extended` had to go: CMP 1.8.2 maps it to 1.7.3, whose iOS
 * klib was never published (the Gradle metadata points at a 404), so it cannot
 * resolve on iOS targets. Path data below comes from the Apache-2.0 licensed
 * material-design-icons repository (24dp grid, filled style), normalized by
 * tooling to plain SVG commands.
 *
 * Usage mirrors the old `Icons.Default.X`: `ZcIcons.Default.ArrowBack`.
 */
object ZcIcons {

    val Default: Defaults = Defaults

    object Defaults {
        private fun icon(name: String, pathData: String): ImageVector {
            val builder = PathBuilder()
            PathParser.parse(pathData, builder)
            return ImageVector.Builder(
                name = name,
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f,
            ).apply {
                addPath(
                    pathData = builder.nodes,
                    pathFillType = PathFillType.NonZero,
                    name = name,
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    stroke = null,
                    strokeAlpha = 1f,
                    strokeLineWidth = 1f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                    strokeLineMiter = 4f,
                )
            }.build()
        }

        val AccountTree: ImageVector by lazy { icon("AccountTree", PATH_ACCOUNTTREE) }
        val Add: ImageVector by lazy { icon("Add", PATH_ADD) }
        val Archive: ImageVector by lazy { icon("Archive", PATH_ARCHIVE) }
        val ArrowBack: ImageVector by lazy { icon("ArrowBack", PATH_ARROWBACK) }
        val ArrowDownward: ImageVector by lazy { icon("ArrowDownward", PATH_ARROWDOWNWARD) }
        val ArrowUpward: ImageVector by lazy { icon("ArrowUpward", PATH_ARROWUPWARD) }
        val AutoAwesome: ImageVector by lazy { icon("AutoAwesome", PATH_AUTOAWESOME) }
        val Build: ImageVector by lazy { icon("Build", PATH_BUILD) }
        val CallSplit: ImageVector by lazy { icon("CallSplit", PATH_CALLSPLIT) }
        val Chat: ImageVector by lazy { icon("Chat", PATH_CHAT) }
        val Check: ImageVector by lazy { icon("Check", PATH_CHECK) }
        val ChevronRight: ImageVector by lazy { icon("ChevronRight", PATH_CHEVRONRIGHT) }
        val Close: ImageVector by lazy { icon("Close", PATH_CLOSE) }
        val Compress: ImageVector by lazy { icon("Compress", PATH_COMPRESS) }
        val ContentCopy: ImageVector by lazy { icon("ContentCopy", PATH_CONTENTCOPY) }
        val Delete: ImageVector by lazy { icon("Delete", PATH_DELETE) }
        val Description: ImageVector by lazy { icon("Description", PATH_DESCRIPTION) }
        val DragHandle: ImageVector by lazy { icon("DragHandle", PATH_DRAGHANDLE) }
        val DriveFileRenameOutline: ImageVector by lazy { icon("DriveFileRenameOutline", PATH_DRIVEFILERENAMEOUTLINE) }
        val Edit: ImageVector by lazy { icon("Edit", PATH_EDIT) }
        val ErrorOutline: ImageVector by lazy { icon("ErrorOutline", PATH_ERROROUTLINE) }
        val ExpandLess: ImageVector by lazy { icon("ExpandLess", PATH_EXPANDLESS) }
        val ExpandMore: ImageVector by lazy { icon("ExpandMore", PATH_EXPANDMORE) }
        val FactCheck: ImageVector by lazy { icon("FactCheck", PATH_FACTCHECK) }
        val Flag: ImageVector by lazy { icon("Flag", PATH_FLAG) }
        val Folder: ImageVector by lazy { icon("Folder", PATH_FOLDER) }
        val History: ImageVector by lazy { icon("History", PATH_HISTORY) }
        val Image: ImageVector by lazy { icon("Image", PATH_IMAGE) }
        val Lightbulb: ImageVector by lazy { icon("Lightbulb", PATH_LIGHTBULB) }
        val MarkChatUnread: ImageVector by lazy { icon("MarkChatUnread", PATH_MARKCHATUNREAD) }
        val Memory: ImageVector by lazy { icon("Memory", PATH_MEMORY) }
        val Menu: ImageVector by lazy { icon("Menu", PATH_MENU) }
        val MoreHoriz: ImageVector by lazy { icon("MoreHoriz", PATH_MOREHORIZ) }
        val MoreVert: ImageVector by lazy { icon("MoreVert", PATH_MOREVERT) }
        val Pause: ImageVector by lazy { icon("Pause", PATH_PAUSE) }
        val PlayArrow: ImageVector by lazy { icon("PlayArrow", PATH_PLAYARROW) }
        val PlaylistAdd: ImageVector by lazy { icon("PlaylistAdd", PATH_PLAYLISTADD) }
        val Psychology: ImageVector by lazy { icon("Psychology", PATH_PSYCHOLOGY) }
        val Public: ImageVector by lazy { icon("Public", PATH_PUBLIC) }
        val PushPin: ImageVector by lazy { icon("PushPin", PATH_PUSHPIN) }
        val QrCodeScanner: ImageVector by lazy { icon("QrCodeScanner", PATH_QRCODESCANNER) }
        val Refresh: ImageVector by lazy { icon("Refresh", PATH_REFRESH) }
        val Schedule: ImageVector by lazy { icon("Schedule", PATH_SCHEDULE) }
        val Search: ImageVector by lazy { icon("Search", PATH_SEARCH) }
        val Send: ImageVector by lazy { icon("Send", PATH_SEND) }
        val Settings: ImageVector by lazy { icon("Settings", PATH_SETTINGS) }
        val Shield: ImageVector by lazy { icon("Shield", PATH_SHIELD) }
        val Stop: ImageVector by lazy { icon("Stop", PATH_STOP) }
        val Terminal: ImageVector by lazy { icon("Terminal", PATH_TERMINAL) }
        val ThumbDown: ImageVector by lazy { icon("ThumbDown", PATH_THUMBDOWN) }
        val ThumbUp: ImageVector by lazy { icon("ThumbUp", PATH_THUMBUP) }
    }
}
