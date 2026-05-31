package com.theycallmeboxy.caulker.ui.screens.platforms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.db.entity.PlatformEntity
import com.theycallmeboxy.caulker.data.repository.PlatformRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// Lists platforms from the local cache only. Library sync is triggered explicitly
// from the dashboard's "Sync database now" — this screen no longer touches the
// network, so there's no loading state to surface.
@HiltViewModel
class PlatformsViewModel @Inject constructor(
    repository: PlatformRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _hideEmpty = MutableStateFlow(true)
    val hideEmpty = _hideEmpty.asStateFlow()

    val platforms: StateFlow<List<PlatformEntity>> = combine(
        repository.observePlatforms(),
        _searchQuery,
        _hideEmpty
    ) { all, query, hideEmpty ->
        all.filter { p ->
            (!hideEmpty || p.romCount > 0) &&
            (query.isBlank() || p.name.contains(query, ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(q: String) { _searchQuery.value = q }
    fun toggleHideEmpty() { _hideEmpty.value = !_hideEmpty.value }
}
