package team.bhe.bhaistudio.ui.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.bhe.bhaistudio.ui.component.ChatBubble
import team.bhe.bhaistudio.ui.component.ReasoningBubble
import team.bhe.bhaistudio.ui.component.TypingBubble

/**
 * 组件展示页——验证 Material 3 Expressive 组件在本项目主题下的实际观感。
 *
 * 重点看两件事：
 *   1. 所有组件的配色是否自动跟随主题（是，因为全走 colorScheme）
 *   2. 「分段回复」的呈现效果（见 [SegmentReplyDemo]）
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComponentShowcase() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        ButtonSection()
        SegmentedSection()
        BubbleSection()
        SegmentReplyDemo()
    }
}

// ─────────────────────────────────────────────────────────
// 1. 按钮家族
// ─────────────────────────────────────────────────────────

@Composable
private fun ButtonSection() {
    SectionTitle("按钮家族")

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {}) { Text("Filled") }
        FilledTonalButton(onClick = {}) { Text("Tonal") }
        OutlinedButton(onClick = {}) { Text("Outlined") }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedButton(onClick = {}) { Text("Elevated") }
        TextButton(onClick = {}) { Text("Text") }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FloatingActionButton(
            onClick = {},
            modifier = Modifier.size(56.dp)
        ) {
            Text("+", style = MaterialTheme.typography.headlineSmall)
        }
        Text(
            text = "FAB —— 新建联系人 / 群聊入口",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────
// 2. 分段按钮（聊天 / 记忆 / 设置 切换）
// ─────────────────────────────────────────────────────────

@Composable
private fun SegmentedSection() {
    SectionTitle("分段按钮 —— 页面切换")

    var selectedIndex by remember { mutableIntStateOf(0) }
    val options = listOf("聊天", "记忆", "设置")

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { selectedIndex = index },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = options.size
                )
            ) {
                Text(label)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────
// 3. 气泡家族
// ─────────────────────────────────────────────────────────

@Composable
private fun BubbleSection() {
    SectionTitle("气泡家族 —— 全部由 Surface 组装，无自绘")

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            ChatBubble(
                text = "在干嘛呀？",
                isFromUser = true,
                time = "21:03"
            )
            ReasoningBubble(text = "用户语气轻松，像日常寒暄。应该回得自然一点，别太正式。")
            ChatBubble(
                text = "刚写完代码，在摸鱼",
                isFromUser = false,
                time = "21:03"
            )
            ChatBubble(
                text = "要不要一起看会儿剧？",
                isFromUser = false,
                time = "21:03",
                isGrouped = true // 连续消息，圆角收紧成组
            )
            TypingBubble()
        }
    }
}

// ─────────────────────────────────────────────────────────
// 4. 分段回复效果演示
// ─────────────────────────────────────────────────────────

/**
 * 分段回复的静态呈现示意。
 *
 * 真实运行时由 SegmentedReply 调度器逐条插入，每条之间：
 *   delay = sqrt(字数) × 0.6 秒，±30% 抖动，钳制 0.8~6 秒
 * 间隔期间显示 [TypingBubble]，与上一条成组。
 */
@Composable
private fun SegmentReplyDemo() {
    SectionTitle("分段回复 —— 你的核心差异化")

    Text(
        text = "下面的气泡在真机上会「一条一条、带间隔地」出现，而不是一次性刷出来。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp)) {
            ChatBubble(
                text = "今天过得怎么样",
                isFromUser = true,
                time = "21:10"
            )
            ChatBubble(
                text = "还行吧。",
                isFromUser = false,
                time = "21:10"
            )
            ChatBubble(
                text = "上午开了个会，有点无聊。",
                isFromUser = false,
                time = "21:10",
                isGrouped = true
            )
            ChatBubble(
                text = "不过下午把那个 bug 修好了！",
                isFromUser = false,
                time = "21:10",
                isGrouped = true
            )
            ChatBubble(
                text = "你呢？",
                isFromUser = false,
                time = "21:10",
                isGrouped = true
            )
        }
    }
}

// ─────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
}
