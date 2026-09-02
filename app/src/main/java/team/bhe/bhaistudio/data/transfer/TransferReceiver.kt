package team.bhe.bhaistudio.data.transfer

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket

/**
 * 接收引擎（导入方 / 新机）。
 *
 * 启动 [start] 后进入接收模式：
 *   1. 监听 TCP，对方 hello(token) 匹配才继续；
 *   2. [onIncomingRequest] 询问用户是否接受（UI 弹确认框，返回 Boolean）；
 *   3. 接受 → KEX 交换 → ECDH 派生会话密钥；
 *   4. 解密接收各数据项，按类型落到 staging 目录；
 *   5. 全部落盘后由 [MigrationApplier] 在下次启动时应用到本机。
 */
class TransferReceiver(
    private val context: Context,
    private val token: String,
    private val onIncomingRequest: suspend (deviceName: String) -> Boolean,
    private val onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit
) {

    private var server: ServerSocket? = null
    private val mutex = Any()

    val port: Int get() = synchronized(mutex) { server?.localPort ?: -1 }

    /** 进入接收模式：监听随机端口 */
    fun start() {
        synchronized(mutex) {
            if (server == null) server = ServerSocket(0)
        }
    }

    fun stop() {
        synchronized(mutex) {
            runCatching { server?.close() }
            server = null
        }
    }

    /**
     * 阻塞接受一个连接并完成整场接收（须在 IO 上下文调用）。
     * @return 接收的字节数
     */
    suspend fun acceptAndReceive(): Result<Long> = withContext(Dispatchers.IO) {
        runCatching { receiveLoop() }
    }

    private suspend fun receiveLoop(): Long {
        val staging = MigrationApplier.stagingDir(context).apply { deleteRecursively() }
        while (true) {
            val srv = synchronized(mutex) { server } ?: throw TransferException("接收已停止")
            val socket = srv.accept()
            socket.soTimeout = 120_000

            try {
                return handleSession(socket, staging)
            } catch (e: Exception) {
                runCatching { socket.close() }
                // 令牌不匹配等拒绝场景：继续等待下一位连接
                if (e is SessionRejected) {
                    Log.w("TransferRecv", "会话被拒绝：${e.message}")
                    continue
                }
                throw e
            }
        }
    }

    private class SessionRejected(message: String) : Exception(message)

    private suspend fun handleSession(socket: java.net.Socket, staging: File): Long {
        TransferSocket(socket).use { ts ->
            // 1) hello + 令牌校验
            val line = ts.readJson() ?: throw SessionRejected("对方未发送 hello 即断开")
            val hello = tryParseJson<HelloMsg>(line) ?: throw SessionRejected("无法识别连接请求")
            if (hello.token != token) {
                ts.writeJson(PlainMsg("reject"))
                throw SessionRejected("连接令牌不匹配")
            }

            // 2) 询问用户是否接受（同步等待 UI 决定）
            val accepted = onIncomingRequest(hello.from)
            if (!accepted) {
                ts.writeJson(PlainMsg("reject"))
                throw SessionRejected("用户拒绝")
            }

            // 3) KEX：先发（代表接受），再等对方 KEX
            val keyPair = TransferCrypto.generateKeyPair()
            val myNonce = TransferCrypto.randomNonce()
            ts.writeJson(KexMsg(
                pub = TransferCrypto.encodePublicKey(keyPair.public),
                nonce = Base64.encodeToString(myNonce, Base64.NO_WRAP)
            ))
            val peerLine = ts.readJson() ?: throw TransferException("对方在密钥协商时断开")
            val peerKex = tryParseJson<KexMsg>(peerLine)
                ?: throw TransferException("密钥协商失败")
            val sessionKey = TransferCrypto.deriveSessionKey(
                keyPair.private,
                peerKex.pub,
                myNonce,
                Base64.decode(peerKex.nonce, Base64.NO_WRAP)
            )
            ts.secureFrame = TransferCrypto.SecureFrame(sessionKey)

            // 4) 收 meta
            val metaJson = ts.readEncrypted() ?: throw TransferException("传输中断（无数据）")
            val meta = transferJson.decodeFromString(
                MetaMsg.serializer(),
                String(metaJson, Charsets.UTF_8)
            )
            val total = meta.items.sumOf { it.size }

            // 5) 逐项落盘
            var received = 0L
            meta.items.forEach { item ->
                val target = stageTarget(staging, item) ?: return@forEach
                target.parentFile?.mkdirs()
                BufferedOutputStream(FileOutputStream(target), 256 * 1024).use { out ->
                    var remaining = item.size
                    while (remaining > 0) {
                        val chunk = ts.readEncrypted() ?: throw TransferException("传输中断（数据不完整）")
                        out.write(chunk)
                        remaining -= chunk.size
                        received += chunk.size
                        onProgress(received, total)
                    }
                }
            }

            // 6) done 校验
            val doneJson = ts.readEncrypted()
            val done = doneJson?.let {
                runCatching {
                    transferJson.decodeFromString(DoneMsg.serializer(), String(it, Charsets.UTF_8))
                }.getOrNull()
            }
            if (done == null || done.items != meta.items.size) {
                throw TransferException("传输校验失败，请重试")
            }
            return received
        }
    }

    /** 数据项 → staging 目标文件；未知项忽略 */
    private fun stageTarget(staging: File, item: TransferItem): File? = when {
        item.id == "db" -> File(staging, "db.bin")
        item.id == "passphrase" -> File(staging, "passphrase.txt")
        item.id == "keys.json" -> File(staging, "keys.json")
        item.id == "profile.json" -> File(staging, "profile.json")
        item.id.startsWith("avatar-") ->
            File(File(staging, "avatars"), item.id.removePrefix("avatar-"))
        else -> null
    }
}
