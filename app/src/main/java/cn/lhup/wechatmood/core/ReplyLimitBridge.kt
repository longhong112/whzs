package cn.lhup.wechatmood.core

import android.content.Context
import cn.lhup.wechatmood.reply.ReplyHistoryLimit
import cn.lhup.wechatmood.reply.ReplyIdentityOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 每个账号的“读取历史条数”保存在共享数据库。 */
object ReplyLimitBridge {
    private fun db(): AnalysisCacheDatabase {
        val database = AndroidCacheDatabase.open(SharedStorage.appContext)
        database.execute(
            "CREATE TABLE IF NOT EXISTS reply_limits (account TEXT PRIMARY KEY NOT NULL, value INTEGER NOT NULL)"
        )
        return database
    }

    suspend fun load(context: Context, account: String?): Int = withContext(Dispatchers.IO) {
        if (account == null) return@withContext ReplyHistoryLimit.DEFAULT
        runCatching {
            db().query("SELECT value FROM reply_limits WHERE account = ?", listOf(account))
                ?.toIntOrNull()?.takeIf(ReplyHistoryLimit::valid)
        }.getOrNull() ?: ReplyHistoryLimit.DEFAULT
    }

    fun save(context: Context, owner: ReplyIdentityOwner, value: Int, verify: () -> String?, complete: (Boolean) -> Unit) {
        require(ReplyHistoryLimit.valid(value))
        val account = owner.account ?: run { complete(false); return }
        kotlin.concurrent.thread(name = "weihua-reply-limit") {
            val saved = runCatching {
                check(owner.acceptsAccount(verify()))
                db().execute("INSERT OR REPLACE INTO reply_limits(account, value) VALUES(?, ?)",
                    listOf(account, value.toString()))
            }.isSuccess
            complete(saved)
        }
    }
}
