package team.bhe.bhaistudio.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import java.util.Locale
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import team.bhe.bhaistudio.R
import team.bhe.bhaistudio.ui.theme.BubbleShapes

/**
 * 聊天气泡
 *
 * ── 重要说明 ──────────────────────────────────────────────
 * MD3 没有内置 ChatBubble 组件，但**这个实现 100% 是 Material 3**：
 *   · 颜色 —— 全部取自 MaterialTheme.colorScheme
 *   · 形状 —— 全部取自 MaterialTheme.shapes / BubbleShapes
 *   · 文本 —— 全部取自 MaterialTheme.typography
 * 没有一行硬编码颜色，也没有自定义 Canvas 绘制。
 * 这叫"用 MD3 的基础件组装"，不叫"自己画"。
 * ──────────────────────────────────────────────────────────
 *
 * @param text      气泡内容
 * @param isFromUser true=用户发出（右侧，secondaryContainer）；false=AI 回复（左侧，surfaceContainerHigh）
 * @param time      时间戳文本
 * @param isGrouped 是否与上一条为同一发送者（连续消息时收紧圆角，形成"成组"观感）
 */
/** 气泡旁的头像：uri 为空时显示首字圆形 */
data class ChatAvatar(
    val uri: String? = null,
    val label: String = "?"
)

@Composable
fun ChatBubble(
    text: String,
    isFromUser: Boolean,
    time: String,
    modifier: Modifier = Modifier,
    isGrouped: Boolean = false,
    avatar: ChatAvatar? = null
) {
    val scheme = MaterialTheme.colorScheme

    // 气泡圆角：靠头像一侧收窄，形成"尾巴"
    // 连续消息时该侧进一步收紧，视觉上成组
    val bubbleShape = if (isFromUser) {
        RoundedCornerShape(
            topStart = BubbleShapes.radius,
            topEnd = if (isGrouped) BubbleShapes.groupedRadius else BubbleShapes.radius,
            bottomStart = BubbleShapes.radius,
            bottomEnd = if (isGrouped) BubbleShapes.groupedRadius else BubbleShapes.tailRadius
        )
    } else {
        RoundedCornerShape(
            topStart = if (isGrouped) BubbleShapes.groupedRadius else BubbleShapes.radius,
            topEnd = BubbleShapes.radius,
            bottomStart = if (isGrouped) BubbleShapes.groupedRadius else BubbleShapes.tailRadius,
            bottomEnd = BubbleShapes.radius
        )
    }

    val containerColor = if (isFromUser) {
        scheme.secondaryContainer
    } else {
        scheme.surfaceContainerHigh
    }
    val contentColor = if (isFromUser) {
        scheme.onSecondaryContainer
    } else {
        scheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        // 用户消息靠右，AI 消息靠左
        horizontalArrangement = if (isFromUser) Arrangement.End else Arrangement.Start,
        // 头像对齐气泡侧边顶部
        verticalAlignment = Alignment.Top
    ) {
        if (!isFromUser) {
            // AI 气泡：头像在左侧
            BubbleAvatar(avatar, modifier = Modifier.padding(end = 6.dp))
        }
        Column(
            // 气泡最大宽度限制，避免宽屏上拉成一条线
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (isFromUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = bubbleShape,
                color = containerColor,
                contentColor = contentColor,
                tonalElevation = if (isFromUser) 0.dp else 1.dp
            ) {
                // 含 Markdown 标记（代码块/粗体/行内代码/标题）走增强渲染；
                // 普通文本保持纯 Text，零额外开销。
                if (looksLikeMarkdown(text)) {
                    MarkdownText(
                        markdown = text,
                        textColor = contentColor,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                } else {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        if (isFromUser) {
            // 用户气泡：头像在右侧
            BubbleAvatar(avatar, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** 小圆头像：自定义图片或首字圆形 */
@Composable
private fun BubbleAvatar(avatar: ChatAvatar?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        if (avatar != null && !avatar.uri.isNullOrBlank()) {
            AsyncImage(
                model = avatar.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = avatar?.label?.take(1)?.ifBlank { "?" } ?: "?",
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 「正在输入」气泡
 *
 * 分段回复的核心交互：AI 一条条吐消息时，间隔期间显示这个。
 * 使用 Material 3 Expressive 的 LoadingIndicator —— 官方组件，无需自绘动画。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TypingBubble(
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val labelText = label ?: stringResource(R.string.chat_typing)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = BubbleShapes.radius,
                topEnd = BubbleShapes.radius,
                bottomStart = BubbleShapes.tailRadius,
                bottomEnd = BubbleShapes.radius
            ),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LoadingIndicator(modifier = Modifier.size(20.dp))
                Text(
                    text = labelText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 思考过程气泡（DeepSeek / Kimi 的 reasoning_content）
 *
 * 用 tertiaryContainer 区分于普通回复，暗示"这是过程不是结论"。
 * 点击标题可展开/收起，默认折叠。
 *
 * @param thinkingDone 思考是否完成（开始输出正文即视为完成）。
 *   未完成时标题显示「正在思考…」+ 加载动画，可展开实时查看流式思考；
 *   完成后显示「已完成思考 (Xs)」，X 为思考耗时。
 * @param elapsedSeconds 思考耗时（秒），完成时展示
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReasoningBubble(
    text: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    thinkingDone: Boolean = true,
    elapsedSeconds: Float = 0f
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val title = when {
        !thinkingDone -> stringResource(R.string.reasoning_thinking)
        elapsedSeconds > 0f -> stringResource(
            R.string.reasoning_done_format,
            String.format(Locale.US, "%.1f", elapsedSeconds)
        )
        else -> stringResource(R.string.reasoning_title)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(modifier = Modifier.widthIn(max = 300.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(start = 6.dp, bottom = 4.dp)
            ) {
                if (thinkingDone) {
                    Spacer(modifier = Modifier.size(6.dp))
                } else {
                    LoadingIndicator(modifier = Modifier.size(14.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) {
                        stringResource(R.string.reasoning_collapse)
                    } else {
                        stringResource(R.string.reasoning_expand)
                    },
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Surface(
                    shape = RoundedCornerShape(
                        topStart = BubbleShapes.tailRadius,
                        topEnd = BubbleShapes.radius,
                        bottomStart = BubbleShapes.tailRadius,
                        bottomEnd = BubbleShapes.radius
                    ),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}
