package team.bhe.bhaistudio.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import team.bhe.bhaistudio.R

/**
 * 极简 Markdown 渲染：标题、行内 `code`、**bold**、围栏代码块（含右上角复制按钮）。
 *
 * 为什么不引入 jeziellago/compose-markdown 等三方库：
 *   · 库默认不带代码块独立复制按钮（仅 `isTextSelectable` 走系统长按菜单），
 *     要满足"代码块右上角复制"需自己拦截渲染层，复杂度更高；
 *   · AI 角色扮演聊天场景下需要的语法有限（标题/粗体/行内代码/代码块/段落），
 *     不需要图片、表格、嵌套列表、HTML 等。
 * 自实现 200 行可控，依赖为零。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = buildAnnotated(block.inlines, textColor),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor
                )
                is MdBlock.Heading -> Text(
                    text = block.text,
                    style = headingStyle(block.level),
                    color = textColor
                )
                is MdBlock.Code -> CodeBlockView(code = block.code)
            }
        }
    }
}

/** 快速判断文本是否需要 Markdown 渲染（无标记直接走 Text 避免开销） */
fun looksLikeMarkdown(text: String): Boolean {
    if (text.contains("```")) return true
    if (Regex("""(?m)^#{1,3} """").containsMatchIn(text)) return true
    if (Regex("""\*\*[^*\n]+\*\*""").containsMatchIn(text)) return true
    if (Regex("""`[^`\n]+`""").containsMatchIn(text)) return true
    return false
}

// ─────────────────────────────────────────────────────────
// 解析模型
// ─────────────────────────────────────────────────────────

private sealed interface MdBlock {
    data class Paragraph(val inlines: List<Inline>) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val lang: String?, val code: String) : MdBlock
}

private sealed interface Inline {
    data class Text(val value: String) : Inline
    data class Bold(val value: String) : Inline
    data class Code(val value: String) : Inline
}

/** 块级解析：段落/标题/围栏代码块 */
private fun parseMarkdown(input: String): List<MdBlock> {
    val lines = input.split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paraBuf = mutableListOf<String>()

    fun flushParagraph() {
        if (paraBuf.isEmpty()) return
        val text = paraBuf.joinToString("\n").trimEnd()
        if (text.isNotBlank()) {
            blocks += MdBlock.Paragraph(parseInline(text))
        }
        paraBuf.clear()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                flushParagraph()
                val lang = line.removePrefix("```").trim().ifBlank { null }
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    codeLines += lines[i]; i++
                }
                blocks += MdBlock.Code(lang, codeLines.joinToString("\n"))
                if (i < lines.size) i++ // 跳过闭合 ```
            }
            line.isBlank() -> { flushParagraph(); i++ }
            line.startsWith("# ") -> { flushParagraph(); blocks += MdBlock.Heading(1, line.removePrefix("# ")); i++ }
            line.startsWith("## ") -> { flushParagraph(); blocks += MdBlock.Heading(2, line.removePrefix("## ")); i++ }
            line.startsWith("### ") -> { flushParagraph(); blocks += MdBlock.Heading(3, line.removePrefix("### ")); i++ }
            else -> { paraBuf += line; i++ }
        }
    }
    flushParagraph()
    return blocks
}

/** 行内解析：粗体 **…**、行内代码 `…` */
private val INLINE_REGEX = Regex("""(\*\*[^*\n]+\*\*|`[^`\n]+`)""")
private fun parseInline(text: String): List<Inline> {
    val out = mutableListOf<Inline>()
    var last = 0
    for (m in INLINE_REGEX.findAll(text)) {
        if (m.range.first > last) out += Inline.Text(text.substring(last, m.range.first))
        val token = m.value
        when {
            token.startsWith("**") && token.endsWith("**") ->
                out += Inline.Bold(token.substring(2, token.length - 2))
            token.startsWith("`") && token.endsWith("`") ->
                out += Inline.Code(token.substring(1, token.length - 1))
            else -> out += Inline.Text(token)
        }
        last = m.range.last + 1
    }
    if (last < text.length) out += Inline.Text(text.substring(last))
    return out
}

/** 把行内段组装为带粗体/等宽字体的 AnnotatedString */
private fun buildAnnotated(inlines: List<Inline>, baseColor: Color): AnnotatedString {
    val builder = AnnotatedString.Builder()
    for (item in inlines) {
        when (item) {
            is Inline.Text -> builder.append(item.value)
            is Inline.Bold -> builder.withStyle(
                SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)
            ) { append(item.value) }
            is Inline.Code -> builder.withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, color = baseColor)
            ) { append(item.value) }
        }
    }
    return builder.toAnnotatedString()
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
}

// ─────────────────────────────────────────────────────────
// 代码块视图（深色背景 + 右上角复制按钮）
// ─────────────────────────────────────────────────────────

@Composable
private fun CodeBlockView(code: String) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.surfaceContainerHighest)
    ) {
        // 复制按钮（右上角悬浮）
        IconButton(
            onClick = { copyToClipboard(context, code) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.common_copy),
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = code,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, end = 40.dp, bottom = 10.dp),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = scheme.onSurface
            )
        )
    }
}

/** 复制到系统剪贴板并轻提示 */
private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("wujie_code", text))
    Toast.makeText(context, context.getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
}