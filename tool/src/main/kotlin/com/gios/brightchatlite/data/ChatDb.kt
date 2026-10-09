package com.gios.brightchatlite.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

/** One conversation row; [payload] is the serialized [com.gios.brightchatlite.model.Conversation]. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val guid: String,
    val sortDate: Long,
    val payload: String,
)

/** One message; [payload] is the serialized [com.gios.brightchatlite.model.ChatMessage]. */
@Entity(tableName = "messages", indices = [Index("chatGuid", "date")])
data class MessageEntity(
    @PrimaryKey val guid: String,
    val chatGuid: String,
    val date: Long,
    val payload: String,
)

@Dao
abstract class ChatDao {
    @Query("SELECT * FROM conversations ORDER BY sortDate DESC")
    abstract suspend fun conversations(): List<ConversationEntity>

    @Query("DELETE FROM conversations")
    abstract suspend fun clearConversations()

    @Upsert
    abstract suspend fun upsertConversations(rows: List<ConversationEntity>)

    @Transaction
    open suspend fun replaceConversations(rows: List<ConversationEntity>) {
        clearConversations()
        upsertConversations(rows)
    }

    @Query("SELECT * FROM messages WHERE chatGuid IN (:chatGuids) ORDER BY date DESC LIMIT :limit")
    abstract suspend fun messages(chatGuids: List<String>, limit: Int): List<MessageEntity>

    @Upsert
    abstract suspend fun upsertMessages(rows: List<MessageEntity>)

    @Query("DELETE FROM messages")
    abstract suspend fun clearMessages()

    /** Keeps the cache bounded: drops everything but the newest [keep] messages. */
    @Query("DELETE FROM messages WHERE guid NOT IN (SELECT guid FROM messages ORDER BY date DESC LIMIT :keep)")
    abstract suspend fun trimMessages(keep: Int)
}

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 1, exportSchema = false)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun dao(): ChatDao
}
