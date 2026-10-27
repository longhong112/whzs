package cn.lhup.wechatmood.core

import android.content.Context
import cn.lhup.wechatmood.reply.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** 角色/背景直接存放在共享数据库，微信进程与设置页读写同一份数据。 */
object ReplyIdentityBridge {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "weihua-reply-identity") }
    @Volatile private var cachedStore: ReplyIdentityStore? = null

    private fun store(): ReplyIdentityStore {
        cachedStore?.let { return it }
        synchronized(this) {
            cachedStore?.let { return it }
            val created = ReplyIdentityStore(AndroidCacheDatabase.open(SharedStorage.appContext))
            cachedStore = created
            return created
        }
    }

    fun background(context: Context, key: ReplyContactKey): ContactBackground? =
        runCatching { store().background(key) }.getOrNull()

    suspend fun loadBackground(context: Context, key: ReplyContactKey): ContactBackground =
        withContext(Dispatchers.IO) { store().background(key) }

    suspend fun saveBackground(context: Context, owner: ReplyIdentityOwner, text: String,
        verify: () -> String?): ContactBackground = withContext(Dispatchers.IO) {
        check(owner.acceptsAccount(verify())) { "账号已变化，请重新打开" }
        val key = requireNotNull(owner.key) { "当前聊天不支持保存角色" }
        store().saveBackground(key, text).also { ModulePrefs.backgroundChanged(owner.talker) }
    }

    suspend fun roles(context: Context): List<ReplyRole> = withContext(Dispatchers.IO) { store().roles.list() }

    suspend fun saveRole(context: Context, owner: ReplyIdentityOwner, identity: ReplyIdentitySetting, text: String,
        verify: () -> String?): AppliedReplyRole = withContext(Dispatchers.IO) {
        check(owner.acceptsAccount(verify())) { "账号已变化，请重新打开" }
        val key = requireNotNull(owner.key) { "当前聊天不支持保存角色" }
        store().roles.saveFromChat(key, identity, text).also {
            ModulePrefs.backgroundChanged(owner.talker)
        }
    }

    suspend fun applyRole(context: Context, owner: ReplyIdentityOwner, role: ReplyRole,
        verify: () -> String?): AppliedReplyRole = withContext(Dispatchers.IO) {
        check(owner.acceptsAccount(verify())) { "账号已变化，请重新打开" }
        val key = requireNotNull(owner.key) { "当前聊天不支持保存角色" }
        store().roles.apply(role.id, role.revision, key).also { ModulePrefs.backgroundChanged(owner.talker) }
    }

    suspend fun load(context: Context, key: ReplyContactKey): ReplyIdentitySetting =
        withContext(Dispatchers.IO) { store().find(key) }

    fun save(context: Context, owner: ReplyIdentityOwner, value: ReplyIdentitySetting,
        verify: () -> String?, complete: (Boolean) -> Unit) {
        val key = owner.key ?: run { complete(false); return }
        worker.execute {
            val saved = runCatching {
                check(owner.acceptsAccount(verify()))
                store().save(key, value)
            }.isSuccess
            if (saved) ModulePrefs.backgroundChanged(owner.talker)
            complete(saved)
        }
    }
}
