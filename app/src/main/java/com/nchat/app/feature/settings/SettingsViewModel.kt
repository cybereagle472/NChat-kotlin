package com.nchat.app.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nchat.app.core.update.AutoUpdater
import com.nchat.app.core.update.InstallResult
import com.nchat.app.core.update.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateUiState(
    val checking: Boolean = false,
    val installing: Boolean = false,
    /** 0f..1f download progress, or null while the size is still unknown. */
    val progress: Float? = null,
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
            _state.update { it.copy(installing = true, progress = 0f, notice = null) }
            try {
                val apk = AutoUpdater.downloadUpdate(context, info) { fraction ->
                    _state.update { it.copy(progress = fraction) }
                }
                when (val result = AutoUpdater.installUpdate(context, apk)) {
                    is InstallResult.Launched -> _state.update {
                        it.copy(installing = false, progress = null, dialogVisible = false, notice = null)
                    }

                    is InstallResult.NeedsPermission -> _state.update {
                        it.copy(
                            installing = false,
                            progress = null,
                            dialogVisible = false,
                            notice = "Allow NChat to install unknown apps, then tap Check for updates again."
                        )
                    }

                    is InstallResult.Failed -> _state.update {
                        it.copy(installing = false, progress = null, dialogVisible = false, notice = result.message)
                    }
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        installing = false,
                        progress = null,
                        dialogVisible = false,
                        notice = error.message ?: "Update failed. Please try again."
                    )
                }
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        installing = false,
                        progress = null,
                        dialogVisible = false,
                        notice = "Update failed. Please restart NChat and try again."
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
