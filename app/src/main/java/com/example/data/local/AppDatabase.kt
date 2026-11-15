package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.AnalysisDao
import com.example.data.local.dao.PredictionDao
import com.example.data.local.dao.ResultDao
import com.example.data.local.entity.AnalysisEntity
import com.example.data.local.entity.PredictionEntity
import com.example.data.local.entity.ResultEntity

@Database(
    entities = [
        ResultEntity::class,
        PredictionEntity::class,
        AnalysisEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(RoomTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun resultDao(): ResultDao
    abstract fun predictionDao(): PredictionDao
    abstract fun analysisDao(): AnalysisDao

    companion object {
        private const val DB_NAME = "anshu_wingo_ai.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wingo_results RENAME TO actual_results")
                db.execSQL("ALTER TABLE wingo_predictions RENAME TO predictions")
                db.execSQL("ALTER TABLE predictions ADD COLUMN contributingModels TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS analysis_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        moduleId TEXT NOT NULL,
                        moduleName TEXT NOT NULL,
                        moduleVersion TEXT NOT NULL,
                        status TEXT NOT NULL,
                        sampleSize INTEGER NOT NULL,
                        validationSampleSize INTEGER NOT NULL,
                        lastExecutedAt INTEGER NOT NULL,
                        logLoss REAL,
                        brierScore REAL,
                        accuracy REAL,
                        baselineBrierScore REAL,
                        assignedWeight REAL NOT NULL,
                        summaryNote TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_analysis_runs_moduleId ON analysis_runs(moduleId)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE actual_results ADD COLUMN source TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
