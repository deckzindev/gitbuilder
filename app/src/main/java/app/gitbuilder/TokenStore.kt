package app.gitbuilder

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Token do GitHub cifrado com AES-256-GCM; a chave fica no Android Keystore e não é exportável. */
object TokenStore {
    private const val PREFS = "token_store"
    private const val KEY = "token"
    private const val ALIAS = "gitbuilder_token_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12

    // Resíduos da versão anterior (EncryptedSharedPreferences), removidos assim que um token novo é salvo.
    private const val LEGACY_PREFS = "secure_prefs"
    private const val LEGACY_ALIAS = "_androidx_security_crypto_master_key_"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    fun get(c: Context): String? {
        return try {
            val stored = prefs(c).getString(KEY, null) ?: return null
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_SIZE))
            String(cipher.doFinal(raw, IV_SIZE, raw.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    private fun write(c: Context, t: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(t.toByteArray(Charsets.UTF_8))
        prefs(c).edit().putString(KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).commit() && get(c) == t
    } catch (e: Exception) { false }

    /** Grava de forma síncrona e confere lendo de volta. Se a chave do Keystore estiver inutilizável, recria e tenta de novo. */
    fun save(c: Context, t: String): Boolean {
        val ok = write(c, t) || run {
            try { keyStore().deleteEntry(ALIAS) } catch (e: Exception) { }
            write(c, t)
        }
        if (ok) {
            c.deleteSharedPreferences(LEGACY_PREFS)
            try { keyStore().deleteEntry(LEGACY_ALIAS) } catch (e: Exception) { }
        }
        return ok
    }
}
