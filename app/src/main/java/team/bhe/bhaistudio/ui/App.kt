package team.bhe.bhaistudio.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavEntry
import team.bhe.bhaistudio.R
import androidx.navigation3.ui.NavDisplay
import team.bhe.bhaistudio.ui.screen.about.AboutScreen
import team.bhe.bhaistudio.ui.screen.chat.ChatScreen
import team.bhe.bhaistudio.ui.screen.chatsettings.ChatLogsScreen
import team.bhe.bhaistudio.ui.screen.chatsettings.ChatSettingsScreen
import team.bhe.bhaistudio.ui.screen.contact.ContactEditScreen
import team.bhe.bhaistudio.ui.screen.contacts.ContactListScreen
import team.bhe.bhaistudio.ui.screen.home.ChatListScreen
import team.bhe.bhaistudio.ui.screen.memory.MemoryScreen
import team.bhe.bhaistudio.ui.screen.profile.AvatarCropScreen
import team.bhe.bhaistudio.ui.screen.profile.ProfileScreen
import team.bhe.bhaistudio.ui.screen.providers.ProviderSettingsScreen
import team.bhe.bhaistudio.ui.screen.recycled.RecycledScreen
import team.bhe.bhaistudio.ui.screen.scan.ScanScreen
import team.bhe.bhaistudio.ui.screen.search.SearchSettingsScreen
import team.bhe.bhaistudio.ui.screen.settings.SettingsScreen
import team.bhe.bhaistudio.ui.screen.transfer.ExportScreen
import team.bhe.bhaistudio.ui.screen.transfer.ImportScreen
import team.bhe.bhaistudio.ui.screen.transfer.TransferScreen
import team.bhe.bhaistudio.ui.screen.web.WebViewScreen

/**
 * 根导航
 *
 * 基于 Navigation3 的 NavDisplay（与官方示例 Socialite 同架构）：
 *   · backStack 模型，内置页面过渡动画与预测返回（Predictive Back）
 *   · 底部三个 Tab 常驻在 Home 里；其余页面作为独立栈条目压栈
 */
sealed interface Destination {
    data object Home : Destination
    data class Chat(val contactId: String) : Destination
    /**
     * 角色编辑 / 新建。
     *
     * @param contactId null = 新建
     * @param importJson 扫码导入的角色卡片 JSON（含 `"wujie":"contact"`），
     *   非空时打开页面即用卡片数据预填表单
     */
    data class ContactEdit(
        val contactId: String? = null,
        val importJson: String? = null
    ) : Destination
    data object Providers : Destination
    data class Memory(val contactId: String) : Destination
    data object Recycled : Destination
    data object Settings : Destination
    data object About : Destination
    data class CropAvatar(val imageUri: String) : Destination

    /** 对话设置（聊天页 ⋮ 进入，<Name> - 设置），区别于角色编辑 */
    data class ChatSettings(val contactId: String) : Destination

    /** 聊天记录管理（对话设置进入） */
    data class ChatLogs(val contactId: String) : Destination

    /** 网络搜索设置（自定义联网搜索 API） */
    data object SearchSettings : Destination

    /** 扫一扫（主页菜单进入）：网页走内置 WebView，无界角色卡片走创建页 */
    data object Scan : Destination

    /** 内置网页浏览（扫码打开链接） */
    data class WebView(val url: String) : Destination

    /** 数据迁移（设置进入）：导出 / 导入选择 */
    data object Transfer : Destination

    /** 数据迁移 - 导入（接收方） */
    data object TransferImport : Destination

    /** 数据迁移 - 导出（发送方） */
    data object TransferExport : Destination
}

/** 底部三个主 Tab（标签走 string resource，便于本地化） */
enum class MainTab(@StringRes val labelRes: Int, val icon: ImageVector) {
    CHAT(R.string.tab_chat, Icons.AutoMirrored.Filled.Chat),
    CONTACTS(R.string.tab_contacts, Icons.Default.Star),
    PROFILE(R.string.tab_profile, Icons.Default.Person)
}

/**
 * MD3 标准缓动曲线（The motion system · Motion tokens）
 *
 *   emphasized-decelerate  cubic-bezier(0.05, 0.7, 0.1, 1.0)
 *   emphasized-accelerate  cubic-bezier(0.3, 0.0, 0.8, 0.15)
 */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

