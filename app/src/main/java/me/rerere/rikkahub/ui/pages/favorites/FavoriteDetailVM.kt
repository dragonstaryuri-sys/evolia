package me.rerere.rikkahub.ui.pages.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.core.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.core.data.repository.FavoriteRepository

class FavoriteDetailVM(
    private val id: Long,
    private val favoriteRepository: FavoriteRepository
) : ViewModel() {

    private val _favorite = MutableStateFlow<FavoriteEntity?>(null)
    val favorite: StateFlow<FavoriteEntity?> = _favorite

    init {
        viewModelScope.launch {
            _favorite.value = favoriteRepository.getFavoriteById(id)
        }
    }
}
