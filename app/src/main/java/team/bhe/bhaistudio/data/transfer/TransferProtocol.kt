package team.bhe.bhaistudio.data.transfer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.Socket

// ─────────────────────────────────────────────────────────
// 协议模型
// ─────────────────────────────────────────────────────────

/** 数据项类型：迁移包里每个条目是什么 */
object TransferKind {
    /** SQLCipher 加密库文件字节流（wujie.db） */
    const val DB = "db"
    /** 服务商 API 密钥清单（明文，仅内存，会话加密传输） */
    const val PROVIDER_KEYS = "provider_keys"
    /** 联网搜索密钥清单（同上） */
    const val SEARCH_KEYS = "search_keys"
    /** 数据库 passphrase 明文 */
    const val PASSPHRASE = "passphrase"
    /** 头像文件 */
    const val AVATAR = "avatar"
    /** 用户设置（DataStore，预留） */
    const val SETTINGS = "settings"
}

@Serializable
data class TransferItem(
    /** 稳定 id：db / provider_keys / search_keys / passphrase / settings / avatar-<文件名> */
    val id: String,
    val kind: String,
    val size: Long
)

/** 明文握手消息，全部走 JSON 行 */
@Serializable
data class HelloMsg(
    val type: String = "hello",
    val from: String,          // 发送方设备名
    val appVersion: String = "1",
    val token: String          // 接收方广播的一次性令牌
)

@Serializable
data class KexMsg(
    val type: String = "kex",
    val pub: String,           // 临时 EC 公钥 base64
    val nonce: String          // 随机数 base64
)

@Serializable
data class PlainMsg(val type: String)   // accept / reject / error / busy

@Serializable
data class MetaMsg(
    val type: String = "meta",
    val from: String,
    val items: List<TransferItem>
)

@Serializable
data class DoneMsg(
    val type: String = "done",
    val items: Int,
    val totalBytes: Long
)

/** 内容分块的固定上限（约 64KB 明文） */
const val TRANSFER_CHUNK = 64 * 1024

// ─────────────────────────────────────────────────────────
// Socket 会话封装
// ─────────────────────────────────────────────────────────

internal val transferJson = Json { ignoreUnknownKeys = true }

/**
 * 单个 TCP 连接的封装。
 *
 * 两阶段：
 *   1. 明文握手（[writeJson]/[readJson]）——hello / kex，JSON 行。
 *   2. 握手完成后设置 [secureFrame] → 之后的数据全部用加密帧
 *      （[writeEncrypted]/[readEncrypted]，长度前缀 + IV + AES-GCM）。
 */
class TransferSocket(private val socket: Socket) : Closeable {

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    /** 握手完成后设置；设置后只允许走加密读写 */
    @Volatile
    var secureFrame: TransferCrypto.SecureFrame? = null

    // ── 明文阶段 ──

    internal inline fun <reified T> writeJson(msg: T) {
        output.write((transferJson.encodeToString(serializer<T>(), msg) + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /** 读一行 JSON；连接关闭返回 null */
    fun readJson(): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (sb.isEmpty()) return null
                break
            }
            if (b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    // ── 加密阶段 ──

    fun writeEncrypted(plain: ByteArray, offset: Int = 0, length: Int = plain.size) {
        val frame = requireSecure().encrypt(plain, offset, length)
        writeInt(frame.size)
        output.write(frame)
        output.flush()
    }

    /** 读一个加密帧；连接正常关闭返回 null */
    fun readEncrypted(): ByteArray? {
        val len = readInt() ?: return null
        require(len in 1..(TRANSFER_CHUNK + 64)) { "非法帧长度 $len" }
        val frame = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(frame, read, len - read)
            if (n < 0) throw EOFException("传输中断")
            read += n
        }
        return requireSecure().decrypt(frame)
    }

    private fun requireSecure(): TransferCrypto.SecureFrame =
        secureFrame ?: error("会话密钥尚未建立")

    private fun writeInt(value: Int) {
        output.write(byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        ))
    }

    private fun readInt(): Int? {
        val b = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = input.read(b, read, 4 - read)
            if (n < 0) {
                if (read == 0) return null
                throw EOFException("传输中断")
            }
            read += n
        }
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/** 尝试把 JSON 行反序列化为指定类型；失败返回 null（容忍垃圾行） */
internal inline fun <reified T> tryParseJson(line: String?): T? =
    if (line.isNullOrBlank()) null
    else runCatching { transferJson.decodeFromString(serializer<T>(), line) }.getOrNull()

/** 错误透传 */
class TransferException(message: String, cause: Throwable? = null) : IOException(message, cause)
