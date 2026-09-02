package team.bhe.bhaistudio.data.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密钥加解密工具
 *
 * 逻辑原样取自 ApiKeyRepository（对应桌面端 index.js:2860 的 AES_CONFIG 思路），
 * 但有两处本质升级：
 *   1. 桌面端 AES 密钥**硬编码在前端 JS 里**，DevTools 一开就等于明文；
 *      这里 AES 密钥存放在 **Android Keystore**——永不离开安全硬件，
 *      App 重启后密钥依然有效，密文不会失效。
 *   2. 算法用 AES-GCM 而非 CBC，自带完整性校验
 */
object KeyCrypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "wujie_api_key"

    private const val AES_ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    /** 从 Keystore 取密钥，不存在则生成（Keystore 内生成，密钥材料不出硬件） */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** 加密。输出格式：Base64(IV + 密文)，IV 前置 12 字节 */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + cipherText, Base64.DEFAULT)
    }

    /**
     * 解密。格式不符或校验失败返回 null，调用方直接当"未配置"处理。
     *
     * 注意：历史版本用内存密钥加密的密文，在密钥丢失（进程重启 / 升级到 Keystore）后
     * 无法解密，会返回 null 被视作"未配置"——用户重新填入一次即可，之后永久有效。
     */
    fun decrypt(encoded: String): String? = runCatching {
        val raw = Base64.decode(encoded, Base64.DEFAULT)
        val iv = raw.copyOfRange(0, IV_LENGTH)
        val cipherText = raw.copyOfRange(IV_LENGTH, raw.size)
        val cipher = Cipher.getInstance(AES_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()

    /**
     * 脱敏：`sk-1234****abcd`，用于列表展示。
     *
     * 桌面端 getMaskedKey 在长度 ≤ 2 时会 substring(-2) 崩溃（老代码真实缺陷），
     * 这里安全降级。
     */
    fun mask(key: String): String = when {
        key.length > 6 -> "${key.take(6)}****${key.takeLast(4)}"
        key.length >= 2 -> "****${key.takeLast(2)}"
        key.isNotEmpty() -> "****"
        else -> ""
    }
}
