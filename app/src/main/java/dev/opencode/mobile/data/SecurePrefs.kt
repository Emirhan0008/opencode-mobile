package dev.opencode.mobile.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API anahtarları için AES-GCM şifreli saklama. Anahtar Android Keystore'da
 * üretilir ve cihazdan DIŞARI ÇIKMAZ; DataStore'da yalnızca şifreli metin durur.
 *
 * Bu yüzden anahtarlar yedekleme/cihaz değişimiyle taşınmaz — yeni cihazda
 * yeniden girilmesi gerekir (bilinçli tercih: sızma yüzeyini küçültür).
 */
object SecurePrefs {

    private const val ALIAS = "oc_voice_api_keys"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Düz metni şifreler. Boş girdi boş çıktı verir (anahtar yok = ""). */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(1 + iv.size + ct.size)
            out[0] = iv.size.toByte()
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
            Base64.encodeToString(out, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    /** Şifreli metni çözer. Bozuk/eski kayıt sessizce "" döner (çökme yok). */
    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        return runCatching {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            val ivLen = raw[0].toInt()
            val iv = raw.copyOfRange(1, 1 + ivLen)
            val ct = raw.copyOfRange(1 + ivLen, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrDefault("")
    }
}
