package com.lustr.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lustr.tv.AppContainer
import com.lustr.tv.core.HomeRow
import com.lustr.tv.core.LustrException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface HomeState {
    data object Loading : HomeState
    data class Loaded(val rows: List<HomeRow>) : HomeState
    data class Failed(val message: String) : HomeState
    data object NeedsLogin : HomeState
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state

    /** (Re)load the rows; called every time Home is shown, so Continue Watching stays current. */
    fun load() {
        viewModelScope.launch {
            if (_state.value !is HomeState.Loaded) _state.value = HomeState.Loading
            _state.value = try {
                HomeState.Loaded(container.client.home())
            } catch (e: LustrException.NotLoggedIn) {
                HomeState.NeedsLogin
            } catch (e: IOException) {
                HomeState.Failed(e.message ?: "Can't reach the server")
            }
        }
    }

    fun logOut() {
        viewModelScope.launch {
            try {
                container.client.logout()
            } catch (e: IOException) {
                // logged out locally either way
            }
            container.cookieJar.clear()
            _state.value = HomeState.NeedsLogin
        }
    }
}
