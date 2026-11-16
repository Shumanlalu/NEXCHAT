package com.nexchat.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexchat.app.core.network.ApiException
import com.nexchat.app.data.AuthRepository
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AuthMode { LOGIN, REGISTER }

data class AuthFormState(
    val mode: AuthMode = AuthMode.LOGIN,
    val username: String = "",
    val displayName: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val showPassword: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(
    private val auth: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = _state.asStateFlow()

    fun setMode(mode: AuthMode) = update { it.copy(mode = mode, error = null) }

    fun setUsername(value: String) = update {
        it.copy(username = value.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' }, error = null)
    }

    fun setDisplayName(value: String) = update { it.copy(displayName = value.take(40), error = null) }

    fun setPassword(value: String) = update { it.copy(password = value, error = null) }

    fun setConfirmPassword(value: String) = update { it.copy(confirmPassword = value, error = null) }

    fun togglePassword() = update { it.copy(showPassword = !it.showPassword) }

    fun submit() {
        val snapshot = _state.value
        val validation = validate(snapshot)
        if (validation != null) {
            _state.value = snapshot.copy(error = validation)
            return
        }
        if (snapshot.loading) return
        _state.value = snapshot.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = runCatching {
                if (snapshot.mode == AuthMode.REGISTER) {
                    auth.register(
                        username = snapshot.username,
                        password = snapshot.password,
                        displayName = snapshot.displayName,
                    )
                } else {
                    auth.login(
                        username = snapshot.username,
                        password = snapshot.password,
                    )
                }
            }
            _state.value = _state.value.copy(
                loading = false,
                error = result.exceptionOrNull()?.let(::friendlyError),
            )
        }
    }

    private fun validate(state: AuthFormState): String? {
        if (state.username.length !in 3..24) {
            return "Username must be 3 to 24 letters, digits, or underscores."
        }
        if (state.mode == AuthMode.REGISTER && state.displayName.isBlank()) {
            return "Choose the display name your contacts will see."
        }
        if (state.password.length < 10) return "Use a password with at least 10 characters."
        if (state.mode == AuthMode.REGISTER && state.password != state.confirmPassword) {
            return "The passwords do not match."
        }
        return null
    }

    private fun friendlyError(error: Throwable): String = when (error) {
        is ApiException -> when (error.code) {
            "INVALID_CREDENTIALS" -> "That username or password is incorrect."
            "USERNAME_TAKEN" -> "That username is already taken."
            "DEVICE_ALREADY_REGISTERED" ->
                "This device is linked to another account. Sign in to that account first."
            "RATE_LIMITED", "RATE_LIMIT_EXCEEDED" -> "Too many attempts. Wait a moment and try again."
            "NO_SERVER" -> "This build has no server configured. Install a properly configured build."
            "INSECURE_SERVER" -> "The configured server address must use HTTPS."
            "SESSION_EXPIRED" -> "Your session expired. Sign in again."
            else -> error.message ?: "The server rejected the request."
        }
        is IOException -> "Could not reach the server. Check your connection or contact the server operator."
        else -> error.message ?: "Something went wrong. Try again."
    }

    private fun update(transform: (AuthFormState) -> AuthFormState) {
        _state.value = transform(_state.value)
    }
}
