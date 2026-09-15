package com.glassous.hmail.ui

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.glassous.hmail.ApiFailure
import com.glassous.hmail.MailModel
import com.glassous.hmail.ui.theme.HmailTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 订阅 [MailModel.revision]。`MailModel` 用普通字段承载状态（非 Compose 状态），
 * 屏幕在顶部读取本值即可让任意字段变化触发整屏重组。
 */
@Suppress("NOTHING_TO_INLINE")
@Composable
inline fun revisionOf(model: MailModel): Int = model.revision.collectAsState().value

@Composable
fun HmailApp(model: MailModel, activity: ComponentActivity) {
    val revision = revisionOf(model)
    val dark = when (model.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    HmailTheme(darkTheme = dark) {
        SystemBars(dark)
        // 首帧由本地会话决定：有会话直接进主页，进入后再加载数据。
        val startRoute = remember { if (model.loggedIn) Routes.Inbox else Routes.Login }
        val navController = rememberNavController()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val entry by navController.currentBackStackEntryAsState()
        val route = entry?.destination?.route ?: startRoute

        LaunchedEffect(model) {
            model.events.collect { snackbar.showSnackbar(it, actionLabel = "关闭") }
        }

        // 会话恢复/失效时纠正落地页；恢复期间不做判定，避免误跳登录页。
        LaunchedEffect(revision, route) {
            if (model.starting) return@LaunchedEffect
            val user = model.user
            when {
                user != null && route in Routes.auth -> navController.resetTo(Routes.Inbox)
                user == null && route !in Routes.auth -> navController.resetTo(Routes.Login)
            }
        }

        // 邮件只在前台手动刷新或冷启动时同步，不做定时轮询；这里只跟进 OAuth 回跳票据。
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(owner) {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (model.user != null && model.vault.read("oauth") != null) {
                        try {
                            model.checkOauth()
                        } catch (e: Exception) {
                            if (e is ApiFailure && e.code == "unauthorized") model.fail(e)
                        }
                    }
                    delay(3000)
                }
            }
        }

        BackHandler(enabled = true) {
            val current = navController.currentDestination?.route
            when {
                drawerState.isOpen -> scope.launch { drawerState.close() }
                current == Routes.Inbox && model.selected.isNotEmpty() -> {
                    model.selected.clear()
                    model.changed()
                }
                current == Routes.Compose -> {
                    // 上传、保存或发送进行中不响应返回，避免草稿状态与界面不一致。
                    if (!model.uploading && !model.composeBusy && !model.busy) {
                        model.work {
                            model.closeCompose()
                            navController.popBackStack()
                        }
                    }
                }
                // 栈底页面直接退出：只弹栈会在原地留下空白页。
                current in Routes.root -> activity.finish()
                !navController.popBackStack() -> activity.finish()
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = route == Routes.Inbox,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(drawerWidth()),
                    drawerContainerColor = MaterialTheme.colorScheme.background
                ) {
                    DrawerContent(
                        model = model,
                        onClose = { scope.launch { drawerState.close() } },
                        onNavigate = { target -> navController.navigate(target) }
                    )
                }
            }
        ) {
            Box(Modifier.fillMaxSize()) {
                NavHost(navController = navController, startDestination = startRoute) {
                    composable(Routes.Login) {
                        AuthScreen(model, "login") { target -> navController.navigate(target) }
                    }
                    composable(Routes.Register) {
                        AuthScreen(model, "register") { target -> navController.navigate(target) }
                    }
                    composable(Routes.Reset) {
                        AuthScreen(model, "reset") { target -> navController.resetTo(target) }
                    }
                    composable(Routes.Inbox) {
                        InboxScreen(
                            model = model,
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onSearch = { navController.navigate(Routes.Search) },
                            onOpenThread = { account, thread -> navController.navigate(Routes.thread(account, thread)) },
                            onCompose = { navController.navigate(Routes.Compose) },
                            onConnect = { navController.navigate(Routes.Connect) },
                            onLabelPick = { navController.navigate(Routes.labelPick("")) }
                        )
                    }
                    composable(Routes.Search) {
                        SearchScreen(model) { navController.popBackStack() }
                    }
                    composable(
                        route = Routes.Thread,
                        arguments = listOf(
                            navArgument(Args.Account) { type = NavType.StringType; defaultValue = "" },
                            navArgument(Args.Thread) { type = NavType.StringType; defaultValue = "" }
                        )
                    ) { backStackEntry ->
                        ThreadScreen(
                            model = model,
                            account = backStackEntry.arguments?.getString(Args.Account).orEmpty(),
                            threadId = backStackEntry.arguments?.getString(Args.Thread).orEmpty(),
                            onBack = { navController.popBackStack() },
                            onCompose = { navController.navigate(Routes.Compose) },
                            onLabelPick = { navController.navigate(Routes.labelPick(backStackEntry.arguments?.getString(Args.Thread).orEmpty())) }
                        )
                    }
                    composable(Routes.Compose) {
                        ComposeScreen(
                            model = model,
                            onBack = { navController.popBackStack() },
                            onOpenSent = { navController.resetTo(Routes.Inbox) }
                        )
                    }
                    composable(Routes.Accounts) {
                        AccountsScreen(model, onBack = { navController.popBackStack() }, onConnect = { navController.navigate(Routes.Connect) })
                    }
                    composable(Routes.Connect) {
                        ConnectScreen(model, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.Settings) {
                        SettingsScreen(
                            model = model,
                            onBack = { navController.popBackStack() },
                            onNavigate = { target -> navController.navigate(target) }
                        )
                    }
                    composable(Routes.Password) {
                        PasswordScreen(model, onBack = { navController.popBackStack() })
                    }
                    composable(Routes.Labels) {
                        LabelsScreen(
                            model = model,
                            onBack = { navController.popBackStack() },
                            onEdit = { id -> navController.navigate(Routes.labelEdit(id)) }
                        )
                    }
                    composable(
                        route = Routes.LabelEdit,
                        arguments = listOf(navArgument(Args.Label) { type = NavType.StringType; defaultValue = "" })
                    ) { backStackEntry ->
                        LabelEditScreen(
                            model = model,
                            labelId = backStackEntry.arguments?.getString(Args.Label).orEmpty(),
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = Routes.LabelPick,
                        arguments = listOf(navArgument(Args.Thread) { type = NavType.StringType; defaultValue = "" })
                    ) { backStackEntry ->
                        LabelPickScreen(
                            model = model,
                            threadId = backStackEntry.arguments?.getString(Args.Thread).orEmpty(),
                            onBack = { navController.popBackStack() },
                            onCreateLabel = { navController.navigate(Routes.labelEdit("")) }
                        )
                    }
                }
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(16.dp)
                )
            }
        }
    }
}

/** 清空回退栈后落到目标页，等价于重构前的 `popUpTo(R.id.routes, true)`。 */
fun NavHostController.resetTo(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/** 系统栏图标明暗跟随主题，edge-to-edge 由 `enableEdgeToEdge()` 打开。 */
@Composable
private fun SystemBars(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}
