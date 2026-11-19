package com.nexchat.app.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        ProfileEntity::class,
        ContactEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        SignalStateEntity::class,
        OutboxEntity::class,
        PendingIntentEntity::class,
        RelayPacketEntity::class,
        ProcessedEnvelopeEntity::class,
        CallHistoryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class NexDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun groupDao(): GroupDao
    abstract fun signalStateDao(): SignalStateDao
    abstract fun outboxDao(): OutboxDao
    abstract fun pendingIntentDao(): PendingIntentDao
    abstract fun relayDao(): RelayDao
    abstract fun processedEnvelopeDao(): ProcessedEnvelopeDao
    abstract fun callHistoryDao(): CallHistoryDao
}

/**
 * Adds the retry schedule to already-encrypted outbox rows and introduces the
 * plaintext-content intent queue. Both live inside the SQLCipher database, so a
 * message that was composed while offline survives process death without ever
 * being written to unencrypted storage.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE outbox ADD COLUMN nextAttemptAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE outbox ADD COLUMN lastError TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_intents` (" +
                "`id` TEXT NOT NULL, " +
                "`clientId` TEXT NOT NULL, " +
                "`conversationId` TEXT NOT NULL, " +
                "`contentJson` TEXT NOT NULL, " +
                "`durable` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`expiresAt` INTEGER NOT NULL, " +
                "`attempts` INTEGER NOT NULL, " +
                "`lastAttemptAt` INTEGER, " +
                "`nextAttemptAt` INTEGER NOT NULL DEFAULT 0, " +
                "`lastError` TEXT, " +
                "`materializedAt` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_intents_clientId` " +
                "ON `pending_intents` (`clientId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_intents_nextAttemptAt` " +
                "ON `pending_intents` (`nextAttemptAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_intents_createdAt` " +
                "ON `pending_intents` (`createdAt`)",
        )
    }
}

object DatabaseProvider {
    const val DATABASE_NAME = "nexchat_v1.db"

    @Volatile
    private var database: NexDatabase? = null

    @Synchronized
    fun get(context: Context, passphrase: ByteArray): NexDatabase {
        database?.let { return it }
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(passphrase)
        val db = Room.databaseBuilder(context.applicationContext, NexDatabase::class.java, DATABASE_NAME)
            .openHelperFactory(factory)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(MIGRATION_1_2)
            .build()
        database = db
        return db
    }

    @Synchronized
    fun closeAndDelete(context: Context) {
        database?.close()
        database = null
        context.applicationContext.deleteDatabase(DATABASE_NAME)
    }
}
