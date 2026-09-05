package com.jellycine.app.ui.screens.dashboard.media

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.jellycine.app.ui.screens.dashboard.favorites.FAVORITES_VIEW_ALL_PARENT_ID
import com.jellycine.data.repository.AwardsRepositoryProvider
import com.jellycine.data.repository.MediaRepository
import com.jellycine.data.repository.MediaRepositoryProvider
import com.jellycine.data.repository.SeerrRepository
import com.jellycine.data.model.AwardMode
import com.jellycine.data.model.BaseItemDto
import com.jellycine.data.model.QueryResult
import com.jellycine.data.model.SeerrItemIds
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltViewModel
class ViewAllViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private lateinit var mediaRepository: MediaRepository
    private val seerrRepository = SeerrRepository(context)
    private val awardsRepository = AwardsRepositoryProvider.getInstance(context)
    private val authRepository = com.jellycine.data.repository.AuthRepositoryProvider.getInstance(context)
    private val preferences = com.jellycine.shared.preferences.Preferences(context)

    private val _uiState = MutableStateFlow(
        ViewAllUiState(
            folderLayoutMode = if (preferences.getFolderViewLayoutMode() == com.jellycine.shared.preferences.Preferences.FOLDER_LAYOUT_MODE_LIST) {
                FolderLayoutMode.LIST
            } else {
                FolderLayoutMode.GRID
            },
            directPlayVideos = preferences.isFolderViewDirectPlayEnabled()
        )
    )
    val uiState: StateFlow<ViewAllUiState> = _uiState.asStateFlow()

    private val _items = MutableStateFlow<List<BaseItemDto>>(emptyList())
    val items: StateFlow<List<BaseItemDto>> = _items.asStateFlow()

    private var currentPage = 0
    private val pageSize = 50
    private var totalItems = 0
    private var hasMorePages = true
    private var currentRequestKey: String? = null

    fun ensureItemsLoaded(
        contentType: ContentType,
        parentId: String? = null,
        genreId: String? = null,
        title: String = ""
    ) {
        val isFolderMode = _uiState.value.browseMode == BrowseMode.FOLDERS
        if (isFolderMode && _uiState.value.folderStack.isEmpty() && !parentId.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                folderStack = listOf(FolderCrumb(id = parentId, name = title))
            )
        }
        val effectiveParentId = if (isFolderMode) {
            _uiState.value.folderStack.lastOrNull()?.id ?: parentId
        } else {
            parentId
        }
        val requestKey = "$contentType|${effectiveParentId.orEmpty()}|${_uiState.value.browseMode}|${genreId.orEmpty()}"

        if (currentRequestKey == requestKey && _items.value.isNotEmpty()) return
        loadItems(contentType, parentId, refresh = true, genreId = genreId)
    }

    fun loadItems(
        contentType: ContentType,
        parentId: String? = null,
        refresh: Boolean = false,
        genreId: String? = null
    ) {
        val isFolderMode = _uiState.value.browseMode == BrowseMode.FOLDERS
        val effectiveParentId = if (isFolderMode) {
            _uiState.value.folderStack.lastOrNull()?.id ?: parentId
        } else {
            parentId
        }
        currentRequestKey = "$contentType|${effectiveParentId.orEmpty()}|${_uiState.value.browseMode}|${_uiState.value.sortBy}|${_uiState.value.sortOrder}|${genreId.orEmpty()}"

        if (refresh) {
            currentPage = 0
            hasMorePages = true
        }

        if (!hasMorePages && !refresh) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                if (!::mediaRepository.isInitialized) {
                    mediaRepository = MediaRepositoryProvider.getInstance(context)
                }

                withContext(Dispatchers.IO) {
                    val selectedGenres = _uiState.value.selectedGenres
                        .toList()
                        .sorted()
                        .joinToString("|")
                        .ifBlank { null }
                    val selectedGenreIds = genreId?.takeIf { it.isNotBlank() }
                    val isWatchedRequest = parentId == WATCHED_VIEW_ALL_PARENT_ID
                    val isFavoritesRequest = parentId == FAVORITES_VIEW_ALL_PARENT_ID
                    val folderLimit = 500
                    val result = if (isFolderMode && !effectiveParentId.isNullOrBlank()) {
                        val folderSortBy = _uiState.value.sortBy.removePrefix("IsFolder,").trim()
                        mediaRepository.getUserItems(
                            parentId = effectiveParentId,
                            includeItemTypes = "Folder,Movie,Series,Episode,Video,BoxSet",
                            sortBy = folderSortBy,
                            sortOrder = _uiState.value.sortOrder,
                            limit = folderLimit,
                            startIndex = currentPage * folderLimit,
                            recursive = false,
                            fields = "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,Genres,CommunityRating,CriticRating,ProductionYear,Overview,UserData,MediaSources,Path,RunTimeTicks,DateCreated,DateLastMediaAdded,PremiereDate"
                        )
                    } else when (contentType) {
                        ContentType.SEERR_STUDIO -> seerrRepository.getStudios(
                            scopeId = authRepository.getActiveSessionSnapshot().activeServerId.orEmpty(),
                            studioId = parentId.orEmpty(),
                            limit = pageSize,
                            startIndex = currentPage * pageSize
                        )
                        ContentType.SEERR_NETWORK -> seerrRepository.getNetworks(
                            scopeId = authRepository.getActiveSessionSnapshot().activeServerId.orEmpty(),
                            networkId = parentId.orEmpty(),
                            limit = pageSize,
                            startIndex = currentPage * pageSize
                        )
                        ContentType.MOVIES -> if (isWatchedRequest) {
                            mediaRepository.loadWatchedItems("Movie")
                                .map { QueryResult(items = it, totalRecordCount = it.size, startIndex = 0) }
                        } else if (isFavoritesRequest) {
                            mediaRepository.getFavoriteItems(includeItemTypes = "Movie")
                        } else mediaRepository.getUserItems(
                            parentId = parentId,
                            genres = selectedGenres,
                            includeItemTypes = "Movie,BoxSet",
                            sortBy = _uiState.value.sortBy,
                            sortOrder = _uiState.value.sortOrder,
                            limit = pageSize,
                            startIndex = currentPage * pageSize,
                            recursive = true,
                            fields = "ChildCount,RecursiveItemCount,EpisodeCount,Genres,CommunityRating,CriticRating,ProductionYear,Overview,UserData"
                        )
                        ContentType.SERIES -> if (isWatchedRequest) {
                            mediaRepository.loadWatchedItems("Episode")
                                .mapCatching { mediaRepository.loadSeriesForWatchedEpisodes(it).getOrThrow() }
                                .map { QueryResult(items = it, totalRecordCount = it.size, startIndex = 0) }
                        } else if (isFavoritesRequest) {
                            mediaRepository.getFavoriteItems(includeItemTypes = "Series")
                        } else mediaRepository.getUserItems(
                            parentId = parentId,
                            genres = selectedGenres,
                            includeItemTypes = "Series",
                            sortBy = _uiState.value.sortBy,
                            sortOrder = _uiState.value.sortOrder,
                            limit = pageSize,
                            startIndex = currentPage * pageSize,
                            recursive = true,
                            fields = "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,Genres,CommunityRating,CriticRating,ProductionYear,Overview,UserData"
                        )
                        ContentType.EPISODES -> if (isWatchedRequest) {
                            mediaRepository.loadWatchedItems("Episode")
                                .map { QueryResult(items = it, totalRecordCount = it.size, startIndex = 0) }
                        } else if (isFavoritesRequest) {
                            mediaRepository.getFavoriteItems(includeItemTypes = "Episode")
                        } else mediaRepository.getUserItems(
                            parentId = parentId,
                            genres = selectedGenres,
                            includeItemTypes = "Episode",
                            sortBy = _uiState.value.sortBy,
                            sortOrder = _uiState.value.sortOrder,
                            limit = pageSize,
                            startIndex = currentPage * pageSize,
                            recursive = true,
                            fields = "SeriesName,SeriesId,SeasonName,SeasonId,Overview,UserData"
                        )
                        ContentType.MOVIES_GENRE -> mediaRepository.getUserItems(
                            parentId = parentId,
                            genres = selectedGenres,
                            genreIds = selectedGenreIds,
                            includeItemTypes = "Movie",
                            sortBy = _uiState.value.sortBy,
                            sortOrder = _uiState.value.sortOrder,
                            limit = pageSize,
                            startIndex = currentPage * pageSize,
                            recursive = true,
                            fields = "ChildCount,RecursiveItemCount,EpisodeCount,Genres,CommunityRating,CriticRating,ProductionYear,Overview,UserData"
                        )
                        ContentType.TVSHOWS_GENRE -> mediaRepository.getUserItems(
                            parentId = parentId,
                            genres = selectedGenres,
                            genreIds = selectedGenreIds,
                            includeItemTypes = "Series",
                            sortBy = _uiState.value.sortBy,
                            sortOrder = _uiState.value.sortOrder,
                            limit = pageSize,
                            startIndex = currentPage * pageSize,
                            recursive = true,
                            fields = "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,Genres,CommunityRating,CriticRating,ProductionYear,Overview,UserData"
                        )
                        ContentType.ALL -> mediaRepository.getUserItems(
                            parentId = parentId,
                            genres = selectedGenres,
                            includeItemTypes = "Movie,Series",
                            sortBy = _uiState.value.sortBy,
                            sortOrder = _uiState.value.sortOrder,
                            limit = pageSize,
                            startIndex = currentPage * pageSize,
                            recursive = true,
                            fields = "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,Genres,CommunityRating,CriticRating,ProductionYear,Overview,UserData"
                        )
                        ContentType.AWARD -> loadAwardItems(parentId)
                    }

                    result.fold(
                        onSuccess = { queryResult ->
                            val newItems = (queryResult.items ?: emptyList()).let { fetchedItems ->
                                val selectedGenresSet = _uiState.value.selectedGenres
                                if (selectedGenresSet.size > 1) {
                                    fetchedItems.filter { item ->
                                        val itemGenres = item.genres.orEmpty().toSet()
                                        selectedGenresSet.all { genre -> itemGenres.contains(genre) }
                                    }
                                } else {
                                    fetchedItems
                                }
                            }
                            totalItems = queryResult.totalRecordCount ?: 0
                            val currentLimit = if (isFolderMode) folderLimit else pageSize
                            hasMorePages = !isWatchedRequest &&
                                contentType != ContentType.AWARD &&
                                (currentPage + 1) * currentLimit < totalItems

                            withContext(Dispatchers.Main) {
                                val combinedItems = if (refresh) {
                                    newItems
                                } else {
                                    _items.value + newItems
                                }
                                _items.value = if (isFolderMode) {
                                    sortFolderItems(combinedItems, _uiState.value.sortBy, _uiState.value.sortOrder)
                                } else {
                                    combinedItems
                                }
                                currentPage++
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    totalItems = totalItems,
                                    hasMorePages = hasMorePages
                                )
                            }
                        },
                        onFailure = { exception ->
                            withContext(Dispatchers.Main) {
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    error = exception.message ?: "Unknown error occurred"
                                )
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Unknown error occurred"
                )
            }
        }
    }

    private suspend fun loadAwardItems(parentId: String?): Result<QueryResult<BaseItemDto>> {
        val parts = parentId?.split("_").orEmpty()
        val qid = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: return Result.success(QueryResult(items = emptyList(), totalRecordCount = 0, startIndex = 0))
        val mode = if (parts.getOrNull(1) == AwardMode.NOMINEES.name) AwardMode.NOMINEES else AwardMode.WINNERS
        val refs = awardsRepository.getCategoryRefs(listOf(qid), mode)[qid].orEmpty()
        val items = awardsRepository.hydrate(refs, limit = refs.size).map { title ->
            BaseItemDto(
                id = title.jellyfinMediaId?.takeIf { it.isNotBlank() }
                    ?: SeerrItemIds.detailId(title.tmdbId, title.mediaType),
                name = title.title,
                type = if (title.mediaType == "tv") "Series" else "Movie",
                productionYear = title.productionYear,
                providerIds = mapOf("tmdb" to title.tmdbId),
                imageUrl = title.posterUrl
            )
        }
        return Result.success(QueryResult(items = items, totalRecordCount = items.size, startIndex = 0))
    }

    fun loadMoreItems(contentType: ContentType, parentId: String? = null, genreId: String? = null) {
        loadItems(contentType, parentId, refresh = false, genreId = genreId)
    }

    fun setSort(sortBy: String, sortOrder: String, contentType: ContentType, parentId: String? = null, genreId: String? = null) {
        _uiState.value = _uiState.value.copy(sortBy = sortBy, sortOrder = sortOrder)
        loadItems(contentType, parentId, refresh = true, genreId = genreId)
    }

    fun toggleGenreFilter(genre: String, contentType: ContentType, parentId: String? = null, genreId: String? = null) {
        val currentGenres = LinkedHashSet(_uiState.value.selectedGenres)
        if (currentGenres.contains(genre)) {
            currentGenres.remove(genre)
        } else {
            currentGenres.add(genre)
        }
        _uiState.value = _uiState.value.copy(selectedGenres = currentGenres)
        loadItems(contentType, parentId, refresh = true, genreId = genreId)
    }

    fun clearFilters(contentType: ContentType, parentId: String? = null, genreId: String? = null) {
        _uiState.value = _uiState.value.copy(
            selectedGenres = emptySet()
        )
        loadItems(contentType, parentId, refresh = true, genreId = genreId)
    }

    fun setBrowseMode(
        mode: BrowseMode,
        contentType: ContentType,
        rootParentId: String?,
        rootTitle: String,
        genreId: String? = null
    ) {
        if (_uiState.value.browseMode == mode) return
        val initialStack = if (mode == BrowseMode.FOLDERS && !rootParentId.isNullOrBlank()) {
            listOf(FolderCrumb(id = rootParentId, name = rootTitle))
        } else {
            emptyList()
        }
        _uiState.value = _uiState.value.copy(
            browseMode = mode,
            folderStack = initialStack,
            selectedGenres = emptySet()
        )
        loadItems(contentType, rootParentId, refresh = true, genreId = genreId)
    }

    fun openFolder(
        folderId: String,
        folderName: String,
        contentType: ContentType,
        rootParentId: String?,
        genreId: String? = null,
        currentScrollIndex: Int = 0,
        currentScrollOffset: Int = 0
    ) {
        val currentStack = _uiState.value.folderStack
        val updatedStack = if (currentStack.isNotEmpty()) {
            val lastCrumb = currentStack.last()
            currentStack.dropLast(1) + lastCrumb.copy(
                scrollIndex = currentScrollIndex,
                scrollOffset = currentScrollOffset
            )
        } else {
            currentStack
        }
        _uiState.value = _uiState.value.copy(
            browseMode = BrowseMode.FOLDERS,
            folderStack = updatedStack + FolderCrumb(id = folderId, name = folderName, scrollIndex = 0, scrollOffset = 0)
        )
        loadItems(contentType, rootParentId, refresh = true, genreId = genreId)
    }

    fun setFolderLayoutMode(mode: FolderLayoutMode) {
        if (_uiState.value.folderLayoutMode == mode) return
        preferences.setFolderViewLayoutMode(
            if (mode == FolderLayoutMode.LIST) {
                com.jellycine.shared.preferences.Preferences.FOLDER_LAYOUT_MODE_LIST
            } else {
                com.jellycine.shared.preferences.Preferences.FOLDER_LAYOUT_MODE_GRID
            }
        )
        _uiState.value = _uiState.value.copy(folderLayoutMode = mode)
    }

    fun setDirectPlayVideos(enabled: Boolean) {
        preferences.setFolderViewDirectPlayEnabled(enabled)
        _uiState.value = _uiState.value.copy(directPlayVideos = enabled)
    }

    fun navigateBackFolder(
        contentType: ContentType,
        rootParentId: String?,
        genreId: String? = null
    ): Boolean {
        val currentStack = _uiState.value.folderStack
        if (currentStack.size <= 1) return false
        _uiState.value = _uiState.value.copy(
            folderStack = currentStack.dropLast(1)
        )
        loadItems(contentType, rootParentId, refresh = true, genreId = genreId)
        return true
    }

    fun navigateToFolderIndex(
        index: Int,
        contentType: ContentType,
        rootParentId: String?,
        genreId: String? = null
    ) {
        val currentStack = _uiState.value.folderStack
        if (index < 0 || index >= currentStack.size - 1) return
        _uiState.value = _uiState.value.copy(
            folderStack = currentStack.take(index + 1)
        )
        loadItems(contentType, rootParentId, refresh = true, genreId = genreId)
    }
}

