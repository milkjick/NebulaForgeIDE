package com.nebulaforge.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * NebulaForge IDE 的 MD3 主题包装。
 * 对应开发方案第十章"MD3 主题系统"要求：MaterialTheme 包裹整棵树，
 * 完整注入 ColorScheme / Typography / Shapes；支持亮/暗模式切换。
 *
 * 配色方案说明见 Color.kt 注释：贴近参考截图（ACS App）的浅蓝背景 + 青绿主色风格。
 */

private val LightColors: ColorScheme = lightColorScheme(
    primary = NebulaTeal40,
    onPrimary = Color.White,
    primaryContainer = NebulaBackgroundTop,
    onPrimaryContainer = NebulaTealDeep,
    secondary = NebulaGreen,
    onSecondary = Color.White,
    background = NebulaBackgroundBottom,
    onBackground = NebulaTextPrimary,
    surface = NebulaSurface,
    onSurface = NebulaTextPrimary,
    surfaceVariant = NebulaSurfaceVariant,
    onSurfaceVariant = NebulaTextSecondary,
    outline = Color(0xFFB8CDD6)
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = NebulaTeal80,
    onPrimary = NebulaTealDeep,
    primaryContainer = NebulaTealDeep,
    onPrimaryContainer = NebulaTeal80,
    secondary = NebulaGreen,
    onSecondary = Color.Black,
    background = NebulaDarkBackground,
    onBackground = Color(0xFFDCEEF3),
    surface = NebulaDarkSurface,
    onSurface = Color(0xFFDCEEF3),
    surfaceVariant = NebulaDarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF9FBAC4),
    outline = Color(0xFF3C5661)
)

private val NebulaTypography = Typography(
    // 截图中"打开或创建 / 您的下一个项目"是较大字号的标题，主色高亮
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp)
)

// 截图中卡片、按钮均为明显大圆角风格
private val NebulaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun NebulaForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = NebulaTypography,
        shapes = NebulaShapes,
        content = content
    )
}
