package com.kimicode.companion.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * v0.5.9 全局动效规范（Kimi Motion）。
 *
 * 统一「非线性动画 + 回弹」的语言，参照 MD3 表达式动效（Expressive motion）：
 * - 位移 / 进出场：emphasized 系列三次贝塞尔（先急后缓 / 先缓后急），非线性减速
 * - 弹性元件（按压回弹、选项选中、进度落位）：spring 低阻尼（0.6 ~ 0.85），带轻微过冲
 * - 颜色 / 透明度：短 tween 走 emphasized 曲线，避免线性生硬感
 */
object KimiMotion {

    /** MD3 standard：通用进出 / 位移曲线 */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** MD3 emphasized decelerate：进场（快速出发、长尾减速） */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** MD3 emphasized accelerate：出场（缓慢出发、加速离开） */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 轻快减速（小元件 / 颜色过渡） */
    val Prompt = CubicBezierEasing(0.25f, 0.8f, 0.25f, 1f)

    /** 位移弹簧：轻微回弹（列表重排 / 尺寸变化） */
    fun <T> placement(): SpringSpec<T> = spring(
        dampingRatio = 0.78f,
        stiffness = 380f
    )

    /** 回弹弹簧：按压释放 / 选项选中 / 进度落位（可感知过冲） */
    fun <T> bouncy(): SpringSpec<T> = spring(
        dampingRatio = 0.62f,
        stiffness = Spring.StiffnessMedium
    )

    /** 柔和弹簧：颜色类与尺寸类的平滑跟随 */
    fun <T> soft(): SpringSpec<T> = spring(
        dampingRatio = 0.9f,
        stiffness = Spring.StiffnessMediumLow
    )

    /** 常用时长档位（ms） */
    const val DUR_SHORT = 180
    const val DUR_MEDIUM = 280
    const val DUR_LONG = 420
}

/**
 * 回弹按压：按下轻微缩小（0.965），松手以低阻尼 spring 回弹（带过冲）。
 * 用于可点击行 / 卡片 / 选项，配合水波纹反馈。
 */
fun Modifier.bouncyClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.965f,
    onClick: () -> Unit
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = KimiMotion.bouncy(),
        label = "bouncyPress"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = ripple(),
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * 进出场动态模糊进度（0f = 清晰，1f = 最大模糊）。
 * 挂在 [AnimatedVisibilityScope] 内容上：进场时 1 → 0 逐渐清晰，
 * 出场时 0 → 1 逐渐模糊，配合位移产生「焦点切换」的高级感。
 * 仅 Android 12+ 且「减少模糊」关闭时实际生效；
 * v0.5.10：「减少模糊」开启时直接返回 0 —— 页面切换模糊也一并关闭。
 */
@Composable
fun AnimatedVisibilityScope.enterExitBlurProgress(
    maxRadiusDp: Float = 26f
): Float {
    val progress by transition.animateFloat(
        transitionSpec = {
            tween(durationMillis = KimiMotion.DUR_MEDIUM, easing = KimiMotion.Standard)
        },
        label = "enterExitBlur"
    ) { state ->
        if (state == EnterExitState.Visible) 0f else 1f
    }
    return if (LocalGlassEffects.current) progress * maxRadiusDp else 0f
}

/**
 * 阶梯式入场块：首次进入组合时上滑 + 淡入（emphasized 减速曲线），
 * [delayMillis] 控制节奏，让页面元素「依次落位」。
 */
@Composable
fun EntranceBlock(
    delayMillis: Int,
    content: @Composable () -> Unit
) {
    val visibleState = androidx.compose.runtime.remember {
        androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true }
    }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = visibleState,
        enter = androidx.compose.animation.fadeIn(
            animationSpec = tween(
                durationMillis = KimiMotion.DUR_MEDIUM,
                delayMillis = delayMillis,
                easing = KimiMotion.Standard
            )
        ) + androidx.compose.animation.slideInVertically(
            animationSpec = tween(
                durationMillis = KimiMotion.DUR_LONG,
                delayMillis = delayMillis,
                easing = KimiMotion.EmphasizedDecelerate
            )
        ) { it / 6 }
    ) {
        content()
    }
}
