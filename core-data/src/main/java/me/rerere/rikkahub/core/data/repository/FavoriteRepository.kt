package me.rerere.rikkahub.core.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import me.rerere.rikkahub.core.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.core.data.db.entity.FavoriteEntity

class FavoriteRepository(private val favoriteDao: FavoriteDAO) {

    fun getFavoritesPager() = Pager(
        config = PagingConfig(pageSize = 20),
        pagingSourceFactory = { favoriteDao.getAllPaged() }
    ).flow

    suspend fun addFavorite(favorite: FavoriteEntity) = favoriteDao.insert(favorite)

    suspend fun removeFavorite(id: Long) = favoriteDao.deleteById(id)

    suspend fun getFavoriteById(id: Long) = favoriteDao.getById(id)
}
