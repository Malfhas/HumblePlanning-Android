package com.humbleplanning.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Réglages simples de l'app (non sensibles). */
class Prefs(context: Context) {
    val raw: SharedPreferences = context.getSharedPreferences("humbleplanning", Context.MODE_PRIVATE)

    fun string(key: String, default: String = ""): String = raw.getString(key, default) ?: default
    fun putString(key: String, value: String?) = raw.edit { if (value == null) remove(key) else putString(key, value) }
    fun boolean(key: String, default: Boolean): Boolean = raw.getBoolean(key, default)
    fun putBoolean(key: String, value: Boolean) = raw.edit { putBoolean(key, value) }
    fun long(key: String): Long? = if (raw.contains(key)) raw.getLong(key, 0) else null
    fun putLong(key: String, value: Long?) = raw.edit { if (value == null) remove(key) else putLong(key, value) }
    fun remove(vararg keys: String) = raw.edit { keys.forEach(::remove) }
}

/**
 * Chiffrement AES-256-GCM avec une clé non exportable de l'Android Keystore : session,
 * et données gardées pour le hors-ligne (tâches, personnes, projets), qui ne sont donc jamais en clair sur le téléphone.
 */
class Crypto {
    private val alias = "humbleplanning_data"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    /** IV (12 octets) + texte chiffré. */
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        cipher.doFinal(data, 12, data.size - 12)
    }.getOrNull()
}

/** Petites valeurs secrètes, chiffrées dans les réglages. */
class SecureStore(private val prefs: Prefs, private val crypto: Crypto) {
    private val prefix = "secure_"

    fun get(key: String): String? {
        val stored = prefs.raw.getString(prefix + key, null) ?: return null
        return crypto.decrypt(Base64.decode(stored, Base64.NO_WRAP))?.decodeToString()
    }

    fun set(key: String, value: String?) {
        if (value == null) {
            prefs.remove(prefix + key)
        } else {
            prefs.putString(prefix + key, Base64.encodeToString(crypto.encrypt(value.encodeToByteArray()), Base64.NO_WRAP))
        }
    }
}

/** Fichiers chiffrés (données hors-ligne), dans le stockage privé de l'app. */
class EncryptedFiles(context: Context, private val crypto: Crypto) {
    private val dir = File(context.filesDir, "offline").apply { mkdirs() }

    fun read(name: String): String? {
        val file = File(dir, name)
        if (!file.exists()) return null
        return crypto.decrypt(file.readBytes())?.decodeToString()
    }

    fun write(name: String, content: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(crypto.encrypt(content.encodeToByteArray()))
        tmp.renameTo(File(dir, name))
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
