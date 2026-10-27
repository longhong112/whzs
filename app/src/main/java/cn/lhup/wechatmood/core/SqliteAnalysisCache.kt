package cn.lhup.wechatmood.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.security.MessageDigest

/** 设置页与微信进程共用的极简数据库接口，避免 Binder/ContentProvider 依赖。 */
interface AnalysisCacheDatabase : Closeable {
    fun execute(sql: String, args: List<String> = emptyList())
    fun query(sql: String, args: List<String> = emptyList()): String?
}

fun <T> AnalysisCacheDatabase.transaction(block: () -> T): T {
    execute("BEGIN IMMEDIATE")
    return try {
        val value = block()
        execute("COMMIT")
        value
    } catch (error: Throwable) {
        runCatching { execute("ROLLBACK") }
        throw error
    }
}

object AnalysisCacheKey {
    fun digest(vararg values: String): String {
        val source = values.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** 直接打开共享目录中的数据库；两个进程打开同一文件，SQLite 自身负责加锁。 */
class AndroidCacheDatabase private constructor(private val db: SQLiteDatabase) : AnalysisCacheDatabase {
    @Synchronized override fun execute(sql: String, args: List<String>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.toTypedArray())
    }

    @Synchronized override fun query(sql: String, args: List<String>): String? = runCatching {
        db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    @Synchronized override fun close() {
        // 进程内长期复用，不做真正关闭，避免并发访问已关闭连接。
    }

    companion object {
        @Volatile private var instance: AndroidCacheDatabase? = null

        fun open(context: Context): AndroidCacheDatabase {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val file = SharedStorage.databaseFile()
                val db = SQLiteDatabase.openOrCreateDatabase(file, null)
                runCatching { db.rawQuery("PRAGMA busy_timeout=8000", null).use { } }
                runCatching { db.rawQuery("PRAGMA journal_mode=WAL", null).use { } }
                val opened = AndroidCacheDatabase(db)
                instance = opened
                return opened
            }
        }
    }
}

/** AI 结果缓存：只按“目标消息 + 提示词 + 模型”命中，新消息到达不会复用旧结果。 */
object AiCacheStore {
    private const val TABLE = "ai_cache"

    private fun db(): AnalysisCacheDatabase {
        val database = AndroidCacheDatabase.open(SharedStorage.appContext)
        database.execute(
            "CREATE TABLE IF NOT EXISTS $TABLE " +
                "(cache_key TEXT PRIMARY KEY NOT NULL, body TEXT NOT NULL, updated_at INTEGER NOT NULL)"
        )
        return database
    }

    fun load(key: String): String? = runCatching {
        db().query("SELECT body FROM $TABLE WHERE cache_key = ?", listOf(key))
    }.getOrNull()

    fun save(key: String, body: String) {
        runCatching {
            db().execute(
                "INSERT OR REPLACE INTO $TABLE(cache_key, body, updated_at) VALUES(?, ?, ?)",
                listOf(key, body, System.currentTimeMillis().toString()),
            )
        }
    }

    fun key(vararg parts: String): String = AnalysisCacheKey.digest("ai-cache-v1", *parts)
}
