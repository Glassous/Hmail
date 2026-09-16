package com.glassous.hmail

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.core.graphics.PathParser
import kotlin.math.roundToInt

/**
 * 应用自有图标集：全部为 24 视图单位、1.8 单位描边的路径，不依赖任何图标库。
 * 复用 Compose 的 Canvas 直接绘制 Android `Drawable`，保证与重构前像素级一致。
 */
object IconPaths {
    fun of(type: String): String = when (type) {
        "menu" -> "M4,6 L20,6 M4,12 L20,12 M4,18 L20,18"
        // 定制菜单图标：上横线更长、下横线偏短。
        "menu_lines" -> "M4,8 L20,8 M4,16 L15,16"
        "back" -> "M19,12 L5,12 M11,6 L5,12 L11,18"
        "forward" -> "M5,12 L19,12 M13,6 L19,12 L13,18"
        "search" -> "M16,16 L21,21 M18,10 A8,8 0,1 1,2,10 A8,8 0,1 1,18,10"
        "star", "STARRED" -> "M12,3 L14.8,8.8 L21,9.7 L16.5,14.1 L17.6,20.3 L12,17.3 L6.4,20.3 L7.5,14.1 L3,9.7 L9.2,8.8 Z"
        "send", "SENT" -> "M3,3 L22,12 L3,21 L6,12 Z M6,12 L22,12"
        "edit", "DRAFT" -> "M4,16 L16,4 L20,8 L8,20 L4,20 Z M14,6 L18,10"
        // 未完成草稿：文档带折角与文本行，区别于"写邮件"的铅笔图标。
        "draft_file" -> "M6,3 L13,3 L18,8 L18,21 L6,21 Z M13,3 L13,8 L18,8 M9,12 L13,12 M9,16 L15,16"
        "TRASH", "trash" -> "M3,6 L21,6 M9,6 L9,3 L15,3 L15,6 M6,6 L7,21 L17,21 L18,6 M10,10 L10,17 M14,10 L14,17"
        "SPAM" -> "M12,3 L22,21 L2,21 Z M12,9 L12,14 M12,17 L12,18"
        "tag" -> "M3,3 L12,3 L22,13 L13,22 L3,12 Z M7,7 L7.1,7.1"
        "refresh" -> "M20,10 A8,8 0,1 0,19,17 M20,4 L20,10 L14,10"
        "more" -> "M12,5 L12,5.1 M12,12 L12,12.1 M12,19 L12,19.1"
        "chevron_down" -> "M6,9 L12,15 L18,9"
        "chevron_up" -> "M6,15 L12,9 L18,15"
        "check" -> "M4,12 L9,17 L20,6"
        "close" -> "M6,6 L18,18 M18,6 L6,18"
        "attach" -> "M8,15 L15,8 A2,2 0,0 1,18,11 L9,20 A4,4 0,0 1,3,14 L14,3 A5,5 0,0 1,21,10 L11,20"
        "eye" -> "M2,12 C5.5,7.2 9,5 12,5 C15,5 18.5,7.2 22,12 C18.5,16.8 15,19 12,19 C9,19 5.5,16.8 2,12 Z M15,12 A3,3 0,1 1,9,12 A3,3 0,1 1,15,12"
        "eye_off" -> "M3,3 L21,21 M2,12 C5.5,7.2 9,5 12,5 C13.4,5 14.8,5.4 16.2,6.1 M22,12 C18.5,16.8 15,19 12,19 C10.6,19 9.2,18.6 7.8,17.9"
        "settings" -> "M9,3 L15,3 L16,6 L19,7 L22,12 L19,17 L16,18 L15,21 L9,21 L8,18 L5,17 L2,12 L5,7 L8,6 Z M16,12 A4,4 0,1 1,8,12 A4,4 0,1 1,16,12"
        "archive" -> "M3,7 L21,7 L21,11 L3,11 Z M5,11 L5,21 L19,21 L19,11 M10,15 L14,15"
        "restore" -> "M12,20 L12,9 M7.5,13.5 L12,9 L16.5,13.5"
        "read" -> "M3,6 L21,6 L21,18 L3,18 Z M3,7 L12,14 L21,7"
        "unread" -> "M3,6 L21,6 L21,18 L3,18 Z M3,7 L12,14 L21,7 M19,5 L19,5.1"
        "select_all" -> "M4,5 L20,5 M4,12 L20,12 M4,19 L20,19 M2,5 L2,5.1 M2,12 L2,12.1 M2,19 L2,19.1"
        else -> "M3,5 L21,5 L21,19 L3,19 Z M3,6 L12,13 L21,6"
    }
}

/** 与重构前 `MailIcon : Drawable` 完全一致的绘制逻辑（24 单位视图、1.8 单位圆头描边）。 */
class IconDrawable(
    private val type: String,
    private val tint: Int,
    size: Int = 24,
    density: Float = Resources.getSystem().displayMetrics.density
) : Drawable() {
    private val pixels = (size * density).roundToInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.STROKE
        strokeWidth = if (type == "more") 3.4f else 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        canvas.drawPath(PathParser.createPathFromPathData(IconPaths.of(type)), paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = pixels
    override fun getIntrinsicHeight() = pixels
}

private class IconPainter(private val type: String, private val density: Float) : Painter() {
    private val drawable = IconDrawable(type, android.graphics.Color.BLACK, 24, density)

    override val intrinsicSize: Size get() = Size(24f * density, 24f * density)

    override fun DrawScope.onDraw() {
        drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
        drawable.draw(drawContext.canvas.nativeCanvas)
    }
}

@Composable
private fun iconPainter(type: String): Painter {
    val density = LocalDensity.current.density
    return remember(type, density) { IconPainter(type, density) }
}

/** 描边图标；颜色通过 `Icon` 的 ColorFilter 着色，等价于重构前把颜色烧进 Paint。 */
@Composable
fun MailIcon(
    type: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(painter = iconPainter(type), contentDescription = contentDescription, modifier = modifier, tint = tint)
}
