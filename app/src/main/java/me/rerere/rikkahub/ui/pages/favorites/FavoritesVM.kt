package me.rerere.rikkahub.ui.pages.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.core.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.core.data.repository.FavoriteRepository

class FavoritesVM(
    private val favoriteRepository: FavoriteRepository
) : ViewModel() {

    // 收藏列表流，支持分页
    val favorites: Flow<PagingData<FavoriteEntity>> = favoriteRepository.getFavoritesPager()
        .cachedIn(viewModelScope)

    // 删除收藏
    fun deleteFavorite(id: Long) {
        viewModelScope.launch {
            favoriteRepository.removeFavorite(id)
        }
    }
}
