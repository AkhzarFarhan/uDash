package com.ddpai.uploader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ddpai.uploader.data.db.entity.LogEntity
import com.ddpai.uploader.data.db.entity.VideoFileEntity

@Database(entities = [VideoFileEntity::class, LogEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoFileDao(): VideoFileDao
    abstract fun logDao(): LogDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "ddpai.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
