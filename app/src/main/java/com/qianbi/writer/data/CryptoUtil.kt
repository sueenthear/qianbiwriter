package com.qianbi.writer.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * .book 包的口令保护：
 * - 口令 → PBKDF2-HMAC-SHA256（salt 每次导出随机）→ 64 字节
 *   - 前 32 字节 = AES-256-CTR 加密密钥
 *   - 后 32 字节 = HMAC-SHA256 认证密钥
 * - 头部保存 verifier = HMAC(macKey, "NBOOK-VERIFY-V1")，用于**导入时先验密码**，
 *   密码不对立刻报错，不需要等整包解密完。
 * - 包尾保存 HMAC(macKey, 密文)，用于完整性校验（防篡改/截断）。
 *
 * 说明：CTR 的计数器每次导出都从 0 开始，但 salt 每次随机 → 密钥唯一，
 * 因此计数器复用不会造成密钥流重复。
 */
object CryptoUtil {
    const val DEFAULT_ITERATIONS = 120_000
    const val SALT_SIZE = 16
    const val VERIFIER_SIZE = 32
    const val MAC_SIZE = 32
    private const val VERIFY_TAG = "NBOOK-VERIFY-V1"
    private const val KEY_MATERIAL_SIZE = 64

    /** 成对密钥：enc 用于加密正文，mac 用于校验。 */
    class Keys(val enc: ByteArray, val mac: ByteArray)

    fun newSalt(): ByteArray = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }

    fun deriveKeys(password: CharArray, salt: ByteArray, iterations: Int): Keys {
        val spec = PBEKeySpec(password, salt, iterations, KEY_MATERIAL_SIZE * 8)
        val raw = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return Keys(raw.copyOfRange(0, 32), raw.copyOfRange(32, 64))
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hmac(key: ByteArray, data: ByteArray, out: java.io.OutputStream): ByteArray {
        val tag = hmac(key, data)
        out.write(tag)
        return tag
    }

    /** 与包头的 verifier 比对，用来快速判断口令是否正确。 */
    fun verifier(keys: Keys): ByteArray = hmac(keys.mac, VERIFY_TAG.toByteArray(Charsets.UTF_8))

    fun matches(keys: Keys, expected: ByteArray): Boolean =
        MessageDigest.isEqual(verifier(keys), expected)

    /** AES-256-CTR cipher；mode 取 Cipher.ENCRYPT_MODE / DECRYPT_MODE。 */
    fun ctr(mode: Int, key: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
        return cipher
    }
}
