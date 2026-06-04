package com.nousresearch.hermes.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nousresearch.hermes.chat.MessageEntity

/**
 * The on-device Room database. Phase A only has the `messages`
 * table; future phases add `sessions`, `attachments`, `tool_cache`,
 * `profile_cache`, etc. Bump the version + add a migration when
 * the schema grows.
 *
 * The database file lives at the app's default Room location
 * (`/data/data/com.nousresearch.hermes/databases/hermes.db`) so
 * the smoke test's `adb shell run-as ... sqlite3 hermes.db` can
 * open it without arguments.
 */
@Database(
    entities = [MessageEntity::class, CronJobEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class HermesDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    abstract fun cronJobDao(): CronJobDao

    companion object {
        const val DB_NAME = "hermes.db"

        /** Singleton accessor. HermesApi owns the instance for
         *  the process lifetime. */
        @Volatile private var instance: HermesDatabase? = null

        fun get(context: Context): HermesDatabase = instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }

        private fun build(context: Context): HermesDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                HermesDatabase::class.java,
                DB_NAME,
            )
                // Phase 1.1: destructive migration from v1 (messages
                // only) to v2 (messages + cron_jobs). Chat history is
                // recoverable from the gateway (we re-fetch on
                // reconnect); the local cron job list is small and
                // loss-tolerant in v1.
                .fallbackToDestructiveMigration()
                .build()
    }
}
