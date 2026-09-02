package team.bhe.bhaistudio.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 无界 · 配色方案
 *
 * 方向：薰衣草紫（主）+ 暖玫瑰（辅）+ 薄荷青（点缀）
 * 情绪：温柔、亲近、有陪伴感——刻意避开 MD3 默认紫 #6750A4 的"工具感"
 *
 * ── 想换配色只改这个文件 ────────────────────────────────
 * 改主色时成对维护这 4 个值即可，其余可先不动：
 *   primary / onPrimary / primaryContainer / onPrimaryContainer
 *
 * 生成工具（保证对比度合规，可直接导出 Compose 代码）：
 *   https://m3.material.io/theme-builder
 * ────────────────────────────────────────────────────────
 */

// ===== 品牌主色板（供需要单独取色的地方使用，例如 Canvas 绘制）=====
val WuJiePrimary = Color(0xFF6B4E9B)    // 薰衣草紫
val WuJieSecondary = Color(0xFF9A5C7A)  // 暖玫瑰
val WuJieTertiary = Color(0xFF4A8B7B)   // 薄荷青

/**
 * 亮色方案
 *
 * 关键约定（业务层照此取色，不要硬编码）：
 *   primary          主按钮、导航激活态
 *   primaryContainer 选中态背景、AI 头像底色
 *   secondaryContainer  用户自己发出的气泡
 *   tertiaryContainer   记忆 / 长期记忆标识
 *   surfaceContainer*   卡片层次（Low < 默认 < High < Highest）
 */
val LightColorScheme = lightColorScheme(
    // 主色
    primary = Color(0xFF6B4E9B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF25005F),

    // 辅助色
    secondary = Color(0xFF9A5C7A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9E4),
    onSecondaryContainer = Color(0xFF3E0026),

    // 点缀色
    tertiary = Color(0xFF4A8B7B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1F3E5),
    onTertiaryContainer = Color(0xFF00201A),

    // 错误态
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    // 背景与表面
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceTint = WuJiePrimary,

    // 反色
    inverseSurface = Color(0xFF322F35),
    inverseOnSurface = Color(0xFFF5EFF7),
    inversePrimary = Color(0xFFD3BCFF),

    // 描边
    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCAC4D0),

    scrim = Color(0xFF000000),

    // 分级容器色——卡片的层次感全靠这 6 个，别偷懒全用 surface
    surfaceDim = Color(0xFFDED8E1),
    surfaceBright = Color(0xFFFEF7FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F2FA),
    surfaceContainer = Color(0xFFF3ECF5),
    surfaceContainerHigh = Color(0xFFEDE7EF),
    surfaceContainerHighest = Color(0xFFE7E1E9)
)

/**
 * 暗色方案
 *
 * 注意 MD3 暗色规则：不是把亮色反过来，
 * 而是"主色变浅、容器变深"——primary 用于文字，primaryContainer 用于深色块。
 */
val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD3BCFF),
    onPrimary = Color(0xFF3A0069),
    primaryContainer = Color(0xFF533480),
    onPrimaryContainer = Color(0xFFEADDFF),

    secondary = Color(0xFFEFB8CB),
    onSecondary = Color(0xFF4E1F36),
    secondaryContainer = Color(0xFF67354D),
    onSecondaryContainer = Color(0xFFFFD9E4),

    tertiary = Color(0xFFB4D9CB),
    onTertiary = Color(0xFF0E3730),
    tertiaryContainer = Color(0xFF2B4F46),
    onTertiaryContainer = Color(0xFFD1F3E5),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF14121A),
    onBackground = Color(0xFFE6E1EA),
    surface = Color(0xFF14121A),
    onSurface = Color(0xFFE6E1EA),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceTint = Color(0xFFD3BCFF),

    inverseSurface = Color(0xFFE6E1EA),
    inverseOnSurface = Color(0xFF322F35),
    inversePrimary = Color(0xFF6B4E9B),

    outline = Color(0xFF948F99),
    outlineVariant = Color(0xFF49454F),

    scrim = Color(0xFF000000),

    surfaceDim = Color(0xFF14121A),
    surfaceBright = Color(0xFF3B3841),
    surfaceContainerLowest = Color(0xFF0F0D15),
    surfaceContainerLow = Color(0xFF1D1B23),
    surfaceContainer = Color(0xFF211F29),
    surfaceContainerHigh = Color(0xFF2B2934),
    surfaceContainerHighest = Color(0xFF36343F)
)
