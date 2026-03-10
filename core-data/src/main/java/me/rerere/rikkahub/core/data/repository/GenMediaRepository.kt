package me.rerere.rikkahub.core.data.db.repository

import androidx.paging.PagingSource
import me.rerere.rikkahub.core.data.db.dao.GenMediaDAO
import me.rerere.rikkahub.core.data.db.entity.GenMediaEntity

class GenMediaRepository(private val dao: GenMediaDAO) {
    fun getAllMedia(): PagingSource<Int, GenMediaEntity> = dao.getAll()

    suspend fun insertMedia(media: GenMediaEntity) = dao.insert(media)

    suspend fun deleteMedia(id: Int) = dao.delete(id)
}
