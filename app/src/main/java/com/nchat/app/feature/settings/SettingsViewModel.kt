package com.nchat.app.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nchat.app.core.update.AutoUpdater
import com.nchat.app.core.update.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateUiState(
    val checking: Boolean = false,
    val installing: Boolean = false,
    val info: UpdateInfo? = null,
    val dialogVisible: Boolean = false,
    val notice: String? = null
)

class SettingsViewModel : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    fun checkForUpdates(context: Context) {
        if (_state.value.checking) return
        viewModelScope.launch {
            _state.update { it.copy(checking = true, notice = null) }
            try {
                val info = AutoUpdater.checkForUpdate()
                _state.update {
                    it.copy(
                        checking = false,
                        info = info,
                        dialogVisible = info != null,
                        notice = if (info == null) "You are on the latest version." else null
                    )
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        checking = false,
                        notice = error.message ?: "Could not check for updates. Check your connection."
                    )
                }
            }
        }
    }

    fun downloadAndInstall(context: Context) {
        val info = _state.value.info ?: return
        if (_state.value.installing) return
        viewModelScope.launch {
            _state.update { it.copy(installing = true, notice = null) }
            try {
                val apk = AutoUpdater.downloadUpdate(context, info)
                val launched = AutoUpdater.installUpdate(context, apk)
                _state.update {
                    it.copy(
                        installing = false,
                        dialogVisible = if (launched) false else it.dialogVisible,
                        notice = if (launched) {
                            null
                        } else {
                            "Allow NChat to install unknown apps, then tap Install again."
                        }
                    )
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        installing = false,
                        notice = error.message ?: "Update download failed. Please try again."
                    )
                }
            }
        }
    }

    fun dismissDialog() {
        if (_state.value.installing) return
        _state.update { it.copy(dialogVisible = false) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }
}
