package com.aurum.edge.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** Interoperable, versioned encryption envelope. No plaintext or password is uploaded. */
object DailyBackupCrypto {
    const val ITERATIONS = 310_000
    private val random = SecureRandom()
    fun encrypt(payload: ByteArray, password: CharArray): ByteArray {
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val key = derive(password, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("AurumDailyBackup:1".toByteArray())
        val encrypted = cipher.doFinal(payload)
        key.fill(0)
        val b64 = Base64.getEncoder()
        return JSONObject().put("format", "AurumDailyBackup").put("version", 1)
            .put("kdf", "PBKDF2-HMAC-SHA256").put("iterations", ITERATIONS)
            .put("cipher", "AES-256-GCM").put("compression", "gzip")
            .put("salt", b64.encodeToString(salt)).put("nonce", b64.encodeToString(nonce))
            .put("ciphertext", b64.encodeToString(encrypted)).toString().toByteArray(Charsets.UTF_8)
    }
    fun decrypt(envelope: ByteArray, password: CharArray): ByteArray {
        val obj = JSONObject(String(envelope, Charsets.UTF_8))
        require(obj.getString("format") == "AurumDailyBackup" && obj.getInt("version") == 1)
        val iterations = obj.getInt("iterations")
        require(iterations in 310_000..1_000_000)
        val b64 = Base64.getDecoder()
        val key = derive(password, b64.decode(obj.getString("salt")), iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b64.decode(obj.getString("nonce"))))
        cipher.updateAAD("AurumDailyBackup:1".toByteArray())
        return try { cipher.doFinal(b64.decode(obj.getString("ciphertext"))) } finally { key.fill(0) }
    }
    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}
