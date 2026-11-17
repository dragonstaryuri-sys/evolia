package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.MiniAppDataEntity
import me.rerere.rikkahub.core.data.db.entity.MiniAppVersionEntity

/**
 * Mini App 私有数据与版本 DAO（详设 6.5 / 6.6）。
 * 全部查询绑定 app_id，保证数据隔离；扩展不得直接执行 SQL。
 */
@Dao
interface MiniAppDataDAO {

    @Query("SELECT * FROM mini_app_data WHERE app_id = :appId AND collection = :collection AND record_id = :recordId")
    suspend fun get(appId: String, collection: String, recordId: String): MiniAppDataEntity?

    /** 分页列表，按创建时间倒序 */
    @Query("SELECT * FROM mini_app_data WHERE app_id = :appId AND collection = :collection ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun list(appId: String, collection: String, limit: Int, offset: Int): List<MiniAppDataEntity>

    @Query("SELECT COUNT(*) FROM mini_app_data WHERE app_id = :appId AND collection = :collection")
    suspend fun count(appId: String, collection: String): Int

    @Query("SELECT * FROM mini_app_data WHERE app_id = :appId AND collection = :collection")
    fun observeCollection(appId: String, collection: String): Flow<List<MiniAppDataEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MiniAppDataEntity): Long

    @Update
    suspend fun update(entity: MiniAppDataEntity)

    @Query("DELETE FROM mini_app_data WHERE app_id = :appId AND collection = :collection AND record_id = :recordId")
    suspend fun delete(appId: String, collection: String, recordId: String)

    /** 卸载且用户明确选择删除数据时使用；仅保留数据卸载时禁止调用 */
    @Query("DELETE FROM mini_app_data WHERE app_id = :appId")
    suspend fun deleteByAppId(appId: String)

    @Query("DELETE FROM mini_app_data WHERE app_id = :appId AND collection = :collection")
    suspend fun deleteCollection(appId: String, collection: String)
}

@Dao
interface MiniAppVersionDAO {

    @Query("SELECT * FROM mini_app_versions WHERE app_id = :appId ORDER BY installed_at DESC, id DESC LIMIT 1")
    suspend fun getLatest(appId: String): MiniAppVersionEntity?

    @Insert
    suspend fun insert(version: MiniAppVersionEntity)

    @Query("DELETE FROM mini_app_versions WHERE app_id = :appId")
    suspend fun deleteByAppId(appId: String)
}
