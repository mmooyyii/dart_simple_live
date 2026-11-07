package com.xycz.simple_live_tv.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.xycz.simplelive.core.common.JsonCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.util.concurrent.Executors

@Serializable
data class FollowUser(
    /** siteId_roomId */
    val id: String,
    val roomId: String,
    val siteId: String,
    val userName: String,
    val face: String,
    val addTime: Long,
)

@Serializable
data class HistoryItem(
    val id: String,
    val roomId: String,
    val siteId: String,
    val userName: String,
    val face: String,
    val updateTime: Long,
)

/**
 * 一条可同步的记录。删除时保留墓碑（deleted=true），合并时按 updatedAt 新者胜
 */
@Serializable
data class SyncRecord(
    val type: String,
    val key: String,
    val data: JsonElement? = null,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/**
 * 本地数据：关注、观看记录、屏蔽词、账号 cookie。数据量小，整体存成一个 JSON 文件
 */
object Store {
    const val TYPE_FOLLOW = "follow"
    const val TYPE_HISTORY = "history"
    const val TYPE_SHIELD = "shield"
    const val TYPE_ACCOUNT = "account"

    /** 墓碑保留时长 */
    private const val TOMBSTONE_TTL = 30L * 24 * 3600 * 1000

    private lateinit var file: File
    private val records = LinkedHashMap<String, SyncRecord>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(type: String) -> Unit>()

    fun init(context: Context) {
        file = File(context.filesDir, "store.json")
        if (file.exists()) {
            try {
                val list = JsonCodec.decodeFromString(ListSerializer(SyncRecord.serializer()), file.readText())
                val now = System.currentTimeMillis()
                list.filterNot { it.deleted && now - it.updatedAt > TOMBSTONE_TTL }
                    .forEach { records[id(it.type, it.key)] = it }
            } catch (e: Exception) {
                android.util.Log.e("Store", "读取数据失败", e)
            }
        }
    }

    private fun id(type: String, key: String) = "$type:$key"

    /** 数据变化监听（主线程回调），返回取消函数 */
    fun listen(listener: (type: String) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun notifyChanged(types: Set<String>) {
        main.post { types.forEach { t -> listeners.toList().forEach { it(t) } } }
    }

    private fun save() {
        val snapshot = synchronized(records) { records.values.toList() }
        io.execute {
            val tmp = File(file.parentFile, "store.json.tmp")
            tmp.writeText(JsonCodec.encodeToString(ListSerializer(SyncRecord.serializer()), snapshot))
            tmp.renameTo(file)
        }
    }

    private fun put(type: String, key: String, data: JsonElement?, deleted: Boolean = false, time: Long = System.currentTimeMillis()) {
        synchronized(records) {
            records[id(type, key)] = SyncRecord(type, key, data, time, deleted)
        }
        save()
        notifyChanged(setOf(type))
    }

    private inline fun <reified T> list(type: String): List<T> = synchronized(records) {
        records.values.filter { it.type == type && !it.deleted && it.data != null }
            .mapNotNull { runCatching { JsonCodec.decodeFromJsonElement<T>(it.data!!) }.getOrNull() }
    }

    private fun alive(type: String, key: String) = synchronized(records) {
        records[id(type, key)]?.let { !it.deleted } ?: false
    }

    // ---------------- 关注 ----------------

    fun follows(): List<FollowUser> = list<FollowUser>(TYPE_FOLLOW).sortedByDescending { it.addTime }

    fun isFollowed(id: String) = alive(TYPE_FOLLOW, id)

    fun follow(user: FollowUser) = put(TYPE_FOLLOW, user.id, JsonCodec.encodeToJsonElement(user))

    fun unfollow(id: String) = put(TYPE_FOLLOW, id, null, deleted = true)

    // ---------------- 观看记录 ----------------

    fun histories(): List<HistoryItem> = list<HistoryItem>(TYPE_HISTORY).sortedByDescending { it.updateTime }

    fun addHistory(item: HistoryItem) = put(TYPE_HISTORY, item.id, JsonCodec.encodeToJsonElement(item))

    fun clearHistory() {
        val now = System.currentTimeMillis()
        synchronized(records) {
            records.values.filter { it.type == TYPE_HISTORY && !it.deleted }.forEach {
                records[id(it.type, it.key)] = it.copy(data = null, deleted = true, updatedAt = now)
            }
        }
        save()
        notifyChanged(setOf(TYPE_HISTORY))
    }

    // ---------------- 屏蔽词 ----------------

    fun shieldWords(): List<String> = synchronized(records) {
        records.values.filter { it.type == TYPE_SHIELD && !it.deleted }.map { it.key }
    }

    fun addShieldWord(word: String) = put(TYPE_SHIELD, word, null)

    fun removeShieldWord(word: String) = put(TYPE_SHIELD, word, null, deleted = true)

    // ---------------- 账号 cookie（key 为站点 id） ----------------

    fun accountCookie(siteId: String): String = synchronized(records) {
        val r = records[id(TYPE_ACCOUNT, siteId)]
        if (r == null || r.deleted) "" else (r.data as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
    }

    fun setAccountCookie(siteId: String, cookie: String) {
        if (cookie.isEmpty()) {
            put(TYPE_ACCOUNT, siteId, null, deleted = true)
        } else {
            put(TYPE_ACCOUNT, siteId, kotlinx.serialization.json.JsonPrimitive(cookie))
        }
    }

    // ---------------- 同步 ----------------

    fun allRecords(): List<SyncRecord> = synchronized(records) { records.values.toList() }

    /** 记录摘要：key+updatedAt 的哈希，两端一致说明无需同步 */
    fun digest(): String = synchronized(records) {
        val text = records.values.sortedBy { "${it.type}:${it.key}" }
            .joinToString("\n") { "${it.type}:${it.key}:${it.updatedAt}:${it.deleted}" }
        com.xycz.simplelive.core.common.md5Hex(text)
    }

    /** 合并对端记录（updatedAt 新者胜），返回是否有变化 */
    fun merge(incoming: List<SyncRecord>): Boolean {
        val changed = mutableSetOf<String>()
        synchronized(records) {
            for (r in incoming) {
                val local = records[id(r.type, r.key)]
                if (local == null || r.updatedAt > local.updatedAt) {
                    records[id(r.type, r.key)] = r
                    changed += r.type
                }
            }
        }
        if (changed.isNotEmpty()) {
            save()
            notifyChanged(changed)
        }
        return changed.isNotEmpty()
    }

    /** 批量导入（迁移、旧版同步接口），不覆盖本地更新的记录 */
    fun importRecords(list: List<SyncRecord>) = merge(list)

    fun followRecord(user: FollowUser, time: Long = user.addTime) =
        SyncRecord(TYPE_FOLLOW, user.id, JsonCodec.encodeToJsonElement(user), time)

    fun historyRecord(item: HistoryItem) =
        SyncRecord(TYPE_HISTORY, item.id, JsonCodec.encodeToJsonElement(item), item.updateTime)

    fun shieldRecord(word: String, time: Long) = SyncRecord(TYPE_SHIELD, word, null, time)

    fun accountRecord(siteId: String, cookie: String, time: Long) =
        SyncRecord(TYPE_ACCOUNT, siteId, kotlinx.serialization.json.JsonPrimitive(cookie), time)

    fun decodeFollows(element: JsonElement): List<FollowUser> =
        JsonCodec.decodeFromJsonElement(ListSerializer(FollowUser.serializer()), element)
}
