package com.nexchat.app.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        RelayPacketEntity::class,
        ProcessedEnvelopeEntity::class,
        CallHistoryEntity::class,
    ],
    version = 1,
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
    abstract fun relayDao(): RelayDao
    abstract fun processedEnvelopeDao(): ProcessedEnvelopeDao
    abstract fun callHistoryDao(): CallHistoryDao
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
