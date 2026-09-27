package com.example.receiver

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

object CryptoManager {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12

    // Base64 of at least IV (12) + GCM tag (16) bytes = 28 bytes, i.e. 40 characters
    private val ENCRYPTED_FORMAT = Regex("^[A-Za-z0-9+/]{38,}={0,2}$")

    /**
     * Whether [data] has the shape of a message produced by [encrypt]. Plain messages (JSON, links,
     * text) never do, because they contain characters that are not part of the Base64 alphabet.
     */
    fun looksEncrypted(data: String): Boolean = ENCRYPTED_FORMAT.matches(data.trim())

    fun generateSecretKey(): String {
        val key = ByteArray(32) // 256 bits
        SecureRandom().nextBytes(key)
        return Base64.encodeToString(key, Base64.NO_WRAP)
    }

    fun encrypt(data: String, secretKeyB64: String): String {
        val key = Base64.decode(secretKeyB64, Base64.NO_WRAP)
        val secretKey = SecretKeySpec(key, "AES")
        
        val iv = ByteArray(IV_LENGTH_BYTE)
        SecureRandom().nextBytes(iv)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        
        val encryptedData = cipher.doFinal(data.toByteArray())
        
        // Result: IV + Encrypted Data (Base64)
        return Base64.encodeToString(iv + encryptedData, Base64.NO_WRAP)
    }

    fun decrypt(encryptedDataB64: String, secretKeyB64: String): String {
        val key = Base64.decode(secretKeyB64, Base64.NO_WRAP)
        val secretKey = SecretKeySpec(key, "AES")
        
        val combined = Base64.decode(encryptedDataB64, Base64.NO_WRAP)
        val iv = combined.sliceArray(0 until IV_LENGTH_BYTE)
        val encrypted = combined.sliceArray(IV_LENGTH_BYTE until combined.size)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        
        return String(cipher.doFinal(encrypted))
    }
}
