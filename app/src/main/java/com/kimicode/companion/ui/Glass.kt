package com.kimicode.companion.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * v0.5.10 玻璃 / 模糊效果开关（由「样式 → 减少模糊」驱动）。
 * - true（减少模糊 = 关闭）：启用更多模糊 —— 顶栏高斯动态毛玻璃、
 *   界面切换动态模糊、玻璃拟态卡片等（Android 12+ 生效，低版本自动退化）
 * - false（减少模糊 = 开启）：无任何附加模糊（v0.5.10 起页面切换模糊也一并关闭）
 */
val LocalGlassEffects = staticCompositionLocalOf { false }

/** 当前设备是否支持 RenderEffect 模糊（Android 12+） */
val supportsBlur: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 进出场动态模糊 graphicsLayer 装饰：
 * [blurRadiusDp] 为当前模糊半径（通常来自 [enterExitBlurProgress] 的返回值）。
 * 半径接近 0 时移除 RenderEffect，静止状态零开销。
 */
fun Modifier.dynamicBlur(blurRadiusDp: Float): Modifier = graphicsLayer {
    if (blurRadiusDp > 0.02f && supportsBlur) {
        val r = blurRadiusDp.dp.toPx()
        renderEffect = androidx.compose.ui.graphics.BlurEffect(r, r)
    } else {
        renderEffect = null
    }
}

/**
 * 玻璃拟态卡片底（「减少模糊」关闭时）：
 * 半透明底色 + 柔光渐变 + 细描边，比常规 surface 更「精致玻璃」。
 * 返回背景色 alpha 由调用方组合使用。
 */
@Composable
fun glassCardAlpha(base: Float): Float =
    if (LocalGlassEffects.current) (base * 0.82f).coerceAtLeast(0.18f) else base
