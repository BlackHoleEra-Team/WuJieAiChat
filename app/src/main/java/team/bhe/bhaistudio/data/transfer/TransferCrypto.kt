package team.bhe.bhaistudio.data.transfer

import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 跨设备传输的加密原语（临时密钥，每会话新建，不落盘）。
 *
 * 方案（参考 SafeLink 的思路，简化为单机单用户场景）：
 *   握手双方各自生成一次性 EC P-256 密钥对并交换公钥，
 *   ECDH 得到共享秘密，连同双方随机 nonce 经 SHA-256 派生 32 字节会话密钥；
 *   之后所有数据帧用该会话密钥做 AES-256-GCM 加密（自带完整性校验）。
 * 即使局域网被监听、握手内容被截获，没有私钥也无法解密数据。
 */
object TransferCrypto {

    const val IV_BYTES = 12
    const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()

    /** 一次性 EC P-256 密钥对（会话用，不进入 Keystore） */
    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        return generator.generateKeyPair()
    }

    fun encodePublicKey(key: PublicKey): String =
        Base64.encodeToString(key.encoded, Base64.NO_WRAP)

    fun decodePublicKey(b64: String): PublicKey {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    /** 握手随机数（参与会话密钥派生，防重放） */
    fun randomNonce(): ByteArray {
        val nonce = ByteArray(16)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    /** 一次性随机令牌（接收方广播/展示用） */
    fun randomHex(bytes: Int = 16): String {
        val raw = ByteArray(bytes)
        secureRandom.nextBytes(raw)
        return raw.joinToString("") { "%02x".format(it) }
    }

    /**
     * 由 ECDH 共享秘密 + 双方 nonce 派生 32 字节 AES-256 会话密钥。
     *
     * @param privateKey 本机临时私钥
     * @param peerPublicB64 对方临时公钥（Base64）
     * @param myNonce 本机发出的随机数
     * @param peerNonce 对方发出的随机数
     *
     * 关键：两个 nonce 在拼接前先按字节序**固定排序**。
     * 否则两端各自把「自己 nonce 放前」，会派生出一对不同的密钥
     * （发送方 nonceA||nonceB，接收方 nonceB||nonceA），对端解密必然 BAD_DECRYPT。
     */
    fun deriveSessionKey(
        privateKey: java.security.PrivateKey,
        peerPublicB64: String,
        myNonce: ByteArray,
        peerNonce: ByteArray
    ): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(decodePublicKey(peerPublicB64), true)
        val shared = agreement.generateSecret()

        val first: ByteArray
        val second: ByteArray
        if (compareNonce(myNonce, peerNonce) <= 0) {
            first = myNonce
            second = peerNonce
        } else {
            first = peerNonce
            second = myNonce
        }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(shared)
        digest.update(first)
        digest.update(second)
        digest.update("wujie-transfer-v1".toByteArray(Charsets.UTF_8))
        return digest.digest()
    }

    /** 无符号字节字典序比较 */
    private fun compareNonce(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val x = a[i].toInt() and 0xFF
            val y = b[i].toInt() and 0xFF
            if (x != y) return x - y
        }
        return 0
    }

    /**
     * AES-256-GCM 会话帧编解码器。
     *
     * 帧格式：`12 字节 IV（自增计数器） + AES-GCM 密文（含 16 字节 tag）`。
     * 发送方每次加密取下一个计数器做 IV，保证同一会话密钥下 IV 不重复；
     * 解密方直接读取帧头 IV，天然容忍半包/粘包之外的顺序错乱并校验失败。
     */
    class SecureFrame(private val key: ByteArray) {

        private var counter = 0L

        @Synchronized
        fun encrypt(plain: ByteArray, offset: Int = 0, length: Int = plain.size): ByteArray {
            val data = if (offset == 0 && length == plain.size) plain
            else plain.copyOfRange(offset, offset + length)
            val iv = ByteArray(IV_BYTES)
            // counter 编码到 IV 末尾 8 字节（大端）
            ByteBuffer.wrap(iv, IV_BYTES - 8, 8).putLong(counter++)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(data)
            return iv + ciphertext
        }

        fun decrypt(frame: ByteArray): ByteArray {
            require(frame.size > IV_BYTES) { "加密帧过短" }
            val iv = frame.copyOfRange(0, IV_BYTES)
            val ciphertext = frame.copyOfRange(IV_BYTES, frame.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ciphertext)
        }
    }
}
