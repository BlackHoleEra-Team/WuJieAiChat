package team.bhe.bhaistudio.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import team.bhe.bhaistudio.ui.screen.chat.ChatViewModel
import team.bhe.bhaistudio.ui.screen.chatsettings.ChatLogsViewModel
import team.bhe.bhaistudio.ui.screen.chatsettings.ChatSettingsViewModel
import team.bhe.bhaistudio.ui.screen.contact.ContactEditViewModel
import team.bhe.bhaistudio.ui.screen.contacts.ContactListViewModel
import team.bhe.bhaistudio.ui.screen.home.ChatListViewModel
import team.bhe.bhaistudio.ui.screen.memory.MemoryViewModel
import team.bhe.bhaistudio.ui.screen.providers.ProviderSettingsViewModel
import team.bhe.bhaistudio.ui.screen.search.SearchSettingsViewModel
import team.bhe.bhaistudio.ui.screen.settings.SettingsViewModel
import team.bhe.bhaistudio.ai.ApiTester
import team.bhe.bhaistudio.ai.ProviderFactory
import team.bhe.bhaistudio.ai.SegmentedReplyScheduler
import team.bhe.bhaistudio.ai.WebSearchClient
import team.bhe.bhaistudio.data.db.AppDatabase
import team.bhe.bhaistudio.data.db.DatabaseEncryption
import team.bhe.bhaistudio.data.repository.ChatRepository
import team.bhe.bhaistudio.data.repository.ContactRepository
import team.bhe.bhaistudio.data.repository.ConversationRepository
import team.bhe.bhaistudio.data.repository.MemoryRepository
import team.bhe.bhaistudio.data.repository.ProviderConfigRepository
import team.bhe.bhaistudio.data.repository.SearchConfigRepository
import team.bhe.bhaistudio.data.repository.SettingsRepository
import team.bhe.bhaistudio.data.repository.UserProfileRepository
import team.bhe.bhaistudio.ui.screen.profile.ProfileViewModel
import team.bhe.bhaistudio.ui.screen.recycled.RecycledViewModel
import team.bhe.bhaistudio.ui.screen.transfer.ExportViewModel
import team.bhe.bhaistudio.ui.screen.transfer.ImportViewModel
import java.util.concurrent.TimeUnit

/** DataStore 实例，全局唯一 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "wujie_settings"
)

// ── 数据库迁移 ──
// 覆盖最近两次表结构变更；更早的旧版本由 fallbackToDestructiveMigration 兜底，
// 保证开发期不崩溃、且常见版本的升级不再清空数据。

/** v4→v5：conversation 增加压缩摘要列 */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversation ADD COLUMN compressedSummary TEXT NOT NULL DEFAULT ''")
    }
}

/** v5→v6：contact 增加手动上下文窗口列 */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contact ADD COLUMN contextWindow INTEGER NOT NULL DEFAULT 0")
    }
}

/** v6→v7：contact 增加自定义搜索模式列 + 新增搜索配置表 */
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contact ADD COLUMN useCustomSearch INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `search_config` (" +
                "`id` TEXT NOT NULL, " +
                "`provider` TEXT NOT NULL, " +
                "`apiKey` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
    }
}

/** v7→v8：contact 增加关闭流式传输列 */
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contact ADD COLUMN disableStreaming INTEGER NOT NULL DEFAULT 0")
    }
}

/** v8→v9：provider_config 增加可用性测试结果列 */
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE provider_config ADD COLUMN isAvailable INTEGER")
    }
}

/** v9→v10：contact 增加延迟发送列（与关闭流式互斥） */
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE contact ADD COLUMN enableDelay INTEGER NOT NULL DEFAULT 0")
    }
}

/** v10→v11：memory 增加索引与激活权重列（分层记忆：索引层 + 按需调取打底） */
private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memory ADD COLUMN indexSummary TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE memory ADD COLUMN accessCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memory ADD COLUMN lastAccessAt INTEGER")
    }
}

/**
 * v11→v12：记忆生命周期状态机。
 * importance=AI 打分；state=ACTIVE/INACTIVE/RECYCLED；enteredStateAt/activeUntil=状态计时；
 * recycledAt=进入拾忆区时间。旧数据默认 ACTIVE，计时字段留 0 由状态机首刷时初始化。
 */
private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE memory ADD COLUMN importance REAL NOT NULL DEFAULT 0.5")
        db.execSQL("ALTER TABLE memory ADD COLUMN state TEXT NOT NULL DEFAULT 'ACTIVE'")
        db.execSQL("ALTER TABLE memory ADD COLUMN enteredStateAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memory ADD COLUMN activeUntil INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memory ADD COLUMN recycledAt INTEGER")
    }
}

val appModule = module {

    // ── 网络 ──
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // 流式必须设 0：SSE 连接会长时间无数据（模型"思考"时），
            // 固定 readTimeout 会导致长回复中途被断开
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    // ── 数据库 ──
    single {
        val context = androidContext()
        // 加密化升级：明文旧库直接丢弃（如需保留旧数据请改走 sqlcipher_export 迁移）
        DatabaseEncryption.clearLegacyPlaintextDb(context)
        Room.databaseBuilder(context, AppDatabase::class.java, "wujie.db")
            // 全库 AES 加密（SQLCipher）：密钥随机生成后由 Android Keystore 保管
            .openHelperFactory(DatabaseEncryption.openHelperFactory(context))
            // 已覆盖 4→5、5→6 的正式 Migration（保留数据）；
            // 更早版本（v1-v3）开发期用 fallback 兜底重建，避免崩溃。
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
            .fallbackToDestructiveMigration()
            .build()
    }

    single { get<AppDatabase>().contactDao() }
    single { get<AppDatabase>().conversationDao() }
    single { get<AppDatabase>().messageDao() }
    single { get<AppDatabase>().memoryDao() }
    single { get<AppDatabase>().providerConfigDao() }
    single { get<AppDatabase>().searchConfigDao() }

    // ── 设置 ──
    single<DataStore<Preferences>> { androidContext().settingsDataStore }
    singleOf(::SettingsRepository)
    singleOf(::UserProfileRepository)

    // ── 仓库 ──
    singleOf(::ContactRepository)
    singleOf(::ConversationRepository)
    singleOf(::MemoryRepository)
    singleOf(::ProviderConfigRepository)
    singleOf(::SearchConfigRepository)
    singleOf(::ChatRepository)

    // ── AI ──
    singleOf(::SegmentedReplyScheduler)
    singleOf(::ProviderFactory)
    singleOf(::WebSearchClient)
    singleOf(::ApiTester)

    // ── ViewModel ──
    // VM 层文案本地化：需要 Context 生成文案的 VM 统一继承 AndroidViewModel，
    // Koin 会自动注入 Application（同 ChatLogsViewModel），不要在 DI 注册 Context——
    // androidContext() 本质是 scope.get<Context>()，注册成 single 会自我递归导致 StackOverflow。
    viewModelOf(::ChatViewModel)
    viewModelOf(::ChatSettingsViewModel)
    viewModelOf(::ChatLogsViewModel)
    viewModelOf(::ChatListViewModel)
    viewModelOf(::ContactListViewModel)
    viewModelOf(::ContactEditViewModel)
    viewModelOf(::ProviderSettingsViewModel)
    viewModelOf(::SearchSettingsViewModel)
    viewModelOf(::MemoryViewModel)
    viewModelOf(::SettingsViewModel)
    viewModelOf(::ProfileViewModel)
    viewModelOf(::ImportViewModel)
    viewModelOf(::ExportViewModel)
    viewModelOf(::RecycledViewModel)
}
