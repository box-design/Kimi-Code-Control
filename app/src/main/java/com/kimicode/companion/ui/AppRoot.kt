package com.kimicode.companion.ui

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kimicode.companion.KimiApp
import com.kimicode.companion.data.AppSettings

/** 导航目标 */
sealed interface Screen {
    data object Connect : Screen
    data object Console : Screen
    /** 任务详情：sessionId 即目标会话 */
    data class TaskDetail(val sessionId: String) : Screen
    /** v0.5.2：设置页（v0.5.7 升级为主页 + 分类子页结构） */
    data object Settings : Screen
}

/**
 * 应用根导航：连接页 → 控制台（单页）→ 任务详情页 / 通知设置页。
 *
 * v0.5.9 动效全面升级：
 * - 转场位移改为 MD3 emphasized 曲线的「部分位移」（1/5 屏宽）+ 交叉淡入淡出，
 *   进场长尾减速、出场加速离开，非线性观感接近原生平台动效
 * - 「减少模糊」关闭时，进出场页面随转场进度实时动态模糊（RenderEffect），
 *   焦点切换伴随景深变化，页面更精致（Android 12+ 生效）
 */
@Composable
fun AppRoot(
    activity: ComponentActivity,
    initialSettings: AppSettings
) {
    val context = LocalContext.current
    val app = KimiApp.of(context)
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = initialSettings)

    var screen by remember {
        mutableStateOf<Screen>(if (initialSettings.setupDone) Screen.Console else Screen.Connect)
    }

    // v0.5.2：任务通知点击的深度链接（通知携带 session_id → 打开对应详情页）
    LaunchedEffect(Unit) {
        app.openSessionRequests.collect { sessionId ->
            if (sessionId.isNotBlank()) screen = Screen.TaskDetail(sessionId)
        }
    }

    // 返回处理：详情页/设置页 → 控制台；控制台 → 退出（系统返回键由 Activity 默认处理）
    androidx.activity.compose.BackHandler(enabled = screen is Screen.TaskDetail) {
        screen = Screen.Console
    }
    androidx.activity.compose.BackHandler(enabled = screen is Screen.Settings) {
        screen = Screen.Console
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            when {
                (targetState is Screen.TaskDetail || targetState is Screen.Settings) &&
                    initialState is Screen.Console ->
                    slideInHorizontally(
                        animationSpec = tween(
                            durationMillis = KimiMotion.DUR_LONG,
                            easing = KimiMotion.EmphasizedDecelerate
                        )
                    ) { it / 5 } + fadeIn(
                        animationSpec = tween(
                            durationMillis = KimiMotion.DUR_MEDIUM,
                            delayMillis = 50,
                            easing = KimiMotion.Standard
                        )
                    ) togetherWith slideOutHorizontally(
                        animationSpec = tween(
                            durationMillis = 260,
                            easing = KimiMotion.EmphasizedAccelerate
                        )
                    ) { -it / 7 } + fadeOut(
                        animationSpec = tween(
                            durationMillis = KimiMotion.DUR_SHORT,
                            easing = KimiMotion.Standard
                        )
                    )

                targetState is Screen.Console &&
                    (initialState is Screen.TaskDetail || initialState is Screen.Settings) ->
                    slideInHorizontally(
                        animationSpec = tween(
                            durationMillis = KimiMotion.DUR_LONG,
                            easing = KimiMotion.EmphasizedDecelerate
                        )
                    ) { -it / 6 } + fadeIn(
                        animationSpec = tween(
                            durationMillis = KimiMotion.DUR_MEDIUM,
                            delayMillis = 50,
                            easing = KimiMotion.Standard
                        )
                    ) togetherWith slideOutHorizontally(
                        animationSpec = tween(
                            durationMillis = 260,
                            easing = KimiMotion.EmphasizedAccelerate
                        )
                    ) { it / 7 } + fadeOut(
                        animationSpec = tween(
                            durationMillis = KimiMotion.DUR_SHORT,
                            easing = KimiMotion.Standard
                        )
                    )

                else -> fadeIn(
                    animationSpec = tween(KimiMotion.DUR_MEDIUM, easing = KimiMotion.Standard)
                ) togetherWith fadeOut(
                    animationSpec = tween(KimiMotion.DUR_SHORT, easing = KimiMotion.Standard)
                )
            }
        },
        label = "rootNav"
    ) { current ->
        // v0.5.10：界面切换动态模糊 —— 进场由模糊转清晰，出场逐渐模糊；
        // 「减少模糊」开启时 enterExitBlurProgress 直接返回 0，转场不再有任何模糊
        val blurRadius = enterExitBlurProgress(maxRadiusDp = 24f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .dynamicBlur(blurRadius)
        ) {
            when (current) {
                Screen.Connect -> ConnectScreen(initial = settings) {
                    screen = Screen.Console
                }

                Screen.Console -> ConsoleScreen(
                    settings = settings,
                    onChangeServer = { screen = Screen.Connect },
                    onOpenTask = { sessionId -> screen = Screen.TaskDetail(sessionId) },
                    onOpenSettings = { screen = Screen.Settings }
                )

                is Screen.TaskDetail -> TaskDetailScreen(
                    sessionId = current.sessionId,
                    onBack = { screen = Screen.Console }
                )

                Screen.Settings -> SettingsScreen(
                    onBack = { screen = Screen.Console }
                )
            }
        }
    }
}
