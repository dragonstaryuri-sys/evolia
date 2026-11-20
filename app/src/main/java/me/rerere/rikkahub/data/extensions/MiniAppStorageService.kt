package me.rerere.rikkahub.data.extensions

import android.util.Log
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.core.data.db.entity.MiniAppDataEntity
import me.rerere.rikkahub.core.data.repository.ExtensionRepository
import me.rerere.rikkahub.common.JsonInstant
import kotlin.uuid.Uuid

/**
 * Mini App 存储后端接口（2026-11-20 拆出以支持工作区预览）：
 * - [MiniAppStorageService]：Room 实现，安装运行态；
 * - [InMemoryMiniAppStorage]：易失内存实现，工作区预览态（数据不落库）。
 *
 * 两个实现共用 [MiniAppStorageRules] 的集合声明检查与 Schema 校验，
 * 保证预览阶段也能暴露 Schema 违规。
 */
interface MiniAppStorage {
    class StorageException(val code: String, message: String) : Exception(message)

    data class PageResult(
        val items: List<JsonObject>,
        val total: Int,
        val page: Int,
        val pageSize: Int,
    )

    suspend fun get(appId: String, collection: String, recordId: String): JsonObject?

    suspend fun list(appId: String, collection: String, page: Int, pageSize: Int): PageResult

    /** 插入记录，返回生成的 record_id */
    suspend fun insert(appId: String, collection: String, data: JsonObject): String

    /** 更新记录（全量覆盖），返回是否存在 */
    suspend fun update(appId: String, collection: String, recordId: String, data: JsonObject): Boolean

    suspend fun delete(appId: String, collection: String, recordId: String): Boolean
}

/**
 * 集合声明检查与数据 Schema 校验（安装运行 / 工作区预览共用，纯逻辑）。
 */
object MiniAppStorageRules {
    const val MAX_DATA_BYTES = 256 * 1024 // 单条记录上限

    /**
     * 集合必须在 Manifest 中声明（app_id 由运行环境固定注入，前端不可指定，
     * 禁止通过传入其他 app_id 绕过数据隔离）。
     */
    fun requireCollection(collections: List<ResolvedCollection>, collection: String): ResolvedCollection {
        if (!Regex(ExtensionConstants.COLLECTION_NAME_REGEX).matches(collection)) {
            throw MiniAppStorage.StorageException(ExtensionToolError.INVALID_ARGUMENTS, "集合名不合法：$collection")
        }
        return collections.firstOrNull { it.name == collection }
            ?: throw MiniAppStorage.StorageException(
                ExtensionToolError.INVALID_ARGUMENTS,
                "集合「$collection」未在 Manifest 中声明，禁止访问"
            )
    }

    fun validateData(collection: ResolvedCollection?, data: JsonObject) {
        if (data.toString().encodeToByteArray().size > MAX_DATA_BYTES) {
            throw MiniAppStorage.StorageException(ExtensionToolError.INVALID_ARGUMENTS, "单条记录超出 256KB 上限")
        }
        val schema = collection?.schema ?: return
        val errors = JsonSchemaValidator.validate(data, schema)
        if (errors.isNotEmpty()) {
            throw MiniAppStorage.StorageException(
                ExtensionToolError.INVALID_ARGUMENTS,
                "数据不符合 Schema：\n" + errors.joinToString("\n") { "· $it" }
            )
        }
    }
}

/**
 * Mini App 私有数据服务（详设第十四章）——Room 实现的 [MiniAppStorage]。
 *
 * 所有操作自动绑定当前 app_id；扩展不得直接执行 SQL。数据写入统一经
 * JsonSchemaValidator 按 Manifest 声明的集合 Schema 校验。
 */
class MiniAppStorageService(
    private val repository: ExtensionRepository,
    private val registry: ExtensionRegistry,
) : MiniAppStorage {
    companion object {
        const val TAG = "MiniAppStorage"
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
    }

    private fun requireCollection(appId: String, collection: String): ResolvedCollection {
        val installed = registry.getInstalled(appId)
            ?: throw MiniAppStorage.StorageException(ExtensionToolError.EXTENSION_NOT_FOUND, "扩展不存在或已卸载")
        return MiniAppStorageRules.requireCollection(installed.collections, collection)
    }

    private fun validateData(collection: ResolvedCollection?, data: JsonObject) {
        MiniAppStorageRules.validateData(collection, data)
    }

    override suspend fun get(appId: String, collection: String, recordId: String): JsonObject? {
        requireCollection(appId, collection)
        val entity = repository.getMiniAppData(appId, collection, recordId) ?: return null
        return JsonInstant.parseToJsonElement(entity.data) as? JsonObject
    }

    override suspend fun list(appId: String, collection: String, page: Int, pageSize: Int): MiniAppStorage.PageResult {
        requireCollection(appId, collection)
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val total = repository.countMiniAppData(appId, collection)
        val entities = repository.listMiniAppData(
            appId, collection, safeSize, (safePage - 1) * safeSize
        )
        return MiniAppStorage.PageResult(
            items = entities.mapNotNull {
                runCatching { JsonInstant.parseToJsonElement(it.data) as? JsonObject }.getOrNull()
            },
            total = total,
            page = safePage,
            pageSize = safeSize,
        )
    }

    override suspend fun insert(appId: String, collection: String, data: JsonObject): String {
        val declared = requireCollection(appId, collection)
        validateData(declared, data)
        val now = System.currentTimeMillis()
        val recordId = Uuid.random().toString()
        val inserted = repository.insertMiniAppData(
            MiniAppDataEntity(
                appId = appId,
                collection = collection,
                recordId = recordId,
                data = data.toString(),
                createdAt = now,
                updatedAt = now,
            )
        )
        if (inserted == -1L) {
            throw MiniAppStorage.StorageException(ExtensionToolError.SDK_CALL_FAILED, "记录写入冲突")
        }
        return recordId
    }

    override suspend fun update(appId: String, collection: String, recordId: String, data: JsonObject): Boolean {
        val declared = requireCollection(appId, collection)
        validateData(declared, data)
        val existing = repository.getMiniAppData(appId, collection, recordId) ?: return false
        repository.updateMiniAppData(
            existing.copy(data = data.toString(), updatedAt = System.currentTimeMillis())
        )
        return true
    }

    override suspend fun delete(appId: String, collection: String, recordId: String): Boolean {
        requireCollection(appId, collection)
        val existing = repository.getMiniAppData(appId, collection, recordId) ?: return false
        repository.deleteMiniAppData(appId, collection, recordId)
        return true
    }

    /** 卸载删除数据专用（用户显式选择后） */
    suspend fun deleteAllByApp(appId: String) {
        repository.deleteMiniAppDataByAppId(appId)
        Log.i(TAG, "Mini App 数据已清理：$appId")
    }
}
