package com.paperview.mobile.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Account secrets (passwords, secret keys), encrypted with an AES key that
 * lives in the Android Keystore and never leaves it. The ciphertext sits in
 * a private preferences file, one entry per account id. If the Keystore
 * can't be used, the caller keeps the secret in the account itself
 * ([Account.plainSecret]) and Settings says so.
 */
object Secrets {
    private const val ALIAS = "paperview_cloud_secrets"
    private const val FILE = "cloud_secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Stores a secret; false when the Keystore can't be used. */
    fun store(context: Context, account: String, secret: String): Boolean = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(secret.toByteArray())
        prefs(context).edit().putString(account, Base64.getEncoder().encodeToString(sealed)).apply()
    }.isSuccess

    /** The account's secret: its settings copy, else the Keystore one, else "". */
    fun get(context: Context, acct: Account): String {
        acct.plainSecret?.takeIf { it.isNotEmpty() }?.let { return it }
        val raw = prefs(context).getString(acct.id, null) ?: return ""
        return runCatching {
            val sealed = Base64.getDecoder().decode(raw)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            String(cipher.doFinal(sealed, 12, sealed.size - 12))
        }.getOrDefault("")
    }

    fun has(context: Context, account: String): Boolean = prefs(context).contains(account)

    fun forget(context: Context, account: String) {
        prefs(context).edit().remove(account).apply()
    }
}
