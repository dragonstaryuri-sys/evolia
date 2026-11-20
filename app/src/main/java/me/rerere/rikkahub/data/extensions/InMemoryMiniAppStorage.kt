package me.rerere.rikkahub.data.extensions

import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * 工作区预览专用易失存储（2026-11-20 补充需求四.3 第三层预览）。
 *
 * 与 [MiniAppStorageService] 共用 [MiniAppStorageRules] 的集合声明检查与
 * Schema 校验——预览阶段同样能暴露 Schema 违规；数据只存内存 Map，随预览
 * 会话销毁丢弃，不写 Room（不污染已安装拓展数据，未安装拓展也不留残留）。
 * 生命周期 = 预览页 Bridge 会话（onDispose invalidate 后不再被引用）。
 */
class InMemoryMiniAppStorage(
    private val collections: List<ResolvedCollection>,
) : MiniAppStorage {

    companion object {
        const val DEFAULT_PAGE_SIZE = MiniAppStorageService.DEFAULT_PAGE_SIZE
        const val MAX_PAGE_SIZE = MiniAppStorageService.MAX_PAGE_SIZE
    }

    /** key = (collection, record_id) */
    private val records = LinkedHashMap<String, JsonObject>()
    private val lock = Any()

    private fun requireCollection(collection: String): ResolvedCollection =
        MiniAppStorageRules.requireCollection(collections, collection)

    private fun validateData(collection: ResolvedCollection?, data: JsonObject) =
        MiniAppStorageRules.validateData(collection, data)

    private fun key(collection: String, recordId: String) = "$collection/$recordId"

    override suspend fun get(appId: String, collection: String, recordId: String): JsonObject? {
        requireCollection(collection)
        return synchronized(lock) { records[key(collection, recordId)] }
    }

    override suspend fun list(appId: String, collection: String, page: Int, pageSize: Int): MiniAppStorage.PageResult {
        requireCollection(collection)
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        return synchronized(lock) {
            // 插入序倒序（与 Room 实现的 created_at DESC 语义一致）
            val items = records.entries
                .filter { it.key.startsWith("$collection/") }
                .toList()
                .asReversed()
            val total = items.size
            val from = (safePage - 1) * safeSize
            val pageItems = items.drop(from).take(safeSize).map { it.value }
            MiniAppStorage.PageResult(
                items = pageItems,
                total = total,
                page = safePage,
                pageSize = safeSize,
            )
        }
    }

    override suspend fun insert(appId: String, collection: String, data: JsonObject): String {
        val declared = requireCollection(collection)
        validateData(declared, data)
        val recordId = Uuid.random().toString()
        synchronized(lock) { records[key(collection, recordId)] = data }
        return recordId
    }

    override suspend fun update(appId: String, collection: String, recordId: String, data: JsonObject): Boolean {
        val declared = requireCollection(collection)
        validateData(declared, data)
        synchronized(lock) {
            val k = key(collection, recordId)
            if (!records.containsKey(k)) return false
            records[k] = data
            return true
        }
    }

    override suspend fun delete(appId: String, collection: String, recordId: String): Boolean {
        requireCollection(collection)
        return synchronized(lock) { records.remove(key(collection, recordId)) != null }
    }
}
