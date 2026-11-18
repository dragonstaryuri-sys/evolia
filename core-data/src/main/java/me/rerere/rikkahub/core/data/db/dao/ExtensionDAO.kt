package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.ExtensionEntity
import me.rerere.rikkahub.core.data.db.entity.ExtensionInstallOperationEntity

@Dao
interface ExtensionDAO {

    // ---------- extensions ----------

    @Query("SELECT * FROM extensions")
    fun observeAll(): Flow<List<ExtensionEntity>>

    @Query("SELECT * FROM extensions")
    suspend fun getAll(): List<ExtensionEntity>

    @Query("SELECT * FROM extensions WHERE app_id = :appId")
    suspend fun getByAppId(appId: String): ExtensionEntity?

    @Upsert
    suspend fun upsert(extension: ExtensionEntity)

    @Query("UPDATE extensions SET is_enabled = :enabled, updated_at = :updatedAt WHERE app_id = :appId")
    suspend fun setEnabled(appId: String, enabled: Boolean, updatedAt: Long)

    @Query("DELETE FROM extensions WHERE app_id = :appId")
    suspend fun deleteByAppId(appId: String)

    // ---------- extension_install_operations ----------

    @Query("SELECT * FROM extension_install_operations WHERE operation_state != 'COMPLETED'")
    suspend fun getPendingOperations(): List<ExtensionInstallOperationEntity>

    @Query("SELECT * FROM extension_install_operations WHERE operation_id = :operationId")
    suspend fun getOperation(operationId: String): ExtensionInstallOperationEntity?

    @Upsert
    suspend fun upsertOperation(operation: ExtensionInstallOperationEntity)

    @Query("UPDATE extension_install_operations SET operation_state = :state, updated_at = :updatedAt WHERE operation_id = :operationId")
    suspend fun updateOperationState(operationId: String, state: String, updatedAt: Long)

    @Query("DELETE FROM extension_install_operations WHERE operation_id = :operationId")
    suspend fun deleteOperation(operationId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<ExtensionEntity>)
}