@Composable
fun App() {
    // Navigation3 NavDisplay：backStack 即导航栈，预测返回动画由库内置
    val backStack = remember { mutableStateListOf<Destination>(Destination.Home) }
    // 底部 Tab 状态提升到顶层：Home 从组合移除再回来时不会丢当前位置
    var currentTab by remember { mutableStateOf(MainTab.CHAT) }
    // 头像裁切结果回调槽：ProfileScreen / ContactEditScreen 注册，CropAvatar 完成后分发
    var avatarCropCallback by remember { mutableStateOf<(String) -> Unit>({}) }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    NavDisplay(
        backStack = backStack,
        onBack = { pop() },
        modifier = Modifier.fillMaxSize(),
        // MD3 Shared X-axis：前进时新页从右侧进入，旧页向左轻退 1/4
        transitionSpec = {
            (slideInHorizontally(tween(450, easing = EmphasizedDecelerate)) { it } +
                    fadeIn(tween(450, easing = EmphasizedDecelerate)))
                .togetherWith(
                    slideOutHorizontally(tween(450, easing = EmphasizedAccelerate)) { -it / 4 } +
                            fadeOut(tween(450))
                )
        },
        // 返回：Android 16 原生风格——上一级从左侧轻微滑入并放大，
        // 当前页全宽右移并缩小（模拟系统返回手势的"页面被推走"）
        popTransitionSpec = {
            (slideInHorizontally(tween(450, easing = EmphasizedDecelerate)) { -it / 4 } +
                    scaleIn(tween(450, easing = EmphasizedDecelerate), initialScale = 0.94f) +
                    fadeIn(tween(450, easing = EmphasizedDecelerate)))
                .togetherWith(
                    slideOutHorizontally(tween(450, easing = EmphasizedAccelerate)) { it } +
                            scaleOut(tween(450, easing = EmphasizedAccelerate), targetScale = 0.9f) +
                            fadeOut(tween(450))
                )
        },
        // 预测返回：与返回同向，手势进度驱动（跟手），同样带缩放更贴近系统原生
        predictivePopTransitionSpec = { _ ->
            (slideInHorizontally(tween(450, easing = EmphasizedDecelerate)) { -it / 4 } +
                    scaleIn(tween(450, easing = EmphasizedDecelerate), initialScale = 0.94f) +
                    fadeIn(tween(450, easing = EmphasizedDecelerate)))
                .togetherWith(
                    slideOutHorizontally(tween(450, easing = EmphasizedAccelerate)) { it } +
                            scaleOut(tween(450, easing = EmphasizedAccelerate), targetScale = 0.9f) +
                            fadeOut(tween(450))
                )
        },
        entryProvider = { dest ->
            NavEntry(dest) {
                when (dest) {
                    is Destination.Home -> HomeContent(
                        currentTab = currentTab,
                        onSelectTab = { currentTab = it },
                        onNavigate = { backStack.add(it) },
                        onAvatarCropRequested = { uri, cb ->
                            avatarCropCallback = cb
                            backStack.add(Destination.CropAvatar(uri))
                        }
                    )

                    is Destination.Chat -> ChatScreen(
                        contactId = dest.contactId,
                        onBack = ::pop,
                        onOpenSettings = { contactId -> backStack.add(Destination.ChatSettings(contactId)) },
                        onOpenProviders = { backStack.add(Destination.Providers) }
                    )

                    is Destination.ChatSettings -> ChatSettingsScreen(
                        contactId = dest.contactId,
                        onBack = ::pop,
                        onOpenChatLogs = { backStack.add(Destination.ChatLogs(dest.contactId)) }
                    )

                    is Destination.ChatLogs -> ChatLogsScreen(
                        contactId = dest.contactId,
                        onBack = ::pop
                    )

                    is Destination.ContactEdit -> ContactEditScreen(
                        contactId = dest.contactId,
                        importJson = dest.importJson,
                        onBack = ::pop,
                        onSaved = ::pop,
                        onCropRequested = { uri, cb ->
                            avatarCropCallback = cb
                            backStack.add(Destination.CropAvatar(uri))
                        },
                        onOpenSearchSettings = { backStack.add(Destination.SearchSettings) },
                        onOpenProviders = { backStack.add(Destination.Providers) }
                    )

                    is Destination.Providers -> ProviderSettingsScreen(onBack = ::pop)

                    is Destination.Memory -> MemoryScreen(
                        contactId = dest.contactId,
                        onBack = ::pop
                    )

                    is Destination.Recycled -> RecycledScreen(onBack = ::pop)

                    is Destination.Settings -> SettingsScreen(
                        onBack = ::pop,
                        onSearchSettings = { backStack.add(Destination.SearchSettings) },
                        onTransfer = { backStack.add(Destination.Transfer) }
                    )

                    is Destination.Transfer -> TransferScreen(
                        onBack = ::pop,
                        onImport = { backStack.add(Destination.TransferImport) },
                        onExport = { backStack.add(Destination.TransferExport) }
                    )

                    is Destination.TransferImport -> ImportScreen(onBack = ::pop)

                    is Destination.TransferExport -> ExportScreen(onBack = ::pop)

                    is Destination.SearchSettings -> SearchSettingsScreen(onBack = ::pop)

                    is Destination.Scan -> ScanScreen(
                        onBack = ::pop,
                        onOpenUrl = { url -> backStack.add(Destination.WebView(url)) },
                        onCreateContact = { json ->
                            backStack.add(Destination.ContactEdit(importJson = json))
                        }
                    )

                    is Destination.WebView -> WebViewScreen(
                        url = dest.url,
                        onBack = ::pop
                    )

                    is Destination.About -> AboutScreen(onBack = ::pop)

                    is Destination.CropAvatar -> AvatarCropScreen(
                        imageUri = dest.imageUri,
                        onCropped = { uri ->
                            avatarCropCallback(uri)
                            avatarCropCallback = {}
                        },
                        onBack = ::pop
                    )
                }
            }
        }
    )
}

