package com.glassous.hmail.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glassous.hmail.ui.theme.HmailTheme
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * 建立一个贯穿整页的玻璃采样层。
 *
 * 必须显式补画背景色：`layerBackdrop` 只录制被标记节点的内容，不补背景会在内容之外留下透明像素。
 * 全页共用一个 backdrop，录制一次、多处采样。
 */
@Composable
fun rememberGlassBackdrop(background: Color): LayerBackdrop {
    val onDraw: ContentDrawScope.() -> Unit = remember(background) {
        {
            drawRect(background)
            drawContent()
        }
    }
    return rememberLayerBackdrop(onDraw = onDraw)
}

/** 把当前节点标记为玻璃的采样源（通常加在页面主体内容上）。 */
fun Modifier.glassSource(backdrop: LayerBackdrop): Modifier = this.layerBackdrop(backdrop)

/**
 * 一块液态玻璃：离屏录制背景 → 饱和度提升 + 高斯模糊 + 透镜折射 → 叠加主题化着色层。
 * 按下时着色层加深，提供"高光流动"的下沉反馈。
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(percent = 50),
    tint: Color = HmailTheme.colors.glassTint,
    tintAlpha: Float = HmailTheme.colors.glassTintAlpha,
    blurRadius: Dp = 6.dp,
    refraction: Dp = 12.dp,
    depth: Dp = 24.dp,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val target = if (pressed && enabled) (tintAlpha + 0.18f).coerceAtMost(0.95f) else tintAlpha
    val alpha by animateFloatAsState(target, label = "glassTint")
    Box(
        modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    lens(refraction.toPx(), depth.toPx())
                },
                // 关闭默认高光描边（避免白色边框）与默认外投影（避免玻璃块带阴影）。
                highlight = null,
                shadow = null,
                onDrawSurface = { drawRect(tint.copy(alpha = alpha)) }
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        content = content
    )
}

/** 圆形玻璃按钮：48dp 见方，居中放一个图标。 */
@Composable
fun GlassCircle(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier.size(48.dp),
        shape = RoundedCornerShape(percent = 50),
        enabled = enabled,
        onClick = onClick,
        content = content
    )
}

/** 胶囊玻璃块：高度自适应，内容左右各留 16dp。 */
@Composable
fun GlassPill(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(percent = 50),
    /** 遮罩颜色，默认取主题玻璃色；传入实色可让玻璃与卡片同色，形变与模糊不受影响。 */
    tint: Color = HmailTheme.colors.glassTint,
    tintAlpha: Float = HmailTheme.colors.glassTintAlpha,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = shape,
        tint = tint,
        tintAlpha = tintAlpha,
        enabled = enabled,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 16.dp)
                .align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}
