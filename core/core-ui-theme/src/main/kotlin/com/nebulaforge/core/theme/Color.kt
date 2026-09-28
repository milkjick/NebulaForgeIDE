package com.nebulaforge.core.theme

import androidx.compose.ui.graphics.Color

/**
 * 配色取自截图中的 ACS（Android Code Studio）App 视觉风格：
 * 浅蓝渐变背景、青绿色主色调（工具图标、强调色）、白色/浅灰蓝圆角卡片。
 * 对应开发方案第十章 MD3 主题系统要求，以及用户提出的"UI 风格需贴近截图"的要求。
 */

// 主色：青绿色系（截图中"打开或创建"标题、图标主色）
val NebulaTeal40 = Color(0xFF1C7F94)   // 主色，浅色主题下的 primary
val NebulaTeal80 = Color(0xFF7FD4E8)   // 主色，深色主题下的 primary
val NebulaTealDeep = Color(0xFF0F5D6E) // 更深的青绿，用于强调文字/标题

// 背景：浅蓝渐变系（截图整体背景是浅蓝到近白的渐变）
val NebulaBackgroundTop = Color(0xFFDCEEF7)
val NebulaBackgroundBottom = Color(0xFFF7FBFD)
val NebulaSurface = Color(0xFFFFFFFF)
val NebulaSurfaceVariant = Color(0xFFEAF3F8)   // 卡片浅灰蓝底色（截图中"新建项目"等卡片背景）

// 辅助色
val NebulaGreen = Color(0xFF3FA796)     // 图标点缀色（如截图 SDK 管理器图标）
val NebulaTextPrimary = Color(0xFF1A2B32)
val NebulaTextSecondary = Color(0xFF5C7480)

// 深色主题
val NebulaDarkBackground = Color(0xFF0D1B20)
val NebulaDarkSurface = Color(0xFF13262D)
val NebulaDarkSurfaceVariant = Color(0xFF1B3038)