/** 首页：底部 Tab + 三个主页面（同级用 Fade Through 过渡） */
@Composable
private fun HomeContent(
    currentTab: MainTab,
    onSelectTab: (MainTab) -> Unit,
    onNavigate: (Destination) -> Unit,
    onAvatarCropRequested: (String, (String) -> Unit) -> Unit
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
            MainTab.entries.forEach { tab ->
                NavigationBarItem(
                    selected = currentTab == tab,
                    onClick = { onSelectTab(tab) },
                    icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                    label = { Text(stringResource(tab.labelRes)) }
                )
            }
            }
        },
        // ⚠️ 关键：外层不处理顶部 insets——内层每个 tab 页面自带 TopAppBar（含状态栏），
        // 若这里再 padding 一次状态栏高度，顶部会多出一倍空白（"主页面和通知栏间距大"）。
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // MD3 Fade Through：同级（peer）导航的规范过渡——
            // 进入 0.92→1.0 缩放 + 淡入，退出 1.0→0.92 缩放 + 淡出，同时进行。
            // SaveableStateHolder：切 Tab 时保留各 Tab 的滚动位置等状态，避免重建丢失
            val saveableStateHolder = rememberSaveableStateHolder()
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    (scaleIn(tween(350), initialScale = 0.92f) + fadeIn(tween(350)))
                        .togetherWith(scaleOut(tween(350), targetScale = 0.92f) + fadeOut(tween(350)))
                },
                label = "tab-nav"
            ) { tab ->
                saveableStateHolder.SaveableStateProvider(tab) {
                when (tab) {
                    MainTab.CHAT -> ChatListScreen(
                        onOpenChat = { contactId -> onNavigate(Destination.Chat(contactId)) },
                        onAddContact = { onNavigate(Destination.ContactEdit()) },
                        onScan = { onNavigate(Destination.Scan) }
                    )

                    MainTab.CONTACTS -> ContactListScreen(
                        onOpenChat = { contactId -> onNavigate(Destination.Chat(contactId)) },
                        onEdit = { contactId -> onNavigate(Destination.ContactEdit(contactId)) },
                        onAdd = { onNavigate(Destination.ContactEdit()) },
                        onMemory = { contactId -> onNavigate(Destination.Memory(contactId)) },
                        onScan = { onNavigate(Destination.Scan) },
                        onImportJson = { json ->
                            onNavigate(Destination.ContactEdit(importJson = json))
                        }
                    )

                    MainTab.PROFILE -> ProfileScreen(
                        onProviders = { onNavigate(Destination.Providers) },
                        onSettings = { onNavigate(Destination.Settings) },
                        onAbout = { onNavigate(Destination.About) },
                        onOpenRecycled = { onNavigate(Destination.Recycled) },
                        onAvatarPicked = onAvatarCropRequested
                    )
                }
                }
            }
        }
    }
}
