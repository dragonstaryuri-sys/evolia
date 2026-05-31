package me.rerere.rikkahub.core.data.repository

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.core.data.db.dao.UserDeviceStateDAO
import me.rerere.rikkahub.core.data.db.entity.UserDeviceStateEntity

class UserDeviceStateRepository(private val userDeviceStateDAO: UserDeviceStateDAO) {
    fun getUserDeviceState(): Flow<UserDeviceStateEntity?> = userDeviceStateDAO.getUserDeviceState()

    suspend fun updateDeviceState(state: UserDeviceStateEntity) {
        userDeviceStateDAO.insertOrUpdate(state)
    }
}
