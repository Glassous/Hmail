package com.glassous.hmail.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.glassous.hmail.MailIcon
import com.glassous.hmail.ui.glass.GlassSurface
import com.glassous.hmail.ui.theme.HmailTheme
import com.kyant.backdrop.Backdrop

/**
 * 由竖向三点按钮呼出的玻璃操作卡片。
 *
 * - 与页面共用同一个 [backdrop]，因此卡片本身也是真实的液态玻璃；
 * - 开启/关闭为「放大扩散 + 淡入淡出」动画，缩放原点在右上角，视觉上从三点按钮处扩散；
 * - 卡片打开时会铺一层全屏透明层接管手势，点任意空白处关闭；返回键同样关闭。
 *
 * 由于 [GlassSurface] 的采样依赖布局坐标，这里的缩放保持在很小幅度（0.9 → 1），
 * 动画期间的采样偏移被淡入过程掩盖。
 */
@Composable
fun GlassActionCard(
    backdrop: Backdrop,
    visible: Boolean,
    actions: List<GlassAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp)
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "glassActionCard"
    )
    if (!visible && progress <= 0.001f) return

    BackHandler(enabled = visible) { onDismiss() }
    val scrim = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = scrim, indication = null, onClick = onDismiss)
    )

    GlassSurface(
        backdrop = backdrop,
        modifier = modifier.graphicsLayer {
            alpha = progress
            val scale = lerp(0.9f, 1f, progress)
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(1f, 0f)
        },
        shape = shape
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 184.dp, max = 280.dp)
                .padding(vertical = 6.dp)
        ) {
            actions.forEachIndexed { index, action ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                        color = HmailTheme.colors.outline
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable(enabled = action.enabled) {
                            onDismiss()
                            action.onClick()
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (action.icon != null) {
                        MailIcon(action.icon, tint = HmailTheme.colors.muted)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        Spacer(Modifier.width(36.dp))
                    }
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
