package id.sengkuni.aphodius

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private fun stroke(name: String, d: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

private fun filled(name: String, d: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()

// Ikon mengikuti design system Aphodius (24dp, stroke 2, ujung bulat)
object Ic {
    val Home by lazy { stroke("home", "M3 11l9-8 9 8v10h-6v-6H9v6H3z") }
    val Search by lazy { stroke("search", "M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM21 21l-6-6") }
    val Play by lazy { stroke("play", "M6 4l14 8-14 8z") }
    val PlayFill by lazy { filled("playFill", "M6 4l14 8-14 8z") }
    val PauseFill by lazy { filled("pauseFill", "M6 4h4v16H6zM14 4h4v16h-4z") }
    val Plus by lazy { stroke("plus", "M12 4v16M4 12h16") }
    val Check by lazy { stroke("check", "M4 12l5 5 11-11") }
    val Back by lazy { stroke("back", "M15 4l-8 8 8 8") }
    val Close by lazy { stroke("close", "M5 5l14 14M19 5L5 19") }
    val Share by lazy { stroke("share", "M12 3v12M7 8l5-5 5 5M5 14v6h14v-6") }
    val Volume by lazy { stroke("volume", "M3 9h4l5-4v14l-5-4H3zM16 8a5 5 0 0 1 0 8") }
    val Mute by lazy { stroke("mute", "M3 9h4l5-4v14l-5-4H3zM16 9l5 6M21 9l-5 6") }
    val Eps by lazy { stroke("eps", "M4 4h16v16H4zM9 8l7 4-7 4z") }
    val Rewind by lazy { stroke("rewind", "M4 12a8 8 0 1 0 3-6.2M4 3v5h5") }
    val Forward by lazy { stroke("forward", "M20 12a8 8 0 1 1-3-6.2M20 3v5h-5") }
    val Bookmark by lazy { stroke("bookmark", "M6 3h12v18l-6-4-6 4z") }
    val Speed by lazy { stroke("speed", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2") }
    val Aspect by lazy { stroke("aspect", "M3 6h18v12H3zM7 10h10v4H7z") }
    val Quality by lazy { stroke("quality", "M4 7h8M16 7h4M4 17h4M12 17h8M12 4v6M8 14v6") }
    val Next by lazy { stroke("next", "M5 4l10 8-10 8zM19 5v14") }
    val External by lazy { stroke("external", "M14 4h6v6M20 4l-9 9M18 14v6H4V6h6") }
    val Sun by lazy { stroke("sun", "M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.5 1.5M17.5 17.5L19 19M5 19l1.5-1.5M17.5 6.5L19 5") }
    val Refresh by lazy { stroke("refresh", "M20 12a8 8 0 1 1-3-6.2M20 3v5h-5") }
}
