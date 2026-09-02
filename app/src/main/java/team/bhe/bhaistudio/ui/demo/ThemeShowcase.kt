package team.bhe.bhaistudio.ui.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * 主题展示页——用来直观检查配色 / 字阶 / 形状 / 容器层次是否符合预期。
 *
 * 这个页面本身也是一份"取色对照表"：
 * 写业务界面时不确定用哪个颜色，回来看一眼这里就行。
 */
@Composable
fun ThemeShowcase() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        ColorSection()
        ContainerSection()
        TypographySection()
        ShapeSection()
    }
}

// ─────────────────────────────────────────────────────────
// 1. 色角色
// ─────────────────────────────────────────────────────────

@Composable
private fun ColorSection() {
    SectionTitle("色角色 ColorScheme")

    ColorRow("主色组") {
        Swatch("primary", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        Swatch("onPrimary", MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary)
        Swatch("primaryContainer", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    }

    ColorRow("辅助色组") {
        Swatch("secondary", MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
        Swatch("secondaryContainer", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        Swatch("tertiary", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
        Swatch("tertiaryContainer", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
    }

    ColorRow("表面与文本") {
        Swatch("surface", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
        Swatch("surfaceVariant", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        Swatch("background", MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.onBackground)
    }

    ColorRow("错误与描边") {
        Swatch("error", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
        Swatch("errorContainer", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        Swatch("outline", MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surface)
    }
}

// ─────────────────────────────────────────────────────────
// 2. 分级容器色（卡片层次）
// ─────────────────────────────────────────────────────────

@Composable
private fun ContainerSection() {
    SectionTitle("分级容器色 —— 卡片的层次感来源")

    Text(
        text = "同一屏里出现多层信息时，用这一组递进色区分层级，而不是全用 surface。" +
                "数字越小越贴近背景，越大越靠前。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    val scheme = MaterialTheme.colorScheme
    listOf(
        "Lowest" to scheme.surfaceContainerLowest,
        "Low" to scheme.surfaceContainerLow,
        "Container" to scheme.surfaceContainer,
        "High" to scheme.surfaceContainerHigh,
        "Highest" to scheme.surfaceContainerHighest
    ).forEach { (name, color) ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(color)
                .border(1.dp, scheme.outlineVariant, MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "surfaceContainer.$name",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
// 3. 字阶
// ─────────────────────────────────────────────────────────

@Composable
private fun TypographySection() {
    SectionTitle("字阶 Typography")

    TypeSample("displayLarge   — 极短主标题", MaterialTheme.typography.displayLarge)
    TypeSample("displayMedium", MaterialTheme.typography.displayMedium)
    TypeSample("displaySmall", MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(8.dp))
    TypeSample("headlineLarge  — 页面主标题", MaterialTheme.typography.headlineLarge)
    TypeSample("headlineMedium", MaterialTheme.typography.headlineMedium)
    TypeSample("headlineSmall", MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    TypeSample("titleLarge     — 卡片标题", MaterialTheme.typography.titleLarge)
    TypeSample("titleMedium", MaterialTheme.typography.titleMedium)
    TypeSample("titleSmall", MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    TypeSample("bodyLarge      — 聊天气泡正文", MaterialTheme.typography.bodyLarge)
    TypeSample("bodyMedium", MaterialTheme.typography.bodyMedium)
    TypeSample("bodySmall", MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    TypeSample("labelLarge     — 按钮文本", MaterialTheme.typography.labelLarge)
    TypeSample("labelMedium", MaterialTheme.typography.labelMedium)
    TypeSample("labelSmall     — 消息时间戳", MaterialTheme.typography.labelSmall)
}

@Composable
private fun TypeSample(label: String, style: TextStyle) {
    Text(
        text = label,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

// ─────────────────────────────────────────────────────────
// 4. 形状
// ─────────────────────────────────────────────────────────

@Composable
private fun ShapeSection() {
    SectionTitle("形状 Shapes —— Expressive 大圆角")

    val shapes = MaterialTheme.shapes
    listOf(
        "extraSmall  8dp" to shapes.extraSmall,
        "small      12dp" to shapes.small,
        "medium     16dp" to shapes.medium,
        "large      24dp" to shapes.large,
        "extraLarge 32dp" to shapes.extraLarge
    ).forEach { (label, shape) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp, 40.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ─────────────────────────────────────────────────────────
// 通用小组件
// ─────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun ColorRow(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

/**
 * 单个色块：上色下名，文字用对应的 on- 色以保证对比度合规
 */
@Composable
private fun Swatch(
    name: String,
    background: Color,
    foreground: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = background,
            modifier = Modifier.size(76.dp, 56.dp)
        ) {}
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = foreground
        )
    }
}
