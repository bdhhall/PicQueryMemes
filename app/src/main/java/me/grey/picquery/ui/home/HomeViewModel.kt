package me.grey.picquery.ui.home

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.grey.picquery.PicQueryApplication
import me.grey.picquery.R
import me.grey.picquery.common.showToast
import me.grey.picquery.domain.ImageSearcher
import timber.log.Timber

data class UserGuideTaskState(
    val permissionDone: Boolean = false,
    val indexDone: Boolean = false
) {
    val allFinished: Boolean
        get() = permissionDone && indexDone
}

class HomeViewModel(
    private val imageSearcher: ImageSearcher,
    private val preferenceRepository: me.grey.picquery.data.data_source.PreferenceRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
        private const val ROULETTE_COUNT = 20
    }

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText

    val userGuideVisible = mutableStateOf(false)

    val currentGuideState = mutableStateOf(UserGuideTaskState())

    fun onQueryChange(query: String) {
        _searchText.value = query
    }

    init {
        viewModelScope.launch {
            // 检查用户是否已经完成过引导
            val guideCompleted = preferenceRepository.isUserGuideCompleted()
            val hasData = imageSearcher.hasEmbedding()
            
            if (guideCompleted || hasData) {
                // 用户已经完成引导或有索引数据，不需要显示引导
                currentGuideState.value = UserGuideTaskState(
                    permissionDone = true,
                    indexDone = true
                )
                userGuideVisible.value = false
                
                // 如果有数据但标记未设置，更新标记
                if (hasData && !guideCompleted) {
                    preferenceRepository.setUserGuideCompleted(true)
                }
            } else {
                // 首次使用，需要显示引导
                userGuideVisible.value = true
            }
        }
    }

    fun showUserGuide() {
        userGuideVisible.value = true
    }

    fun doneRequestPermission() {
        Timber.tag(TAG).d("doneRequestPermission")
        currentGuideState.value = currentGuideState.value.copy(permissionDone = true)
    }

    fun doneIndexAlbum() {
        currentGuideState.value = currentGuideState.value.copy(indexDone = true)
    }

    fun finishGuide() {
        userGuideVisible.value = false
        // 标记用户已完成引导
        viewModelScope.launch {
            preferenceRepository.setUserGuideCompleted(true)
        }
    }

    private val context: Context
        get() = PicQueryApplication.context

    /**
     * Pick a random set of indexed photos for the roulette feature.
     * Calls onComplete (on main thread) when the IDs have been set.
     */
    fun triggerRoulette(onComplete: () -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            val ids = imageSearcher.pickRandomPhotos(ROULETTE_COUNT)
            if (ids.isEmpty()) {
                showToast(context.getString(R.string.roulette_no_index_toast))
            } else {
                withContext(Dispatchers.Main) {
                    // Set search result IDs on the main thread for thread safety
                    imageSearcher.searchResultIds.clear()
                    imageSearcher.searchResultIds.addAll(ids)
                    onComplete()
                }
            }
        }
    }
}
