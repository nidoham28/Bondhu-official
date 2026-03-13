package com.nidoham.bondhu.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.nidoham.server.domain.participant.User
import com.nidoham.server.repository.participant.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userRepository: UserRepository,
    // FIX: getCurrentUserId() did not exist on UserRepository.
    //      FirebaseAuth is the authoritative source for the current user's UID.
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    // Derived StateFlow for photoUrl — UI collects this directly so it
    // recomposes only when the URL actually changes.
    private val _profileImageUrl = MutableStateFlow<String?>(null)
    val profileImageUrl: StateFlow<String?> = _profileImageUrl.asStateFlow()

    init {
        loadCurrentUser()
    }

    private fun loadCurrentUser() {
        val uid = firebaseAuth.currentUser?.uid
        if (uid == null) {
            Timber.w("MainViewModel: no authenticated user")
            return
        }

        viewModelScope.launch {
            val user = userRepository.fetchUser(uid)
                .onFailure { e -> Timber.e(e, "MainViewModel: failed to load profile") }
                .getOrNull()

            _currentUser.value = user
            _profileImageUrl.value = user?.photoUrl
            Timber.d("MainViewModel: profile loaded — photoUrl=${user?.photoUrl}")
        }
    }
}