enum class BrowseMode {
    ITEMS,
    FOLDERS
}

enum class FolderLayoutMode {
    GRID,
    LIST
}

data class FolderCrumb(
    val id: String,
    val name: String,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0
)

data class ViewAllUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val sortBy: String = "DateCreated",
    val sortOrder: String = "Descending",
    val selectedGenres: Set<String> = emptySet(),
    val totalItems: Int = 0,
    val hasMorePages: Boolean = true,
    val browseMode: BrowseMode = BrowseMode.ITEMS,
    val folderStack: List<FolderCrumb> = emptyList(),
    val folderLayoutMode: FolderLayoutMode = FolderLayoutMode.GRID,
    val directPlayVideos: Boolean = true
)

enum class ContentType {
    ALL, MOVIES, SERIES, EPISODES, MOVIES_GENRE, TVSHOWS_GENRE, SEERR_STUDIO, SEERR_NETWORK, AWARD
}

fun ContentType.isSeerrCatalog(): Boolean =
    this == ContentType.SEERR_STUDIO || this == ContentType.SEERR_NETWORK

fun ContentType.isLibraryCatalog(): Boolean =
    this == ContentType.ALL ||
        this == ContentType.MOVIES ||
        this == ContentType.SERIES ||
        this == ContentType.EPISODES

fun ContentType.isGenreCatalog(): Boolean =
    this == ContentType.MOVIES_GENRE ||
        this == ContentType.TVSHOWS_GENRE