package com.nousresearch.hermes.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A scheduled cron job, persisted in the local Room database.
 * The cron expression is stored as a string; the gateway is
 * the source of truth for actually running the schedule (the
 * Kotlin side just stores + forwards). A WorkManager
 * `PeriodicWorkRequest` is also registered as a backup so the
 * user can see the job in the system-level WorkManager
 * settings even if the gateway is offline.
 */
@Entity(tableName = "cron_jobs")
data class CronJobEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "cron_expr")
    val cronExpr: String,
    @ColumnInfo(name = "command")
    val command: String,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,
    @ColumnInfo(name = "last_run")
    val lastRun: Long? = null,
    @ColumnInfo(name = "next_run")
    val nextRun: Long? = null,
)
