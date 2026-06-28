package com.ddpai.uploader.data.db

import androidx.room.*
import com.ddpai.uploader.data.db.entity.LogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insert(log: LogEntity)

    @Query("SELECT * FROM app_logs ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<LogEntity>>

    @Query("DELETE FROM app_logs WHERE id NOT IN (SELECT id FROM app_logs ORDER BY id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int = 2000)

    @Query("DELETE FROM app_logs")
    suspend fun clear()
}
