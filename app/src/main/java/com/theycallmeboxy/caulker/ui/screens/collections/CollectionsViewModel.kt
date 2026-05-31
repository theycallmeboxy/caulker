package com.theycallmeboxy.caulker.ui.screens.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.db.entity.CollectionEntity
import com.theycallmeboxy.caulker.data.repository.CollectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// Lists collections from the local cache. Sync runs as part of the library
// sweep from the dashboard's "Sync database now" — this screen is cache-only.
@HiltViewModel
class CollectionsViewModel @Inject constructor(
    repository: CollectionRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    val collections: StateFlow<List<CollectionEntity>> = combine(
        repository.observeAll(),
        _searchQuery
    ) { all, query ->
        all.filter { c -> query.isBlank() || c.name.contains(query, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(q: String) { _searchQuery.value = q }
}
