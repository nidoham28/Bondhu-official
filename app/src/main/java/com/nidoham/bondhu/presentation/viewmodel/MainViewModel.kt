package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nidoham.bondhu.data.repository.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.nidoham.server.domain.model.User
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    // Separate StateFlow for photoUrl — derived from _currentUser.
    // UI collects this directly so it re-composes only when the URL actually changes.
    private val _profileImageUrl = MutableStateFlow<String?>(null)
    val profileImageUrl: StateFlow<String?> = _profileImageUrl.asStateFlow()

    init {
        loadCurrentUser()
    }

    private fun loadCurrentUser() {
        val uid = userRepository.getCurrentUserId()
        if (uid == null) {
            Timber.w("MainViewModel: no authenticated user")
            return
        }

        viewModelScope.launch {
            // Real-time Firestore listener — profile picture updates reflect immediately
            userRepository.observeCurrentUser(uid).collect { user ->
                _currentUser.value = user
                _profileImageUrl.value = user?.photoUrl
                Timber.d("MainViewModel: profile updated — photoUrl=${user?.photoUrl}")
            }
        }
    }
}