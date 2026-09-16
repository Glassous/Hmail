package com.glassous.hmail.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glassous.hmail.MailIcon
import com.glassous.hmail.ui.theme.HmailTheme

/** Shared opaque menu. Motion matches the original thread menu exactly: 200 ms,
 * FastOutSlowIn easing, 0.9–1 scale from the top right, and matching opacity. */
@Composable
fun SelectionActionCard(
    visible: Boolean,
    actions: List<GlassAction>,
    topPadding: Dp,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = visible, onBack = onDismiss)
    val progress = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "actionCard"
    )
    val interaction = remember { MutableInteractionSource() }
    val scrollState = rememberScrollState()
    val hasVisiblePixels by remember { derivedStateOf { progress.value > 0.001f } }
    if (!visible && !hasVisiblePixels) return
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        if (visible) Box(Modifier.fillMaxSize().clickable(
            interactionSource = interaction, indication = null, onClick = onDismiss
        ))
        val availableHeight = (maxHeight - topPadding - bottomInset() - 16.dp).coerceAtLeast(0.dp)
        Surface(
            modifier = Modifier.align(Alignment.TopEnd)
                .padding(top = topPadding, start = 16.dp, end = 16.dp)
                .width(264.dp).heightIn(max = availableHeight)
                .graphicsLayer {
                    val fraction = progress.value
                    alpha = fraction
                    val scale = lerp(0.9f, 1f, fraction)
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(1f, 0f)
                    clip = true
                }
                .then(if (!visible) Modifier.clearAndSetSemantics {} else Modifier)
                .clickable(enabled = visible, interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(24.dp),
            color = HmailTheme.card,
            contentColor = HmailTheme.colors.onSelected
        ) {
            Column(Modifier.verticalScroll(scrollState, enabled = visible).padding(8.dp)) {
                actions.forEach { action ->
                    Surface(
                        onClick = { onDismiss(); action.onClick() },
                        enabled = visible && action.enabled,
                        shape = RoundedCornerShape(16.dp),
                        color = androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.heightIn(min = 48.dp)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            action.icon?.let {
                                MailIcon(it, tint = HmailTheme.colors.onSelected)
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(action.label, style = MaterialTheme.typography.bodyLarge,
                                color = HmailTheme.colors.onSelected.copy(alpha = if (action.enabled) 1f else 0.38f))
                        }
                    }
                }
            }
        }
    }
}
