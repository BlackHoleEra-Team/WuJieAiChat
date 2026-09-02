package team.bhe.bhaistudio

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.android.ext.android.inject
import team.bhe.bhaistudio.data.repository.AppLanguageState
import team.bhe.bhaistudio.data.repository.SettingsRepository
import team.bhe.bhaistudio.data.repository.ThemeMode
import team.bhe.bhaistudio.data.repository.toLocale
import team.bhe.bhaistudio.ui.App
import team.bhe.bhaistudio.ui.theme.WuJieTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val settingsRepository: SettingsRepository by inject()

    /** 冷启动时按偏好语言创建 Activity context（语言已由 Application 预加载到 AppLanguageState） */
    override fun attachBaseContext(newBase: Context) {
        val locale = AppLanguageState.language.toLocale()
        super.attachBaseContext(
            if (locale == null) newBase else newBase.withLocale(locale)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 主题模式：跟随系统 / 浅色 / 深色，在「通用设置 → 外观」里切换
            val themeMode by settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // 动态取色（Material You 跟随壁纸）：默认开启，
            // 可在「通用设置 → 外观」里关闭，改用品牌薰衣草紫。
            // Android 15+ 上 material3 的 dynamic scheme 自动采用 vivid 色彩。
            val dynamicColor by settingsRepository.dynamicColor
                .collectAsStateWithLifecycle(initialValue = true)

            // 语言：运行时通过 LocalContext 覆盖，切换即时生效。
            // 关键点：不 recreate Activity——导航 backStack 是内存态（remember），
            // recreate 会跳回主页面。用 remember(locale) 换一个 localized Context，
            // stringResource 等随 LocalContext 立即按新语言解析，整个组合树原地保留。
            val appLanguage by settingsRepository.appLanguage
                .collectAsStateWithLifecycle(initialValue = AppLanguageState.language)
            val locale = appLanguage.toLocale()
            val localizedContext = remember(locale) {
                if (locale == null) this@MainActivity
                else this@MainActivity.withLocale(locale)
            }
            // 注意：localizedContext 不是 Activity，会导致依赖
            // LocalActivityResultRegistryOwner 的 rememberLauncherForActivityResult 崩溃
            //（ProfileScreen 选头像处），必须把 Activity 自身作为 owner 一并提供。
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalActivityResultRegistryOwner provides this@MainActivity
            ) {
                WuJieTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                    App()
                }
            }
        }
    }
}

/** 以指定 Locale 重建一个 Context（资源按该语言解析） */
private fun Context.withLocale(locale: Locale): Context =
    createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(locale) }
    )
