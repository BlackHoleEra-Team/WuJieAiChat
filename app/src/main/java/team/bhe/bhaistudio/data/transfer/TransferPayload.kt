package team.bhe.bhaistudio.data.transfer

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import net.zetetic.database.sqlcipher.SQLiteDatabase
import team.bhe.bhaistudio.data.db.DatabaseEncryption
import team.bhe.bhaistudio.data.repository.ProviderConfigRepository
import team.bhe.bhaistudio.data.repository.SearchConfigRepository
import java.io.File

/**
 * 迁移包内容模型。
 *
 * 发送方（导出）把下列内容打包发送：
 *   · passphrase   —— 数据库明文口令（会话加密传输，接收方用自己 Keystore 重包）
 *   · db           —— SQLCipher 加密库文件字节（checkpoint 后读取，保证完整）
 *   · provider_keys —— 服务商明文密钥清单（接收方重包进库）
 *   · search_keys  —— 联网搜索明文密钥清单
 *   · avatar-*     —— 头像文件
 */

/** 待发送的每一个数据块（有序） */
class PayloadPart(
    val item: TransferItem,
    private val source: File? = null,
    private val bytes: ByteArray? = null
) {
    val inputStream: java.io.InputStream
        get() = source?.inputStream() ?: bytes!!.inputStream()

    companion object {
        fun ofBytes(id: String, kind: String, data: ByteArray) =
            PayloadPart(TransferItem(id, kind, data.size.toLong()), bytes = data)

        fun ofFile(id: String, kind: String, file: File) =
            PayloadPart(TransferItem(id, kind, file.length()), source = file)
    }
}

/** 迁移包：有序 part 列表 + 设备名 */
class MigrationPayload(
    val deviceName: String,
    val parts: List<PayloadPart>
) {
    val totalBytes: Long = parts.sumOf { it.item.size }
}

/** 服务商密钥条目（发送时明文仅内存，会话加密传输） */
@Serializable
data class KeyEntry(val id: String, val key: String)

/** 密钥清单文件（落盘结构，接收端修复用） */
@Serializable
data class KeysFile(
    val providers: List<KeyEntry> = emptyList(),
    val searches: List<KeyEntry> = emptyList()
)

/** 用户资料（"我的"页昵称 / 头像 uri，存于 DataStore） */
@Serializable
data class ProfileFile(
    val nickname: String = "",
    val avatarUri: String = ""
)

/**
 * 在导出方构建迁移包。
 *
 * 数据完整性的关键一步：Room 使用 WAL 模式时主 db 文件可能不含最新提交，
 * 这里先用 SQLCipher 对库做 `PRAGMA wal_checkpoint(TRUNCATE)`，把 WAL 合并进
 * 主文件后再读字节，保证传过去的就是当前数据的完整快照。
 */
suspend fun buildMigrationPayload(
    context: Context,
    providerRepository: ProviderConfigRepository,
    searchRepository: SearchConfigRepository,
    deviceName: String,
    profileNickname: String = "",
    profileAvatarUri: String = ""
): MigrationPayload {
    DatabaseEncryption.ensureLoaded()

    val passphrase = DatabaseEncryption.currentPassphrase(context)
        ?: throw TransferException("数据库口令不可用，无法导出")

    val dbFile = context.getDatabasePath("wujie.db")
    if (!dbFile.exists() || dbFile.length() == 0L) {
        throw TransferException("本地还没有数据库可导出")
    }

    // 1) WAL checkpoint，把未合并的写提交刷进主文件
    var opened: SQLiteDatabase? = null
    try {
        opened = SQLiteDatabase.openOrCreateDatabase(
            dbFile.path,
            String(passphrase, Charsets.UTF_8),
            null, null
        )
        opened.rawExecSQL("PRAGMA wal_checkpoint(TRUNCATE)")
    } catch (e: Exception) {
        Log.w("TransferPack", "checkpoint 失败（继续尝试直接导出）：${e.message}")
    } finally {
        runCatching { opened?.close() }
    }

    // 2) 收集密钥清单（解密为明文，仅打包到内存）
    val providerKeys = providerRepository.listAll()
        .filter { it.encryptedApiKey.isNotBlank() }
        .mapNotNull { cfg ->
            providerRepository.getDecryptedKey(cfg.id)?.let { KeyEntry(cfg.id, it) }
        }
    val searchKeys = searchRepository.listAll()
        .filter { it.apiKey.isNotBlank() }
        .map { KeyEntry(it.id, it.apiKey) }

    // 3) 头像文件
    val avatarDir = File(context.filesDir, "avatars")
    val avatarFiles = if (avatarDir.isDirectory) avatarDir.listFiles().orEmpty() else emptyArray()

    val parts = mutableListOf<PayloadPart>()
    parts += PayloadPart.ofBytes(
        "passphrase", TransferKind.PASSPHRASE,
        String(passphrase, Charsets.UTF_8).toByteArray(Charsets.UTF_8)
    )
    parts += PayloadPart.ofFile("db", TransferKind.DB, dbFile)
    if (providerKeys.isNotEmpty() || searchKeys.isNotEmpty()) {
        val keysBytes = transferJson.encodeToString(
            KeysFile.serializer(),
            KeysFile(providers = providerKeys, searches = searchKeys)
        ).toByteArray(Charsets.UTF_8)
        parts += PayloadPart.ofBytes("keys.json", TransferKind.PROVIDER_KEYS, keysBytes)
    }
    // 用户资料（昵称 / 头像 uri）：头像文件随 avatars 目录一起传，这里只传引用
    if (profileNickname.isNotBlank() || profileAvatarUri.isNotBlank()) {
        val profileBytes = transferJson.encodeToString(
            ProfileFile.serializer(),
            ProfileFile(nickname = profileNickname, avatarUri = profileAvatarUri)
        ).toByteArray(Charsets.UTF_8)
        parts += PayloadPart.ofBytes("profile.json", TransferKind.SETTINGS, profileBytes)
    }
    avatarFiles.sortedBy { it.name }.forEach { file ->
        parts += PayloadPart.ofFile("avatar-${file.name}", TransferKind.AVATAR, file)
    }
    return MigrationPayload(deviceName, parts)
}
