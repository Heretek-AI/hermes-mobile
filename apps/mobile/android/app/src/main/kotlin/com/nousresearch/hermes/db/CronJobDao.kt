package com.nousresearch.hermes.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data-access object for the `cron_jobs` table. Used by the
 * Phase 4.7 Schedules screen and the Phase 1.1 `listCronJobs`
 * / `createCronJob` / etc. methods in [com.nousresearch.hermes.HermesApi].
 */
@Dao
interface CronJobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: CronJobEntity)

    @Query("SELECT * FROM cron_jobs ORDER BY name ASC")
    suspend fun listAll(): List<CronJobEntity>

    @Query("DELETE FROM cron_jobs WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("UPDATE cron_jobs SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean): Int

    @Query("UPDATE cron_jobs SET last_run = :timestamp WHERE id = :id")
    suspend fun setLastRun(id: String, timestamp: Long)

    @Query("SELECT * FROM cron_jobs WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CronJobEntity?
}
