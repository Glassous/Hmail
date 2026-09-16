package com.glassous.hmail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Material3 配色未覆盖的应用私有色位（未读/已读底、输入框底、选中态、玻璃着色等）。 */
@Immutable
data class HmailColors(
    val unread: Color,
    val read: Color,
    val field: Color,
    val selected: Color,
    val onSelected: Color,
    val star: Color,
    val outline: Color,
    val muted: Color,
    val glassTint: Color,
    val glassTintAlpha: Float
)

private val LightExtra = HmailColors(
    unread = LightUnread,
    read = LightRead,
    field = LightField,
    selected = LightSelected,
    onSelected = LightOnSelected,
    star = Star,
    outline = LightOutline,
    muted = LightMuted,
    // 玻璃的着色遮罩用页面背景色，让玻璃块与页面同色系、更沉浸。
    glassTint = LightBackground,
    glassTintAlpha = 0.55f
)

private val DarkExtra = HmailColors(
    unread = DarkUnread,
    read = DarkRead,
    field = DarkField,
    selected = DarkSelected,
    onSelected = DarkOnSelected,
    star = Star,
    outline = DarkOutline,
    muted = DarkMuted,
    // 玻璃的着色遮罩用页面背景色，让玻璃块与页面同色系、更沉浸。
    glassTint = DarkBackground,
    glassTintAlpha = 0.5f
)

private val LocalHmailColors = staticCompositionLocalOf { LightExtra }

/** 用法：`HmailTheme.colors.selected`。 */
object HmailTheme {
    val colors: HmailColors
        @Composable @ReadOnlyComposable get() = LocalHmailColors.current

    /**
     * 顶部栏呼出的操作卡片的实色底：选中色叠加在页面背景上，保证完全不透明。
     * 邮件详情页底部的回复入口一行复用同一底色，与卡片保持同一视觉。
     */
    val card: Color
        @Composable @ReadOnlyComposable
        get() = colors.selected.compositeOver(MaterialTheme.colorScheme.background).copy(alpha = 1f)
}

private val HmailTypography = Typography().let { base ->
    base.copy(
        headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
        titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
        titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp),
        bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp),
        bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp),
        labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    )
}

private val LightScheme = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = LightSelected,
    onPrimaryContainer = LightOnSelected,
    secondary = Brand,
    onSecondary = Color.White,
    secondaryContainer = LightSelected,
    onSecondaryContainer = LightOnSelected,
    tertiary = Brand,
    tertiaryContainer = LightSelected,
    onTertiaryContainer = LightOnSelected,
    background = LightBackground,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightField,
    onSurfaceVariant = LightMuted,
    surfaceContainerLowest = LightBackground,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightField,
    surfaceContainerHighest = LightField,
    surfaceBright = LightSurface,
    surfaceDim = LightBackground,
    outline = LightOutline,
    outlineVariant = LightOutline,
    error = LightError,
    onError = Color.White
)

private val DarkScheme = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = DarkSelected,
    onPrimaryContainer = DarkOnSelected,
    secondary = Brand,
    onSecondary = Color.White,
    secondaryContainer = DarkSelected,
    onSecondaryContainer = DarkOnSelected,
    tertiary = Brand,
    tertiaryContainer = DarkSelected,
    onTertiaryContainer = DarkOnSelected,
    background = DarkBackground,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkField,
    onSurfaceVariant = DarkMuted,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkField,
    surfaceContainerHighest = DarkField,
    surfaceBright = DarkSurface,
    surfaceDim = DarkBackground,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = DarkError,
    onError = Color.White
)

/**
 * 主题由 `MailModel.theme`（system/light/dark）驱动，不再使用 AppCompatDelegate。
 * 系统栏图标明暗由 [com.glassous.hmail.ui.SystemBars] 同步。
 */
@Composable
fun HmailTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val extra = if (darkTheme) DarkExtra else LightExtra
    val scheme = if (darkTheme) DarkScheme else LightScheme
    CompositionLocalProvider(
        LocalHmailColors provides extra,
        // Compose 的 LocalContentColor 初值是纯黑，不在这里提供的话，未显式指定颜色的文字与图标
        // 会在深色主题下变黑；Material 的 Surface/Scaffold 才会提供它，本应用没有用 Surface 包裹页面。
        LocalContentColor provides scheme.onBackground
    ) {
        MaterialTheme(colorScheme = scheme, typography = HmailTypography, content = content)
    }
}
