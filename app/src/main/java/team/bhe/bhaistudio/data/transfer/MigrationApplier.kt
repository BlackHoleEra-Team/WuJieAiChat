package team.bhe.bhaistudio.data.transfer

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import team.bhe.bhaistudio.data.db.DatabaseEncryption
import team.bhe.bhaistudio.data.repository.KeyCrypto
import java.io.File

/**
 * 接收端落地：把暂存在 staging 目录里的迁移包应用到本机。
 *
 * 时序设计：传输完成 → 写入 staging → 重启进程 → [WuJieApplication] 在
 * Koin / Room 初始化**之前**调用 [applyIfPending]，此时还没有任何数据库连接，
 * 可以直接覆盖库文件与执行密钥重写，然后正常启动进入新数据。
 *
 * 用户资料（昵称/头像，存 DataStore）不能在此同步写（DataStore 是异步协程 API），
 * 落地时先把 profile.json 挪到 [MIGRATION_PROFILE_FILE]，Koin 就绪后由
 * Application 协程消费写入。
 */
object MigrationApplier {

    private const val TAG = "MigrationApply"
    private const val STAGING_DIR = "transfer_incoming"

    /** 落地后待写入 DataStore 的用户资料暂存文件名（files 目录下） */
    const val MIGRATION_PROFILE_FILE = "migration_profile.json"

    fun stagingDir(context: Context): File = File(context.filesDir, STAGING_DIR)

    /** 是否存在待落地的迁移包 */
    fun hasPending(context: Context): Boolean =
        File(stagingDir(context), "db.bin").exists()

    /** 有待落地包时执行应用，成功则删除 staging */
    fun applyIfPending(context: Context) {
        if (!hasPending(context)) return
        Log.i(TAG, "发现待落地的迁移包，开始应用…")
        val result = runCatching { apply(context) }
        if (result.isSuccess) {
            Log.i(TAG, "迁移数据落地完成")
            stagingDir(context).deleteRecursively()
        } else {
            Log.e(TAG, "迁移数据落地失败：${result.exceptionOrNull()?.message}", result.exceptionOrNull())
        }
    }

    private fun apply(context: Context) {
        val staging = stagingDir(context)

        // 1) passphrase：用本机 Keystore 重新包裹
        val passText = File(staging, "passphrase.txt").readText().trim()
        require(passText.isNotEmpty()) { "迁移包缺少 passphrase" }
        DatabaseEncryption.importPassphrase(context, passText)

        // 2) 覆盖数据库文件（先删旧库与 WAL 残留，避免新文件被旧 WAL 干扰）
        val dbFile = context.getDatabasePath("wujie.db")
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
        File(dbFile.path + "-journal").delete()
        dbFile.delete()
        File(staging, "db.bin").copyTo(dbFile, overwrite = true)

        // 3) 密钥重写：迁移来的库里的 encryptedApiKey 是源机 Keystore 密文，
        //    本机解不开——用迁移携带的明文清单重包为本机 Keystore 密文。
        val keysJson = File(staging, "keys.json")
        if (keysJson.exists()) {
            val keys = transferJson.decodeFromString(KeysFile.serializer(), keysJson.readText())
            rewriteKeys(context, passText, keys)
        }

        // 4) 头像：整体替换
        val avatarSrc = File(staging, "avatars")
        val avatarDir = File(context.filesDir, "avatars")
        if (avatarSrc.isDirectory) {
            avatarDir.deleteRecursively()
            avatarDir.mkdirs()
            avatarSrc.listFiles().orEmpty().forEach { it.copyTo(File(avatarDir, it.name), overwrite = true) }
        }

        // 5) 用户资料：DataStore 只能异步写，先把数据挪到持久 pending 文件，
        //    由 WuJieApplication 在 Koin 就绪后用协程写入（本方法整体保持同步）。
        val profileSrc = File(staging, "profile.json")
        if (profileSrc.exists()) {
            profileSrc.copyTo(
                File(context.filesDir, MIGRATION_PROFILE_FILE),
                overwrite = true
            )
        }
    }

    private fun rewriteKeys(context: Context, passText: String, keys: KeysFile) {
        DatabaseEncryption.ensureLoaded()
        val dbFile = context.getDatabasePath("wujie.db")
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openOrCreateDatabase(dbFile.path, passText, null, null)
            keys.providers.forEach { entry ->
                if (entry.key.isNotBlank()) {
                    db.rawExecSQL(
                        "UPDATE provider_config SET encryptedApiKey = ?, maskedApiKey = ? WHERE id = ?",
                        KeyCrypto.encrypt(entry.key), KeyCrypto.mask(entry.key), entry.id
                    )
                }
            }
            keys.searches.forEach { entry ->
                if (entry.key.isNotBlank()) {
                    db.rawExecSQL(
                        "UPDATE search_config SET apiKey = ? WHERE id = ?",
                        KeyCrypto.encrypt(entry.key), entry.id
                    )
                }
            }
        } finally {
            runCatching { db?.close() }
        }
    }
}
