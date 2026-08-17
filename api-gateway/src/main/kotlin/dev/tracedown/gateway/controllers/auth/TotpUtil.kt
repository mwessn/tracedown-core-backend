package dev.tracedown.gateway.controllers.auth

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object TotpUtil {

    private val totp = TimeBasedOneTimePasswordGenerator()

    fun decryptSecret(encryptedBase64: String, ivBase64: String, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.copyOf(32), "AES")
        val ivSpec = IvParameterSpec(Base64.getDecoder().decode(ivBase64))
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(Base64.getDecoder().decode(encryptedBase64))
    }

    fun encryptSecret(secret: ByteArray, key: ByteArray): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.copyOf(32), "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(secret)
        return Base64.getEncoder().encodeToString(encrypted) to Base64.getEncoder().encodeToString(iv)
    }

    fun verifyCode(secret: ByteArray, code: String): Boolean {
        val key = SecretKeySpec(secret, totp.algorithm)
        val now = Instant.now()
        // Accept current window and one step back (clock drift tolerance)
        val current = totp.generateOneTimePasswordString(key, now)
        val previous = totp.generateOneTimePasswordString(key, now.minus(totp.timeStep))
        return code == current || code == previous
    }

    fun generateCode(secret: ByteArray): String {
        val key = SecretKeySpec(secret, totp.algorithm)
        return totp.generateOneTimePasswordString(key, Instant.now())
    }
}
