package com.kimicode.companion.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kimicode.companion.data.StyleSettings
import com.kimicode.companion.data.ThemePalette

// ================= Kimi 靛蓝（品牌默认 · 原 v0.5.8 配色） =================

private val IndigoLight = lightColorScheme(
    primary = Color(0xFF3355C8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF001159),
    secondary = Color(0xFF4355A8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEE1FF),
    onSecondaryContainer = Color(0xFF00105C),
    tertiary = Color(0xFF6B5FA0),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8DEFF),
    onTertiaryContainer = Color(0xFF241557),
    surface = Color(0xFFFDF8FF),
    onSurface = Color(0xFF1A1B23),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceTint = Color(0xFF3355C8),
    outline = Color(0xFF767680)
)

private val IndigoDark = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF0A1B64),
    primaryContainer = Color(0xFF1B3374),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = Color(0xFFBAC3FF),
    onSecondary = Color(0xFF12218A),
    secondaryContainer = Color(0xFF2A3990),
    onSecondaryContainer = Color(0xFFDEE1FF),
    tertiary = Color(0xFFC8BEFF),
    onTertiary = Color(0xFF2F225E),
    tertiaryContainer = Color(0xFF463875),
    onTertiaryContainer = Color(0xFFE8DEFF),
    surface = Color(0xFF121319),
    onSurface = Color(0xFFE4E1EC),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceTint = Color(0xFFAAC7FF),
    outline = Color(0xFF90909A)
)

// ================= 翠绿（经典绿） =================

private val EmeraldLight = lightColorScheme(
    primary = Color(0xFF006C4C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF89F8C6),
    onPrimaryContainer = Color(0xFF002115),
    secondary = Color(0xFF4C6358),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFE9DA),
    onSecondaryContainer = Color(0xFF092016),
    tertiary = Color(0xFF3F6474),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC2E9FC),
    onTertiaryContainer = Color(0xFF001E28),
    surface = Color(0xFFF5FBF6),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DD),
    onSurfaceVariant = Color(0xFF404943),
    surfaceTint = Color(0xFF006C4C),
    outline = Color(0xFF707973)
)

private val EmeraldDark = darkColorScheme(
    primary = Color(0xFF6DDCAC),
    onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF005237),
    onPrimaryContainer = Color(0xFF89F8C6),
    secondary = Color(0xFFB4CCBC),
    onSecondary = Color(0xFF20322A),
    secondaryContainer = Color(0xFF364B40),
    onSecondaryContainer = Color(0xFFCFE9DA),
    tertiary = Color(0xFFA5CCE1),
    onTertiary = Color(0xFF0B1E29),
    tertiaryContainer = Color(0xFF224859),
    onTertiaryContainer = Color(0xFFC2E9FC),
    surface = Color(0xFF0F1511),
    onSurface = Color(0xFFDEE3DC),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C1),
    surfaceTint = Color(0xFF6DDCAC),
    outline = Color(0xFF89938B)
)

// ================= 琥珀（经典暖金） =================

private val AmberLight = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDF9E),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = Color(0xFF6C5C3E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E1BD),
    onSecondaryContainer = Color(0xFF241A04),
    tertiary = Color(0xFF4E6478),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD2EAF8),
    onTertiaryContainer = Color(0xFF061E2C),
    surface = Color(0xFFFFF8F0),
    onSurface = Color(0xFF1E1B13),
    surfaceVariant = Color(0xFFEDE2D0),
    onSurfaceVariant = Color(0xFF4B4639),
    surfaceTint = Color(0xFF7A5900),
    outline = Color(0xFF7E7660)
)

private val AmberDark = darkColorScheme(
    primary = Color(0xFFF5BE49),
    onPrimary = Color(0xFF402D00),
    primaryContainer = Color(0xFF5C4300),
    onPrimaryContainer = Color(0xFFFFDF9E),
    secondary = Color(0xFFD9C6A8),
    onSecondary = Color(0xFF3B2F15),
    secondaryContainer = Color(0xFF52452A),
    onSecondaryContainer = Color(0xFFF5E1BD),
    tertiary = Color(0xFFB1CBE3),
    onTertiary = Color(0xFF1A3243),
    tertiaryContainer = Color(0xFF324B5B),
    onTertiaryContainer = Color(0xFFD2EAF8),
    surface = Color(0xFF191510),
    onSurface = Color(0xFFEDE1D0),
    surfaceVariant = Color(0xFF4B4639),
    onSurfaceVariant = Color(0xFFD0C7B5),
    surfaceTint = Color(0xFFF5BE49),
    outline = Color(0xFF9A917E)
)

// ================= 玫瑰（经典玫红） =================

private val RoseLight = lightColorScheme(
    primary = Color(0xFF9C4350),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD9),
    onPrimaryContainer = Color(0xFF3F0713),
    secondary = Color(0xFF77565B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9DD),
    onSecondaryContainer = Color(0xFF2C1519),
    tertiary = Color(0xFF75592F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDFA6),
    onTertiaryContainer = Color(0xFF271800),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF221919),
    surfaceVariant = Color(0xFFF3DDDD),
    onSurfaceVariant = Color(0xFF524343),
    surfaceTint = Color(0xFF9C4350),
    outline = Color(0xFF857373)
)

private val RoseDark = darkColorScheme(
    primary = Color(0xFFFFB2BB),
    onPrimary = Color(0xFF65101E),
    primaryContainer = Color(0xFF802938),
    onPrimaryContainer = Color(0xFFFFDAD9),
    secondary = Color(0xFFE6BDC0),
    onSecondary = Color(0xFF44292D),
    secondaryContainer = Color(0xFF5D3F43),
    onSecondaryContainer = Color(0xFFFFD9DD),
    tertiary = Color(0xFFE3C46D),
    onTertiary = Color(0xFF3F2D00),
    tertiaryContainer = Color(0xFF5B4300),
    onTertiaryContainer = Color(0xFFFFDFA6),
    surface = Color(0xFF1A1112),
    onSurface = Color(0xFFF0DEDF),
    surfaceVariant = Color(0xFF524343),
    onSurfaceVariant = Color(0xFFD8C3C3),
    surfaceTint = Color(0xFFFFB2BB),
    outline = Color(0xFFA18C8C)
)

/** 各经典配色的明 / 暗色 ColorScheme（供主题与样式设置预览共用） */
val ThemePalette.lightScheme: ColorScheme
    get() = when (this) {
        ThemePalette.INDIGO -> IndigoLight
        ThemePalette.EMERALD -> EmeraldLight
        ThemePalette.AMBER -> AmberLight
        ThemePalette.ROSE -> RoseLight
    }

val ThemePalette.darkScheme: ColorScheme
    get() = when (this) {
        ThemePalette.INDIGO -> IndigoDark
        ThemePalette.EMERALD -> EmeraldDark
        ThemePalette.AMBER -> AmberDark
        ThemePalette.ROSE -> RoseDark
    }

/**
 * MD3 主题（v0.5.9 升级）：
 * - 动态取色开启（默认，Android 12+）：跟随系统壁纸配色（Material You）
 * - 动态取色关闭或低版本：使用四种经典配色之一（靛蓝 / 翠绿 / 琥珀 / 玫瑰）
 * 由 [MainActivity] 收集样式设置后传入 [style]，修改即时生效。
 */
@Composable
fun KimiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    style: StyleSettings = StyleSettings(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = when {
        style.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> style.palette.darkScheme
        else -> style.palette.lightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
