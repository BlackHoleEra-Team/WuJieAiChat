# 无界 · Android（Material 3 Expressive 模板）

AI 角色陪伴应用的 Android 端。技术栈：**Kotlin + Jetpack Compose + Material 3 Expressive**。

本项目刻意做到一件事——**95% 使用标准 Material 3 组件，不自己画 UI**，
差异化全部通过官方支持的主题系统（配色 / 字阶 / 形状 / 动效）实现。

---

## 运行

用 Android Studio 打开本目录，Sync 后直接 Run。

```bash
# 命令行构建
./gradlew :app:assembleDebug
```

要求：JDK 17+、Android SDK compileSdk 37。

---

## 技术栈

| 项 | 版本 | 说明 |
|---|---|---|
| AGP | 9.3.1 | |
| Kotlin | 2.4.10 | |
| Compose BOM | 2026.08.00 | |
| **Material 3** | **1.5.0-alpha27** | ⚠️ 仍是 alpha |
| compileSdk / targetSdk | 37 | minSdk 26 |
| Room | 2.8.4 | 替代老版的 SharedPreferences |
| Paging 3 | 3.5.1 | 聊天记录分页 |
| Koin | 4.2.2 | 依赖注入 |
| OkHttp + SSE | 5.5.0 | 流式对话 |
| Navigation 3 | 1.1.7 | |

版本号对齐自 RikkaHub 的生产配置，可直接对照参考。

---

## 目录结构

```
app/src/main/java/team/bhe/bhaistudio/
├── MainActivity.kt              # 入口 + 演示外壳（底部两个 Tab）
├── ui/
│   ├── theme/                   # ★ 主题层——差异化全部在这
│   │   ├── Color.kt             #   配色（改这里换风格）
│   │   ├── Type.kt              #   字阶（15 级）
│   │   ├── Shape.kt             #   形状（Expressive 大圆角）
│   │   └── Theme.kt             #   MaterialExpressiveTheme + MotionScheme
│   ├── component/
│   │   └── ChatBubble.kt        # 聊天气泡 / 正在输入 / 思考过程
│   └── demo/                    # 主题 & 组件展示页（可直接删）
│       ├── ThemeShowcase.kt
│       └── ComponentShowcase.kt
└── res/                         # 仅图标与空壳平台主题
```

---

## 怎么改成你自己的风格

### 换配色 → `ui/theme/Color.kt`

只需成对维护主色的 4 个值，其余可先不动：

```kotlin
primary            // 主色
onPrimary          // 主色上的文字/图标
primaryContainer   // 浅色块背景
onPrimaryContainer // 浅色块上的文字
```

推荐用官方工具生成，自动保证对比度合规：
<https://m3.material.io/theme-builder>

### 换字体 → `ui/theme/Type.kt`

1. 字体文件放 `app/src/main/res/font/`
2. 定义 `val AppFontFamily = FontFamily(Font(R.font.xxx))`
3. 把文件里所有 `fontFamily = FontFamily.Default` 替换掉

### 换圆角 → `ui/theme/Shape.kt`

当前是 Expressive 风格的大圆角：

```kotlin
extraSmall = 8.dp / small = 12.dp / medium = 16.dp
large = 24.dp / extraLarge = 32.dp
```

标准 MD3 是 `4 / 8 / 12 / 16 / 28`，想收敛一点可以改回去。

### 关掉动态取色 → `ui/theme/Theme.kt`

```kotlin
WuJieTheme(dynamicColor = false) { ... }
```

Android 12+ 默认开启动态取色（从壁纸取色），每个用户看到的都不一样。
关掉后固定使用品牌薰衣草紫。

---

## 取色约定（写业务界面时遵守）

| 用途 | 用哪个色角色 |
|---|---|
| 主按钮、导航激活态 | `primary` / `onPrimary` |
| AI 头像底色、选中态背景 | `primaryContainer` / `onPrimaryContainer` |
| 用户发出的气泡 | `secondaryContainer` / `onSecondaryContainer` |
| AI 回复的气泡 | `surfaceContainerHigh` / `onSurface` |
| 思考过程 | `tertiaryContainer` / `onTertiaryContainer` |
| 卡片层次 | `surfaceContainerLow` → `High` → `Highest` |
| 时间戳、次要文字 | `onSurfaceVariant` |
| 分割线 | `outlineVariant` |

**不要硬编码颜色值。** 需要新颜色就往 `Color.kt` 里加语义化角色。

---

## 关于聊天气泡

MD3 没有内置的 `ChatBubble` 组件，但 `ui/component/ChatBubble.kt` **仍然是 100% Material 3**：

- 颜色全部取自 `MaterialTheme.colorScheme`
- 形状全部取自 `MaterialTheme.shapes` / `BubbleShapes`
- 文本全部取自 `MaterialTheme.typography`

这是"用 MD3 基础件组装"，不是"自己画"——换主题时气泡会跟着变。

---

## 已知风险

**`androidx.compose.material3:material3:1.5.0-alpha27` 仍是 alpha。**

- Expressive 相关 API 需要 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`
  （已在 `app/build.gradle.kts` 的 `compilerOptions.optIn` 里全局开启）
- 升级版本时 API 可能有变动

**降级方案**：若 `MaterialExpressiveTheme` 签名变化，
把它换成 `MaterialTheme` 并删掉 `motionScheme` 参数即可，
配色 / 字阶 / 形状完全通用。

---

## 下一步

当前是**纯 UI 模板**，没有业务逻辑。建议按此顺序推进：

1. Room 数据层（Contact / Conversation / Message / Memory 表）
2. Provider 抽象（`interface Provider` + `Flow<StreamChunk>`）
3. 聊天页（复用 `ChatBubble`）
4. 分段回复调度器（移植桌面版 `SegmentedReply` 的 `sqrt` 延迟算法）
5. 长期记忆
6. 主动触达（通知）
7. 多 Agent 群聊
