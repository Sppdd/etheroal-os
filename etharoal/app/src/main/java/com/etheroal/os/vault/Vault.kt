package com.etheroal.os.vault

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.etheroal.os.data.SessionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A stored secret. Lives only in vault.db, which can be opened only right after the owner unlocks. */
@Entity(tableName = "secrets")
data class VaultSecret(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val secret: String,
)

@Dao
interface SecretDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(s: VaultSecret): Long

    @Query("SELECT * FROM secrets WHERE id = :id")
    fun get(id: Long): VaultSecret?

    @Query("DELETE FROM secrets WHERE id = :id")
    fun delete(id: Long)
}

@Database(entities = [VaultSecret::class], version = 1, exportSchema = false)
abstract class VaultDb : RoomDatabase() {
    abstract fun secrets(): SecretDao
}

/**
 * What the agent may know about a vault entry: its label, app and a masked username. Kept in the
 * main database so the agent can list entries without unlocking anything. Never the secret.
 */
@Entity(tableName = "vault_index")
data class VaultIndex(
    @PrimaryKey val id: Long,
    val label: String,
    /** App package or website the login belongs to, e.g. "com.ubercab" or "uber.com". */
    val target: String,
    val usernameHint: String,
    val updatedAt: Long,
)

@Dao
interface VaultIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(i: VaultIndex)

    @Query("SELECT * FROM vault_index ORDER BY label COLLATE NOCASE")
    fun all(): Flow<List<VaultIndex>>

    @Query("SELECT * FROM vault_index ORDER BY label COLLATE NOCASE")
    suspend fun list(): List<VaultIndex>

    @Query("SELECT * FROM vault_index WHERE id = :id")
    suspend fun get(id: Long): VaultIndex?

    @Query("DELETE FROM vault_index WHERE id = :id")
    suspend fun delete(id: Long)
}

class VaultLockedException : Exception("The vault is locked")

/**
 * The vault: logins and other secrets, isolated from everything else in Etheroal.
 *
 * - Secrets are in their own SQLCipher database, vault.db, with its own random passphrase.
 * - That passphrase is wrapped by an Android Keystore key that works only for 30 seconds after the
 *   owner unlocks with a fingerprint, face or the screen lock, and is destroyed if biometrics change.
 * - Models never receive secrets. The agent sees [VaultIndex] rows; a secret is read only to be put
 *   straight into a password field by the accessibility service, after the owner unlocks.
 */
class Vault(private val context: Context, private val index: VaultIndexDao) {

    val entries: Flow<List<VaultIndex>> = index.all()

    suspend fun list(): List<VaultIndex> = index.list()

    /** A screen lock is required: without one, Android cannot protect the key. */
    fun deviceSecure(): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /** Must be called within 30 s of [VaultUnlock] succeeding, or it throws [VaultLockedException]. */
    suspend fun save(id: Long?, label: String, target: String, username: String, secret: String): Long = withContext(Dispatchers.IO) {
        withDb { db ->
            val newId = db.secrets().put(VaultSecret(id ?: 0, username, secret))
            index.put(VaultIndex(newId, label.trim().take(60), target.trim().take(120), hint(username), System.currentTimeMillis()))
            newId
        }
    }

    suspend fun read(id: Long): VaultSecret? = withContext(Dispatchers.IO) { withDb { it.secrets().get(id) } }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        withDb { it.secrets().delete(id) }
        index.delete(id)
    }

    /** Opens vault.db for one operation and closes it straight after: it is never left open. */
    private inline fun <T> withDb(block: (VaultDb) -> T): T {
        val db = open()
        try { return block(db) } finally { db.close() }
    }

    private fun open(): VaultDb {
        System.loadLibrary("sqlcipher")
        val key = passphrase()
        return Room.databaseBuilder(context, VaultDb::class.java, "vault.db")
            .openHelperFactory(SupportOpenHelperFactory(key))
            .allowMainThreadQueries()      // only ever used from Dispatchers.IO above; Room's check is per-call
            .build()
    }

    private fun passphrase(): ByteArray = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("wrapped", null)
        if (stored != null) {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
                doFinal(bytes, 12, bytes.size - 12)
            }
        } else {
            val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            prefs.edit().putString("wrapped", Base64.encodeToString(cipher.iv + cipher.doFinal(pass), Base64.NO_WRAP)).commit()
            pass
        }
    } catch (e: UserNotAuthenticatedException) {
        throw VaultLockedException()
    } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
        // Biometrics changed: the old secrets can no longer be opened, by design.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("vault.db")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        throw VaultLockedException()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= 30) {
                    setUserAuthenticationParameters(UNLOCK_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(UNLOCK_SECONDS)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    companion object {
        private const val ALIAS = "etheroal_vault_wrap"
        private const val PREFS = "etheroal_vault_key"
        const val UNLOCK_SECONDS = 30

        /** "kar…@gmail.com" / "ka…89": enough for the agent and the owner to tell entries apart. */
        fun hint(username: String): String {
            val u = username.trim()
            if (u.isEmpty()) return ""
            val at = u.indexOf('@')
            return if (at > 0) u.take(minOf(3, at)) + "…" + u.substring(at) else u.take(2) + "…" + u.takeLast(2).takeIf { u.length > 5 }.orEmpty()
        }
    }
}
