package team.bhe.bhaistudio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 无界 · 形状
 *
 * Material 3 Expressive 相比标准 MD3 的核心区别之一就是**更大的圆角**。
 * 标准 MD3：   4 / 8 / 12 / 16 / 28
 * Expressive： 8 / 12 / 16 / 24 / 32   ← 本文件采用
 *
 * 使用约定（业务层按语义取，不要写具体数值）：
 *   extraSmall  小标签、Chip、Tooltip、Snackbar
 *   small       输入框、小按钮、菜单项
 *   medium      卡片、对话框、下拉菜单
 *   large       大卡片、聊天气泡、底部抽屉
 *   extraLarge  全屏抽屉、大图预览容器
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/**
 * 聊天气泡专用形状
 *
 * 气泡不在 MD3 标准组件里，但用 MD3 的 Shape 系统拼仍然是正统做法。
 * 这里单独定义，方便日后调整气泡观感而不影响其他组件。
 */
object BubbleShapes {
    /** 气泡主体圆角 */
    val radius = 20.dp
    /** 气泡靠向头像一侧的"尾巴"小圆角 */
    val tailRadius = 6.dp
    /** 连续消息之间（同一人连发）的收窄圆角 */
    val groupedRadius = 8.dp
}
