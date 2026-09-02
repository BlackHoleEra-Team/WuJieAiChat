package team.bhe.bhaistudio.data.db

import android.content.Context
import android.util.Base64
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import team.bhe.bhaistudio.data.repository.KeyCrypto
import java.security.SecureRandom

/**
 * 全库加密（SQLCipher，AES-256-CBC + HMAC）入口。
 *
 * passphrase 是随机生成的 32 字节密钥，用 [KeyCrypto]（Android Keystore 里的
 * AES-256-GCM 密钥）加密后存 SharedPreferences——用户无需记忆或管理任何密钥，
 * 真正的密钥材料始终留在系统安全硬件里。
 *
 * 为什么不用桌面端那套硬编码 AES_CONFIG：桌面密钥写在 JS 源码里，拿到代码就等于
 * 拿到明文；Keystore 方案密钥不落盘、不出硬件，这是本质区别。
 */
object DatabaseEncryption {

    private const val DB_NAME = "wujie.db"
    private const val PREFS_NAME = "wujie_db_key"
    private const val PREF_PASSPHRASE = "encrypted_passphrase"
    private const val PASSPHRASE_BYTES = 32

    /** SQLCipher 打开助手工厂：Room 的 openHelperFactory 用它打开加密库 */
    fun openHelperFactory(context: Context): SupportOpenHelperFactory {
        ensureLoaded()
        return SupportOpenHelperFactory(getOrCreatePassphrase(context))
    }

    /** 加载 SQLCipher native 库（幂等，System.loadLibrary 重复调用安全） */
    fun ensureLoaded() {
        System.loadLibrary("sqlcipher")
    }

    /**
     * 读取当前数据库 passphrase 明文（用于跨设备迁移导出）。
     * 未配置过返回 null；只读，不生成。
     */
    fun currentPassphrase(context: Context): ByteArray? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_PASSPHRASE, null)
            ?.let { KeyCrypto.decrypt(it)?.toByteArray(Charsets.UTF_8) }
    }

    /**
     * 导入跨设备迁移来的 passphrase：用**本机** Keystore 重新加密后落盘。
     * 迁移落地时由接收方调用，之后本机可像正常首次初始化一样打开迁移来的库。
     */
    fun importPassphrase(context: Context, passText: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_PASSPHRASE, KeyCrypto.encrypt(passText)).apply()
    }

    /**
     * 处理加密化升级前的明文旧库。
     *
     * 判定方式：对已存在的 db 用当前 passphrase 尝试 SQLCipher 打开——
     *   成功 → 已是加密库（或全新空库），保留；
     *   失败 → 是明文 / 损坏旧库，删掉让 Room 重建为空加密库。
     *
     * 注：明文旧数据**直接丢弃**（升级到加密版时无迁移保留数据的方案）。
     */
    fun clearLegacyPlaintextDb(context: Context) {
        val file = context.getDatabasePath(DB_NAME)
        if (!file.exists()) return

        ensureLoaded()
        val pass = String(getOrCreatePassphrase(context), Charsets.UTF_8)
        var opened: SQLiteDatabase? = null
        try {
            opened = SQLiteDatabase.openOrCreateDatabase(file.path, pass, null, null)
        } catch (e: Exception) {
            Log.w("DbEncrypt", "现存数据库不是 SQLCipher 加密库，丢弃重建（旧数据不保留）：${e.message}")
            context.deleteDatabase(DB_NAME)
            return
        } finally {
            runCatching { opened?.close() }
        }
        Log.i("DbEncrypt", "现存数据库已是加密库（或可加密打开），无需重建")
    }

    /** 取数据库 passphrase：已有则解密复用，首次则生成随机密钥并 Keystore 加密保存 */
    private fun getOrCreatePassphrase(context: Context): ByteArray {
        currentPassphrase(context)?.let { return it }
        Log.w("DbEncrypt", "已保存的 passphrase 无法解密（Keystore 密钥变化），重新生成")
        val raw = ByteArray(PASSPHRASE_BYTES)
        SecureRandom().nextBytes(raw)
        val pass = Base64.encodeToString(raw, Base64.NO_WRAP)
        importPassphrase(context, pass)
        return pass.toByteArray(Charsets.UTF_8)
    }
}
