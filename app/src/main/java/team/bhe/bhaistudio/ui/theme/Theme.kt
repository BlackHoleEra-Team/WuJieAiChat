package team.bhe.bhaistudio.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 无界 · 应用主题入口
 *
 * 三件事在这里定死，业务层不再操心：
 *   1. 配色（ColorScheme）  —— 品牌色 or 动态取色
 *   2. 形状（Shapes）       —— Expressive 大圆角
 *   3. 动效（MotionScheme） —— Expressive 弹性动效
 *
 * 用法：
 *   WuJieTheme { App() }
 *
 * ── 关于 Material 3 Expressive ──────────────────────────
 * Expressive 相比标准 MD3 的差异：
 *   · 更大的圆角（见 Shape.kt）
 *   · 更有弹性的动效（MotionScheme.expressive()）
 *   · 更丰富的形状/尺寸变体（ButtonGroup、SplitButton、LoadingIndicator…）
 *
 * ⚠️ androidx.compose.material3 目前（1.5.0-alpha27）仍在 alpha，
 *    Expressive 相关 API 需要 @OptIn(ExperimentalMaterial3ExpressiveApi::class)。
 *    若升级后 MaterialExpressiveTheme 签名变化，最小降级方案：
 *      把 MaterialExpressiveTheme(...) 换成 MaterialTheme(...)，
 *      删掉 motionScheme 参数即可，其余配色/字阶/形状完全通用。
 * ────────────────────────────────────────────────────────
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WuJieTheme(
    /** 是否暗色，默认跟随系统 */
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * 是否启用动态取色（Material You）
     * Android 12+ 从壁纸取色，每个用户看到的都不一样。
     * 置为 false 则始终使用品牌薰衣草紫。
     */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    // dynamicLightColorScheme / dynamicDarkColorScheme 从系统壁纸资源提取颜色：
    //   · Android 12–14（API 31–34）→ 标准 Material You 取色
    //   · Android 15+（API 35+）→ 系统默认采用 vivid 色调（新版动态取色），
    //     material3 的 dynamic scheme 自动跟随，无需额外 API。
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        // Expressive 的灵魂之一：弹性动效（比 MotionScheme.standard() 更活泼）
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
