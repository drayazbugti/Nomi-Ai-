package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        StudioSessionEntity::class,
        StudioAssetEntity::class,
        PromptHistoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class NoomiDatabase : RoomDatabase() {
    abstract fun sessionDao(): StudioSessionDao
    abstract fun assetDao(): StudioAssetDao
    abstract fun promptHistoryDao(): PromptHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: NoomiDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE studio_assets ADD COLUMN isTrashed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE studio_assets ADD COLUMN trashedAt INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `prompt_history` (
                        `id` TEXT NOT NULL,
                        `promptText` TEXT NOT NULL,
                        `mediaMode` TEXT NOT NULL,
                        `aspectRatio` TEXT NOT NULL,
                        `modelLabel` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE studio_assets ADD COLUMN generationDurationMs INTEGER")
                db.execSQL("ALTER TABLE studio_assets ADD COLUMN folderTag TEXT")
            }
        }

        fun getInstance(context: Context): NoomiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NoomiDatabase::class.java,
                    "noomi_studio.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
