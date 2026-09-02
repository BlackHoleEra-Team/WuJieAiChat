package team.bhe.bhaistudio.data.transfer

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 发送引擎（导出方 / 旧机）。
 *
 * 流程：连接接收方 → hello(token) → 接收方确认后发起 KEX → ECDH 派生会话密钥
 * → 加密发送 meta → 逐块发送各数据项 → done。
 */
class TransferSender(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val deviceName: String,
    private val payload: MigrationPayload,
    private val onProgress: (sentBytes: Long, totalBytes: Long) -> Unit
) {

    /** 返回实际发送的字节数 */
    suspend fun run(): Result<Long> = withContext(Dispatchers.IO) {
        runCatching { send() }
    }

    private fun send(): Long {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 10_000)
        socket.soTimeout = 120_000

        TransferSocket(socket).use { ts ->
            // 1) hello：告知身份并携带接收方广播的令牌
            ts.writeJson(HelloMsg(from = deviceName, token = token))

            // 2) 等接收方响应：KEX 消息 = 已接受；其它（reject/error）= 拒绝
            val line = ts.readJson() ?: throw TransferException("对方已断开连接")
            val peerKex = tryParseJson<KexMsg>(line)
                ?: throw TransferException("对方拒绝了本次传输")

            // 3) 己方临时密钥 + 交换 KEX，派生会话密钥
            val keyPair = TransferCrypto.generateKeyPair()
            val myNonce = TransferCrypto.randomNonce()
            ts.writeJson(KexMsg(
                pub = TransferCrypto.encodePublicKey(keyPair.public),
                nonce = Base64.encodeToString(myNonce, Base64.NO_WRAP)
            ))
            val sessionKey = TransferCrypto.deriveSessionKey(
                keyPair.private,
                peerKex.pub,
                myNonce,
                Base64.decode(peerKex.nonce, Base64.NO_WRAP)
            )
            ts.secureFrame = TransferCrypto.SecureFrame(sessionKey)

            // 4) 加密传输
            val meta = MetaMsg(from = deviceName, items = payload.parts.map { it.item })
            ts.writeEncrypted(
                transferJson.encodeToString(MetaMsg.serializer(), meta).toByteArray(Charsets.UTF_8)
            )

            var sent = 0L
            val buf = ByteArray(TRANSFER_CHUNK)
            payload.parts.forEach { part ->
                part.inputStream.use { input ->
                    var remaining = part.item.size
                    while (remaining > 0) {
                        val want = minOf(buf.size.toLong(), remaining).toInt()
                        val n = input.read(buf, 0, want)
                        if (n < 0) throw TransferException("读取本地数据失败：${part.item.id}")
                        ts.writeEncrypted(buf, 0, n)
                        remaining -= n
                        sent += n
                        onProgress(sent, payload.totalBytes)
                    }
                }
            }

            val done = DoneMsg(items = payload.parts.size, totalBytes = payload.totalBytes)
            ts.writeEncrypted(
                transferJson.encodeToString(DoneMsg.serializer(), done).toByteArray(Charsets.UTF_8)
            )
            return sent
        }
    }
}
