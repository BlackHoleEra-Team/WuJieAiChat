package team.bhe.bhaistudio.data.repository

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 进程内当前生效的应用语言。
 *
 * 为什么要单独一份内存快照：
 * MainActivity.attachBaseContext() 需要**同步**读到语言才能 override Locale，
 * 而 DataStore 是异步的。Application.onCreate() 里阻塞读一次写入此状态，
 * 此后设置页切语言时同步更新。
 */
object AppLanguageState {
    @Volatile
    var language: AppLanguage = AppLanguage.SYSTEM
}

/** 该语言对应的 Locale；跟随系统返回 null（不做覆盖） */
fun AppLanguage.toLocale(): Locale? = when (this) {
    AppLanguage.SYSTEM -> null
    AppLanguage.ZH -> Locale.CHINESE
    AppLanguage.EN -> Locale.ENGLISH
    AppLanguage.JA -> Locale.JAPANESE
    AppLanguage.ZH_HK -> Locale("zh", "HK")
    AppLanguage.ZH_TW -> Locale("zh", "TW")
}

/**
 * 以当前**应用语言**重建一个 Context（资源按它解析）。
 *
 * 给没有 Compose LocalContext 的 ViewModel 用：直接 `getApplication().getString()`
 * 拿到的是系统语言（英文模拟器就是英文），会绕过应用内语言设置。
 * 应用内语言为"跟随系统"时原样返回，零开销。
 */
fun Context.withAppLanguage(): Context {
    val locale = AppLanguageState.language.toLocale() ?: return this
    return createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(locale) }
    )
}
