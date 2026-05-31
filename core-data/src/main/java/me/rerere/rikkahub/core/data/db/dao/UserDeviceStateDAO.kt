package me.rerere.rikkahub.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.entity.UserDeviceStateEntity

@Dao
interface UserDeviceStateDAO {
    @Query("SELECT * FROM user_device_state WHERE id = 0")
    fun getUserDeviceState(): Flow<UserDeviceStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: UserDeviceStateEntity)
}
