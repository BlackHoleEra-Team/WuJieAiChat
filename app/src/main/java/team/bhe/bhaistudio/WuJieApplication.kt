package team.bhe.bhaistudio

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import team.bhe.bhaistudio.ai.ApiTester
import team.bhe.bhaistudio.data.repository.AppLanguageState
import team.bhe.bhaistudio.data.repository.ProviderConfigRepository
import team.bhe.bhaistudio.data.repository.SettingsRepository
import team.bhe.bhaistudio.data.repository.UserProfileRepository
import team.bhe.bhaistudio.data.transfer.MigrationApplier
import team.bhe.bhaistudio.data.transfer.ProfileFile
import team.bhe.bhaistudio.data.transfer.transferJson
import team.bhe.bhaistudio.di.appModule
import java.io.File

class WuJieApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 跨设备迁移落地：存在待应用迁移包时，必须在 Koin / Room 初始化之前
        // 覆盖数据库与重写密钥，否则 Room 打开旧库会锁住文件。
        MigrationApplier.applyIfPending(this)

        // Coil 3 全局 ImageLoader（AsyncImage 显示头像用），
        // network 组件由 coil-network-okhttp 经 ServiceLoader 自动装配。
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).build()
        }

        startKoin {
            androidLogger()
            androidContext(this@WuJieApplication)
            modules(appModule)
        }

        // 语言偏好预加载到内存：MainActivity.attachBaseContext 需要同步读取。
        // 阻塞一次 Application.onCreate（DataStore 单键读取通常 <50ms），
        // 保证第一个 Activity 创建前语言一定就绪，避免冷启动闪一下默认语言。
        runBlocking {
            AppLanguageState.language = GlobalContext.get()
                .get<SettingsRepository>().appLanguage.first()
        }

        // 首次启动写入服务商预设（DeepSeek / Kimi / 百炼 / Ollama）。
        // 预设自带 baseUrl、默认模型与参数风格，用户只需填一个密钥即可使用——
        // 与 AIRI 的内置 provider 预设同思路。
        appScope.launch {
            runCatching {
                val repo = GlobalContext.get().get<ProviderConfigRepository>()
                repo.seedPresetsIfEmpty()
                // 老版本预设模型同步为最新（温和升级，不覆盖已拉取的真实模型）
                repo.upgradePresetModels()
                // 每次启动对已设置密钥的服务商做最小请求测试（0~1 token），
                // 刷新可用性标记（✓/✕），供服务商页与聊天前拦截使用
                repo.testAllConfigured(GlobalContext.get().get<ApiTester>())
            }
        }

        // 记忆生命周期刷新：冷启动补一次状态流转
        //（ACTIVE 到期→沉睡；低分沉睡超期→拾忆区；超期拾忆→物理清空）
        appScope.launch {
            runCatching {
                val memoryRepo = GlobalContext.get()
                    .get<team.bhe.bhaistudio.data.repository.MemoryRepository>()
                // 把用户设置的「拾忆自动清空时间」同步给生命周期引擎（默认 30 天）
                memoryRepo.recycleRetentionDays = GlobalContext.get()
                    .get<SettingsRepository>().recycleRetentionDays.first()
                memoryRepo.refreshLifecycle()
            }
        }

        // 跨设备迁移：把迁移带来的用户资料（昵称/头像引用）写入 DataStore。
        // 落地文件由 MigrationApplier 在进程重启时放置，写完后删除。
        appScope.launch {
            runCatching {
                val file = File(filesDir, MigrationApplier.MIGRATION_PROFILE_FILE)
                if (!file.exists()) return@launch
                val profile = transferJson.decodeFromString(ProfileFile.serializer(), file.readText())
                val repo = GlobalContext.get().get<UserProfileRepository>()
                if (profile.nickname.isNotBlank()) repo.setNickname(profile.nickname)
                if (profile.avatarUri.isNotBlank()) repo.setAvatarUri(profile.avatarUri)
                file.delete()
            }
        }
    }
}
