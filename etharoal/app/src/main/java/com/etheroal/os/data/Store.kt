package com.etheroal.os.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where a captured notification is in its life. */
enum class ItemStatus { PENDING, TRIAGED, FAILED, DISMISSED }

/**
 * One captured notification. Text stays on the phone in an encrypted database; triage fields
 * are filled in by the on-device model.
 */
@Entity(
    tableName = "items",
    indices = [
        Index("conversation"), Index("status"),
        // One row per posted notification: re-reading the shade (service restart) must not duplicate.
        Index(value = ["notificationKey", "postedAt"], unique = true),
    ],
)
data class NotificationItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The system's key for the live notification, used to find its reply action. */
    val notificationKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    /** Messages from the same chat or sender are grouped under one key. */
    val conversation: String,
    val postedAt: Long,
    val canReply: Boolean,
    val status: ItemStatus = ItemStatus.PENDING,
    val urgency: String? = null,
    val category: String? = null,
    val needsReply: Boolean = false,
    val summary: String? = null,
    /** "ON_DEVICE" or "IN_CLOUD", from the router. */
    val source: String? = null,
    val engine: String? = null,
    val failure: String? = null,
    val triagedAt: Long? = null,
    /** INTERRUPT, DIGEST or FILE: what the Chief of Staff did with it after sorting. */
    val disposition: String? = null,
    /** Why, in words the owner sees ("You always clear these without opening them"). */
    val reason: String? = null,
)

@Dao
interface ItemDao {
    /** Returns -1 when this exact notification is already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: NotificationItem): Long

    @Query("SELECT * FROM items WHERE status = 'PENDING' ORDER BY postedAt ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<NotificationItem>

    @Query("SELECT COUNT(*) FROM items WHERE status = 'PENDING'")
    fun pendingCount(): Flow<Int>

    @Query(
        "UPDATE items SET status = 'TRIAGED', urgency = :urgency, category = :category, needsReply = :needsReply, " +
            "summary = :summary, source = :source, engine = :engine, triagedAt = :at, failure = NULL WHERE id = :id",
    )
    suspend fun triaged(id: Long, urgency: String, category: String, needsReply: Boolean, summary: String, source: String, engine: String?, at: Long)

    @Query("UPDATE items SET status = 'FAILED', failure = :reason WHERE id = :id")
    suspend fun failed(id: Long, reason: String)

    /** Items that failed to sort get another go once the cloud is reachable again. */
    @Query("UPDATE items SET status = 'PENDING', failure = NULL WHERE status = 'FAILED' AND postedAt > :since")
    suspend fun retryFailed(since: Long)

    @Query("UPDATE items SET status = 'DISMISSED' WHERE conversation = :conversation")
    suspend fun dismissConversation(conversation: String)

    /** Undo of [dismissConversation]: items go back to sorted, or to the queue if never sorted. */
    @Query(
        "UPDATE items SET status = CASE WHEN triagedAt IS NOT NULL THEN 'TRIAGED' ELSE 'PENDING' END " +
            "WHERE conversation = :conversation AND status = 'DISMISSED'",
    )
    suspend fun undismissConversation(conversation: String)

    @Query("UPDATE items SET disposition = :disposition, reason = :reason WHERE id = :id")
    suspend fun dispose(id: Long, disposition: String, reason: String)

    /** Undo of filing: the conversation goes back to the digest. */
    @Query("UPDATE items SET disposition = 'DIGEST', reason = 'You moved it back' WHERE conversation = :conversation AND disposition = 'FILE'")
    suspend fun unfile(conversation: String)

    @Query("SELECT COUNT(*) FROM items WHERE conversation = :conversation AND status = 'TRIAGED' AND disposition = 'FILE'")
    suspend fun filedCount(conversation: String): Int

    /** Sorted, still-open items since [since]: the digest. */
    @Query("SELECT * FROM items WHERE status = 'TRIAGED' AND postedAt > :since ORDER BY postedAt DESC")
    suspend fun sortedSince(since: Long): List<NotificationItem>

    @Query("UPDATE items SET needsReply = 0 WHERE conversation = :conversation")
    suspend fun replied(conversation: String)

    /** Everything still worth showing, newest first. */
    @Query("SELECT * FROM items WHERE status != 'DISMISSED' AND postedAt > :since ORDER BY postedAt DESC")
    fun feed(since: Long): Flow<List<NotificationItem>>

    @Query("SELECT * FROM items WHERE conversation = :conversation AND status != 'DISMISSED' ORDER BY postedAt ASC")
    suspend fun thread(conversation: String): List<NotificationItem>

    /** Debug diagnostics: counts per status, never content. */
    @Query("SELECT status, COUNT(*) AS n, MIN(postedAt) AS oldest, MAX(postedAt) AS newest FROM items GROUP BY status")
    suspend fun stats(): List<StatusCount>

    @Query("DELETE FROM items WHERE postedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

data class StatusCount(val status: String, val n: Int, val oldest: Long, val newest: Long)

@Database(entities = [NotificationItem::class, SessionEntity::class, MessageEntity::class, com.etheroal.os.vault.VaultIndex::class, com.etheroal.os.mind.MemoryEntity::class, com.etheroal.os.mind.HabitEntity::class], version = 5, exportSchema = false)
abstract class EtheroalDb : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun sessions(): SessionDao
    abstract fun vaultIndex(): com.etheroal.os.vault.VaultIndexDao
    abstract fun mind(): com.etheroal.os.mind.MindDao

    companion object {
        /** v2: drop duplicate captures, then make (notificationKey, postedAt) unique. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM items WHERE id NOT IN (SELECT MIN(id) FROM items GROUP BY notificationKey, postedAt)")
                db.execSQL("DROP INDEX IF EXISTS index_items_notificationKey")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_items_notificationKey_postedAt ON items (notificationKey, postedAt)")
            }
        }

        /** v3: conversations with Etheroal and their messages, and the vault's non-secret index. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sessions` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, " +
                        "`role` TEXT NOT NULL, `text` TEXT NOT NULL, `at` INTEGER NOT NULL, `ok` INTEGER NOT NULL, " +
                        "`agent` TEXT, `durationMs` INTEGER, `imagePath` TEXT)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_sessionId` ON `messages` (`sessionId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vault_index` (`id` INTEGER NOT NULL, `label` TEXT NOT NULL, `target` TEXT NOT NULL, " +
                        "`usernameHint` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /** v4: what Etheroal learns (memories) and habit counts. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `habits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `key` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, `at` INTEGER NOT NULL, `hour` INTEGER NOT NULL, `weekday` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_habits_at` ON `habits` (`at`)")
            }
        }

        /** v5: what the Chief of Staff did with each sorted notification, and why. */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `disposition` TEXT")
                db.execSQL("ALTER TABLE `items` ADD COLUMN `reason` TEXT")
            }
        }

        fun open(context: Context): EtheroalDb {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, EtheroalDb::class.java, "etheroal.db")
                .openHelperFactory(SupportOpenHelperFactory(DatabaseKey.get(context)))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
        }
    }
}

/**
 * The database passphrase: 32 random bytes, stored only in encrypted form. The wrapping key lives
 * in Android Keystore (hardware-backed where the phone has it) and cannot be exported.
 */
object DatabaseKey {
    private const val ALIAS = "etheroal_db_wrap"
    private const val PREFS = "etheroal_db_key"

    fun get(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString("wrapped", null)
        if (stored != null) {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
            return cipher.doFinal(bytes, 12, bytes.size - 12)
        }
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val sealed = cipher.iv + cipher.doFinal(passphrase)
        prefs.edit().putString("wrapped", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
        return passphrase
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
