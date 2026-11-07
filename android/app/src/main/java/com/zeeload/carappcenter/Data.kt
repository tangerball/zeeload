package com.zeeload.carappcenter

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "apps")
data class ManagedApp(
    @PrimaryKey val packageName: String,
    val name: String,
    val url: String = "",
    val origin: String,
    val versionCode: Long = 0,
    val versionName: String = "",
    val certificateSha256: String = "",
    val apkSha256: String = "",
    val installedAt: Long = 0,
    val updatedAt: Long = 0,
    val lastResult: String = ""
)

@Entity(tableName = "history")
data class InstallRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val at: Long,
    val versionCode: Long,
    val result: String,
    val apkSha256: String
)

@Entity(tableName = "events")
data class DiagnosticEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val level: String,
    val stage: String,
    val packageName: String = "",
    val versionCode: Long = 0,
    val url: String = "",
    val httpStatus: Int = 0,
    val errorCode: String = "",
    val message: String = "",
    val sizeBytes: Int = 0,
    val uploaded: Boolean = false
)

/** 拦截事件（持久化，跨重启可关联）：武装 / 注入 / 替换 / 对账 / 模块 … */
@Entity(tableName = "intercept_events", indices = [Index("ts")])
data class InterceptEventRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val kind: String,
    val detail: String
)

@Dao
interface InterceptEventDao {
    @Insert
    suspend fun add(event: InterceptEventRecord)

    /** 时间窗内的记录（历史页关联用）。 */
    @Query("SELECT * FROM intercept_events WHERE ts BETWEEN :from AND :to ORDER BY ts ASC LIMIT :limit")
    suspend fun inWindow(from: Long, to: Long, limit: Int): List<InterceptEventRecord>

    @Query("SELECT * FROM intercept_events ORDER BY ts DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<InterceptEventRecord>

    @Query("DELETE FROM intercept_events WHERE ts < :cutoff")
    suspend fun deleteBefore(cutoff: Long)

    @Query("DELETE FROM intercept_events WHERE id IN (SELECT id FROM intercept_events ORDER BY ts ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    @Query("SELECT COUNT(*) FROM intercept_events")
    suspend fun count(): Int

    @Query("DELETE FROM intercept_events")
    suspend fun clear()
}

@Dao
interface AppDao {
    @Query("SELECT * FROM apps ORDER BY name COLLATE NOCASE")
    fun observe(): Flow<List<ManagedApp>>

    @Query("SELECT * FROM apps WHERE packageName = :name LIMIT 1")
    suspend fun get(name: String): ManagedApp?

    /** 报告用：一次性取全部记录。 */
    @Query("SELECT * FROM apps ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<ManagedApp>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(app: ManagedApp)

    @Insert
    suspend fun record(record: InstallRecord)

    @Query("SELECT * FROM history ORDER BY id DESC LIMIT 1")
    fun latestRecord(): Flow<InstallRecord?>

    /** 安装历史页：最近 N 条，含 via=direct / via=market 两条路的结果。 */
    @Query("SELECT * FROM history ORDER BY id DESC LIMIT :limit")
    fun observeHistory(limit: Int = 200): Flow<List<InstallRecord>>

    /** 报告用：一次性取历史快照。 */
    @Query("SELECT * FROM history ORDER BY id DESC LIMIT :limit")
    suspend fun historySnapshot(limit: Int = 100): List<InstallRecord>
}

@Dao
interface EventDao {
    @Insert
    suspend fun add(event: DiagnosticEvent)

    @Query("SELECT * FROM events WHERE uploaded = 0 ORDER BY id LIMIT :limit")
    suspend fun pending(limit: Int): List<DiagnosticEvent>

    /** 安装历史展开用：某个包的最近日志。 */
    @Query("SELECT * FROM events WHERE packageName = :pkg ORDER BY id DESC LIMIT :limit")
    suspend fun forPackage(pkg: String, limit: Int): List<DiagnosticEvent>

    /** 报告用：最近的调试日志（不区分是否已上传）。 */
    @Query("SELECT * FROM events ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DiagnosticEvent>

    @Query("DELETE FROM events WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<Long>)

    @Query("DELETE FROM events WHERE ts < :cutoff")
    suspend fun deleteBefore(cutoff: Long)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM events")
    suspend fun totalBytes(): Long

    @Query("SELECT id FROM events ORDER BY id LIMIT 1")
    suspend fun oldestId(): Long?

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM events WHERE uploaded = 0")
    suspend fun pendingCount(): Int

    @Query("DELETE FROM events")
    suspend fun clear()
}

@Database(
    entities = [ManagedApp::class, InstallRecord::class, DiagnosticEvent::class, InterceptEventRecord::class],
    version = 4,
    exportSchema = false
)
abstract class CarDatabase : RoomDatabase() {
    abstract fun apps(): AppDao
    abstract fun events(): EventDao
    abstract fun interceptEvents(): InterceptEventDao

    companion object {
        /** v1 → v2：只新增拦截事件表，不动已有数据。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `intercept_events` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`ts` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`detail` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_intercept_events_ts` ON `intercept_events` (`ts`)")
            }
        }

        /** v2 → v3：历史上加过 flow_runs（v4 已删），保留这步让老库能顺序升级。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `flow_runs` (" +
                        "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`targetPackage` TEXT NOT NULL, " +
                        "`apkName` TEXT NOT NULL, " +
                        "`steps` TEXT NOT NULL, " +
                        "`message` TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_flow_runs_startedAt` ON `flow_runs` (`startedAt`)")
            }
        }

        /** v3 → v4：删掉流程记录表（功能已收敛）。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `flow_runs`")
            }
        }

        fun open(context: Context): CarDatabase =
            Room.databaseBuilder(context, CarDatabase::class.java, "zeeload-car.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
