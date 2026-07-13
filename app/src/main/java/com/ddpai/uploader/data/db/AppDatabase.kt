package com.ddpai.uploader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ddpai.uploader.data.db.entity.LogEntity
import com.ddpai.uploader.data.db.entity.VideoFileEntity

@Database(entities = [VideoFileEntity::class, LogEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoFileDao(): VideoFileDao
    abstract fun logDao(): LogDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE video_files ADD COLUMN kind TEXT NOT NULL DEFAULT 'SEGMENT'")
                db.execSQL("ALTER TABLE video_files ADD COLUMN mergedInto TEXT")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "ddpai.db"
                ).addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
            }
    }
}
