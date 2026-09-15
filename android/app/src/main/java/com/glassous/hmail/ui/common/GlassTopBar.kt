package com.glassous.hmail.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.glassous.hmail.MailIcon
import com.glassous.hmail.ui.glass.GlassCircle
import com.glassous.hmail.ui.glass.GlassPill
import com.glassous.hmail.ui.glass.GlassSurface
import com.kyant.backdrop.Backdrop

/** 顶部栏的一个操作项：有图标时渲染成玻璃圆钮，否则渲染成玻璃文字胶囊。 */
data class GlassAction(
    val label: String,
    val icon: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

private val BandHeight = 48.dp
private val GlassStartPadding = 16.dp
private val GlassEndPadding = 16.dp
private val GlassMaxWidth = 220.dp
private val IconSize = 24.dp
private val IconTitleGap = 4.dp
private val TitleGapBelowBand = 8.dp
private val BlockGap = 8.dp
private const val ExpandedTitleSize = 24f
private const val CollapsedTitleSize = 16f

/** 展开态（标题位于第二行大字）时顶栏玻璃的高度。 */
val TopBarExpandedHeight = 96.dp

/** 顶部栏与状态栏安全区之间的额外留白。 */
val TopBarTopGap = 8.dp

/**
 * 统一悬浮玻璃顶部栏，以主页顶栏为标准，可直接推广到其它页面：
 *
 * - 左侧「导航按钮 + 标题」合并在**同一块玻璃**内，图标与标题间距很小；
 * - 页面在顶部（[collapse] 为 0）时标题位于第二行、字号更大、且正好落在导航按钮下方；
 * - 页面滚动时标题平滑折进顶栏，字号收敛到与折叠态一致的 16sp；
 * - 右侧每个操作项**各自独占一块玻璃**，数量多时横向滚动，导航块固定不动。
 *
 * 非折叠页面不传 [collapse] 即可（默认恒为 1，即紧凑态）。
 */
@Composable
fun GlassTopBar(
    backdrop: Backdrop,
    title: String,
    modifier: Modifier = Modifier,
    collapse: State<Float> = remember { mutableStateOf(1f) },
    navigationIcon: String = "menu_lines",
    navigationDescription: String = "打开侧栏",
    onNavigationClick: (() -> Unit)? = null,
    actions: List<GlassAction> = emptyList(),
    topPadding: Dp = 0.dp
) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = topPadding),
        verticalAlignment = Alignment.Top
    ) {
        NavigationTitleGlass(
            backdrop = backdrop,
            title = title,
            collapse = collapse,
            navigationIcon = navigationIcon,
            navigationDescription = navigationDescription,
            onNavigationClick = onNavigationClick,
            tint = onBackground
        )
        Spacer(Modifier.width(BlockGap))
        Box(Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(BlockGap),
                verticalAlignment = Alignment.Top
            ) {
                actions.forEach { action -> GlassActionBlock(backdrop, action) }
            }
        }
    }
}

/**
 * 由列表滚动位置推导 0..1 的折叠进度。
 *
 * `firstVisibleItemScrollOffset` 不包含 contentPadding，列表在顶部时为 0。
 * 这里只创建 `derivedStateOf`，真正的读取发生在顶栏内部，避免整屏随滚动重组。
 */
@Composable
fun rememberCollapseFraction(listState: LazyListState, distance: Dp = 64.dp): State<Float> {
    val distancePx = with(LocalDensity.current) { distance.toPx() }
    return remember(listState, distancePx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / distancePx).coerceIn(0f, 1f)
        }
    }
}

/** 导航按钮 + 标题合并成的单块玻璃，标题随 [collapse] 从第二行大字折进顶栏。 */
@Composable
private fun NavigationTitleGlass(
    backdrop: Backdrop,
    title: String,
    collapse: State<Float>,
    navigationIcon: String,
    navigationDescription: String,
    onNavigationClick: (() -> Unit)?,
    tint: Color
) {
    // 在这里读取折叠进度，重组范围收敛到这一块玻璃。
    val fraction = collapse.value.coerceIn(0f, 1f)
    val titleSize = lerp(ExpandedTitleSize, CollapsedTitleSize, fraction)

    GlassSurface(
        backdrop = backdrop,
        modifier = Modifier.widthIn(max = GlassMaxWidth),
        shape = RoundedCornerShape(26.dp),
        onClick = onNavigationClick
    ) {
        Layout(
            content = {
                MailIcon(navigationIcon, contentDescription = navigationDescription, tint = tint)
                Text(
                    text = title,
                    color = tint,
                    fontSize = titleSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        ) { measurables, constraints ->
            val bandPx = BandHeight.roundToPx()
            val sidePadPx = GlassStartPadding.roundToPx()
            val endPadPx = GlassEndPadding.roundToPx()
            val iconPx = IconSize.roundToPx()
            val gapPx = IconTitleGap.roundToPx()
            val expandPx = TopBarExpandedHeight.roundToPx()

            val icon = measurables[0].measure(Constraints.fixed(iconPx, iconPx))
            val titleLimit = (constraints.maxWidth - sidePadPx - endPadPx - iconPx - gapPx).coerceAtLeast(0)
            val label = measurables[1].measure(Constraints(maxWidth = titleLimit))

            // 展开态标题从玻璃内左边距起排，折叠态排在图标右侧；宽度取两者较大值。
            val inlineWidth = sidePadPx + iconPx + gapPx + label.width
            val stackedWidth = sidePadPx + label.width
            val width = minOf(constraints.maxWidth, maxOf(inlineWidth, stackedWidth) + endPadPx)
            val height = lerp(expandPx.toFloat(), bandPx.toFloat(), fraction).toInt()

            val labelX = lerp(sidePadPx.toFloat(), (sidePadPx + iconPx + gapPx).toFloat(), fraction).toInt()
            val collapsedY = (bandPx - label.height) / 2f
            val expandedY = (bandPx + TitleGapBelowBand.roundToPx()).toFloat()
            val labelY = lerp(expandedY, collapsedY, fraction).toInt()

            layout(width, height) {
                icon.place(sidePadPx, (bandPx - iconPx) / 2)
                label.place(labelX, labelY)
            }
        }
    }
}

@Composable
private fun GlassActionBlock(backdrop: Backdrop, action: GlassAction) {
    if (action.icon != null) {
        GlassCircle(backdrop = backdrop, enabled = action.enabled, onClick = action.onClick) {
            MailIcon(
                action.icon,
                contentDescription = action.label,
                modifier = Modifier.align(Alignment.Center),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    } else {
        GlassPill(
            backdrop = backdrop,
            modifier = Modifier.height(BandHeight),
            enabled = action.enabled,
            onClick = action.onClick
        ) {
            Text(
                action.label,
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
