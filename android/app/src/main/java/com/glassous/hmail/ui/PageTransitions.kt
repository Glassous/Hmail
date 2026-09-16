package com.glassous.hmail.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry

/**
 * 共享元素过渡与页面切换动画的统一配置，新页面接入只需要三步：
 *
 * 1. `App.kt` 的 `NavHost` 已在 `SharedTransitionLayout` 内，把 `SharedTransitionScope`
 *    与 `AnimatedVisibilityScope` 传给页面即可；
 * 2. 两端容器各自挂 [sharedPageElement]（同一个键），文字等小元素挂 [sharedTextElement]；
 * 3. 页面切换过渡用 [enterFrom] / [exitTo] 声明"这一段由共享元素连接"，页面本身不再淡变。
 *
 * 所有动画（边界形变、内容交叉、整页淡变）统一为 [PageTransitions.DurationMillis] = 300ms。
 */
object PageTransitions {
    /** 全部过渡动画的统一时长（毫秒）。 */
    const val DurationMillis = 300

    /** 内容交叉与整页淡变共用的动画规格。 */
    val Spec: FiniteAnimationSpec<Float> = tween(DurationMillis, easing = FastOutSlowInEasing)

    /** 共享元素边界（位置与尺寸）的形变动画。 */
    val BoundsTransform = BoundsTransform { _, _ -> tween(DurationMillis, easing = FastOutSlowInEasing) }

    /** 共享元素两端内容的交叉淡变：旧内容先退场，新内容稍后跟进，避免两团内容长时间叠加。 */
    val ContentEnter: EnterTransition = fadeIn(tween(DurationMillis, delayMillis = 60, easing = FastOutSlowInEasing))
    val ContentExit: ExitTransition = fadeOut(tween(DurationMillis - 60, easing = FastOutSlowInEasing))

    /** 未接入共享元素的普通页面切换。 */
    val PageEnter: EnterTransition = fadeIn(Spec)
    val PageExit: ExitTransition = fadeOut(Spec)
}

/** 主页写信入口 ↔ 写邮件页的共享元素键；「写邮件」「继续写信」两个入口各自成组。 */
object ComposeSharedKeys {
    /** 整页容器：按钮玻璃胶囊 ↔ 写邮件页的背景与全部组件。 */
    fun page(draft: Boolean) = "hmail:compose:page:${if (draft) "draft" else "new"}"

    /** 标题：按钮文字 ↔ 写邮件页顶部栏标题。 */
    fun title(draft: Boolean) = "hmail:compose:title:${if (draft) "draft" else "new"}"
}

/**
 * 把一个容器（按钮胶囊、整页背景与组件等）注册为共享元素：300ms 形变 + 内容交叉淡变。
 *
 * 内容按目标边界测量后整体缩放（默认的 `scaleToBounds`），过渡中不重排版，
 * 含文字、表单这类对约束敏感的整页内容也不会挤在一起。
 */
@Composable
fun SharedTransitionScope.sharedPageElement(
    key: Any,
    animatedVisibilityScope: AnimatedVisibilityScope
): Modifier = Modifier.sharedBounds(
    sharedContentState = rememberSharedContentState(key),
    animatedVisibilityScope = animatedVisibilityScope,
    enter = PageTransitions.ContentEnter,
    exit = PageTransitions.ContentExit,
    boundsTransform = PageTransitions.BoundsTransform
)

/**
 * 文字等小元素的共享元素：整体缩放适配目标边界，过渡中不会重排版换行
 * （`resizeMode` 取默认的 `ScaleToBounds`）。
 */
@Composable
fun SharedTransitionScope.sharedTextElement(
    key: Any,
    animatedVisibilityScope: AnimatedVisibilityScope
): Modifier = Modifier.sharedBounds(
    sharedContentState = rememberSharedContentState(key),
    animatedVisibilityScope = animatedVisibilityScope,
    enter = PageTransitions.ContentEnter,
    exit = PageTransitions.ContentExit,
    boundsTransform = PageTransitions.BoundsTransform
)

/** 进入本页：来自 [from] 时由共享元素连接，不做整页淡入；其余来源保持 300ms 淡入。 */
fun AnimatedContentTransitionScope<NavBackStackEntry>.enterFrom(from: String): EnterTransition =
    if (initialState.destination.route == from) EnterTransition.None else PageTransitions.PageEnter

/**
 * 离开本页：去向 [to] 时由共享元素连接，页面内容整体保持可见（不立即销毁、也不淡出），
 * 直到进入与退出过渡全部结束；其余去向保持 300ms 淡出。
 */
fun AnimatedContentTransitionScope<NavBackStackEntry>.exitTo(to: String): ExitTransition =
    if (targetState.destination.route == to) {
        ExitTransition.KeepUntilTransitionsFinished
    } else {
        PageTransitions.PageExit
    }
