package me.grey.picquery.ui.search

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.grey.picquery.PicQueryApplication
import me.grey.picquery.R
import me.grey.picquery.common.showToast
import me.grey.picquery.data.data_source.PhotoRepository
import me.grey.picquery.data.model.Photo
import me.grey.picquery.domain.ImageSearcher
import timber.log.Timber

enum class SearchState {
    NO_INDEX, // 没有索引
    LOADING, // 初始化加载模型中
    READY,  // 准备好搜索
    SEARCHING,  // 正在搜索
    FINISHED,  // 搜索已完成
}

class SearchViewModel(
    private val imageSearcher: ImageSearcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val repo: PhotoRepository
) : ViewModel() {
    companion object {
        private const val TAG = "SearchResultViewModel"
        // How many extra results to fetch on each "Load More" press
        private const val LOAD_MORE_INCREMENT = 30
        // Hard cap to avoid runaway queries
        private const val MAX_EXTENDED_TOP_K = 300
    }

    // Full result list (all results fetched from DB for current query)
    private val _allResultList = MutableStateFlow<List<Photo>>(emptyList())
    private val _allResultMap = MutableStateFlow<Map<Long, Double>>(mutableMapOf())

    // How many results are currently displayed
    private val _displayedCount = MutableStateFlow(0)

    // The effective topK used for the last successful fetch (to detect when we may have more)
    private val _lastFetchedTopK = MutableStateFlow(0)

    // Derived: only show up to _displayedCount items
    val resultList: StateFlow<List<Photo>> = combine(_allResultList, _displayedCount) { list, count ->
        list.take(count)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val resultMap: StateFlow<Map<Long, Double>> = _allResultMap.asStateFlow()

    // Show "Load More" when displayed count < total fetched, OR when fetched == topK (may have more in DB)
    val canLoadMore: StateFlow<Boolean> = combine(
        _allResultList, _displayedCount, _lastFetchedTopK
    ) { list, displayed, lastTopK ->
        list.isNotEmpty() && (displayed < list.size || (lastTopK > 0 && list.size >= lastTopK))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _searchState = MutableStateFlow(SearchState.LOADING)
    val searchState = _searchState.asStateFlow()

    private val _searchText = MutableStateFlow<String>("")
    val searchText: StateFlow<String> = _searchText.map { it }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ""
    )

    // Remember last search input for "load more" re-fetch
    private var lastSearchText: String? = null
    private var lastSearchUri: Uri? = null

    private val context: Context
        get() {
            return PicQueryApplication.context
        }

    init {
        Timber.tag(TAG).d("init!!! SearchViewModel")
    }

    fun onQueryChange(query: String) {
        if (query == _searchText.value) return
        Timber.tag(TAG).d("onQueryChange: $query")
        _searchText.value = query
        _searchState.value = SearchState.READY
    }

    fun startSearch(text: String) {
        if (text.trim().isEmpty()) {
            showToast(context.getString(R.string.empty_search_content_toast))
            Timber.tag(TAG).w("搜索字段为空")
            return
        }
        _searchText.value = text
        lastSearchText = text
        lastSearchUri = null
        _displayedCount.value = 0 // Reset for fresh search
        val topK = imageSearcher.topK.value
        viewModelScope.launch(ioDispatcher) {
            _searchState.value = SearchState.SEARCHING
            imageSearcher.searchV2(text) { ids ->
                Timber.tag(TAG).d("searchV2 ids: $ids")
                updateResults(ids, topK, isLoadMore = false)
                _searchState.value = SearchState.FINISHED
            }
        }
    }

    fun startSearch(uri: Uri) {
        val photo = repo.getBitmapFromUri(uri)
        if (photo == null) {
            showToast(context.getString(R.string.empty_search_content_toast))
            Log.w(TAG, "搜索字段为空")
            return
        }
        lastSearchUri = uri
        lastSearchText = null
        _displayedCount.value = 0 // Reset for fresh search
        val topK = imageSearcher.topK.value
        viewModelScope.launch(ioDispatcher) {
            _searchState.value = SearchState.SEARCHING
            imageSearcher.searchWithRangeV2(photo) { ids ->
                updateResults(ids, topK, isLoadMore = false)
                _searchState.value = SearchState.FINISHED
            }
        }
    }

    /**
     * Fetch more results for the current search query.
     * If there are already fetched results that haven't been displayed yet, just show more.
     * Otherwise, re-run the search with a larger topK.
     */
    fun loadMore() {
        val currentDisplayed = _displayedCount.value
        val currentTotal = _allResultList.value.size
        val lastTopK = _lastFetchedTopK.value

        if (currentDisplayed < currentTotal) {
            // Show next batch of already-fetched results
            _displayedCount.value = minOf(currentDisplayed + LOAD_MORE_INCREMENT, currentTotal)
            return
        }

        // Need to fetch more from the database
        val newTopK = minOf(lastTopK + LOAD_MORE_INCREMENT, MAX_EXTENDED_TOP_K)
        if (newTopK <= lastTopK) return // Already at cap

        val text = lastSearchText
        val uri = lastSearchUri
        viewModelScope.launch(ioDispatcher) {
            _searchState.value = SearchState.SEARCHING
            if (text != null) {
                imageSearcher.searchV2WithTopK(text, newTopK) { ids ->
                    updateResults(ids, newTopK, isLoadMore = true)
                    _searchState.value = SearchState.FINISHED
                }
            } else if (uri != null) {
                val bitmap = repo.getBitmapFromUri(uri)
                if (bitmap != null) {
                    imageSearcher.searchWithRangeV2WithTopK(bitmap, newTopK) { ids ->
                        updateResults(ids, newTopK, isLoadMore = true)
                        _searchState.value = SearchState.FINISHED
                    }
                } else {
                    _searchState.value = SearchState.FINISHED
                }
            }
        }
    }

    /**
     * Load photos from imageSearcher.searchResultIds (used by roulette navigation).
     * The IDs must already be set on imageSearcher before calling this.
     */
    fun loadFromSearchResultIds() {
        viewModelScope.launch(ioDispatcher) {
            _searchState.value = SearchState.SEARCHING
            val ids = imageSearcher.searchResultIds.toList()
            if (ids.isNotEmpty()) {
                val photos = repo.getPhotoListByIds(ids)
                val ordered = reOrderList(photos, ids)
                _allResultList.value = ordered
                _allResultMap.value = emptyMap()
                _displayedCount.value = ordered.size
                _lastFetchedTopK.value = 0
                Timber.tag(TAG).d("loadFromSearchResultIds: ${ordered.size} photos")
            }
            _searchState.value = SearchState.FINISHED
        }
    }

    private suspend fun updateResults(ids: List<Pair<Long, Double>>, fetchedTopK: Int, isLoadMore: Boolean = false) {
        if (ids.isNotEmpty()) {
            val photos = repo.getPhotoListByIds(ids.map { it.first })
            val ordered = reOrderList(photos, ids.map { it.first })
            _allResultList.value = ordered
            _allResultMap.update {
                ids.associate { it.first to (1.0 - it.second) }.toMutableMap()
            }
            _lastFetchedTopK.value = fetchedTopK
            val initialDisplay = minOf(imageSearcher.topK.value, ordered.size)
            _displayedCount.value = if (isLoadMore) {
                // Extend display window by one increment beyond current
                minOf(_displayedCount.value + LOAD_MORE_INCREMENT, ordered.size)
            } else {
                initialDisplay
            }
            Timber.tag(TAG).d("updateResults: ${ordered.size} total, showing ${_displayedCount.value}")
        } else {
            _allResultList.value = emptyList()
            _allResultMap.value = emptyMap()
            _displayedCount.value = 0
            _lastFetchedTopK.value = fetchedTopK
        }
    }

    // fix the order of the result list
    private fun reOrderList(originalList: List<Photo>, orderList: List<Long>): List<Photo> {
        val photoMap = originalList.associateBy { it.id }
        return orderList.mapNotNull { id -> photoMap[id] }
    }
}
