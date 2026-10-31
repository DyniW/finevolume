package dev.finevolume.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.finevolume.app.backend.BackendSelector
import dev.finevolume.app.data.AppSettings
import dev.finevolume.app.data.SettingsRepository
import dev.finevolume.app.diagnostics.FineVolumeDiagnostics
import dev.finevolume.app.domain.model.BackendType
import dev.finevolume.app.domain.model.VolumeCapabilities
import dev.finevolume.app.shizuku.ShizukuManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backendSelector: BackendSelector,
    private val shizukuManager: ShizukuManager,
    private val diagnostics: FineVolumeDiagnostics
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Lazily, AppSettings())

    val activeBackendCapabilities: StateFlow<VolumeCapabilities> = backendSelector.activeBackend
        .map { it.capabilities() }
        .stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            VolumeCapabilities(15, false, false, BackendType.NATIVE, null)
        )

    val isShizukuConnected: StateFlow<Boolean> = shizukuManager.isConnected
    val hasShizukuPermission: StateFlow<Boolean> = shizukuManager.permissionGranted
    val shizukuServerUid: StateFlow<Int?> = shizukuManager.serverUid
    val shizukuLastError: StateFlow<String?> = shizukuManager.lastError
    val shizukuServiceStatus: StateFlow<String> = backendSelector.shizukuServiceStatus

    private val _diagnosticReport = MutableStateFlow(
        diagnostics.lastReport() ?: "No diagnostic report yet. Run checks to inspect native audio and the optional Shizuku engine."
    )
    val diagnosticReport: StateFlow<String> = _diagnosticReport

    private val _diagnosticsRunning = MutableStateFlow(false)
    val diagnosticsRunning: StateFlow<Boolean> = _diagnosticsRunning

    fun refreshRuntimeState() {
        shizukuManager.refreshStatus()
    }

    fun runDiagnostics() {
        if (_diagnosticsRunning.value) return
        viewModelScope.launch {
            _diagnosticsRunning.value = true
            try {
                _diagnosticReport.value = diagnostics.runChecks()
            } catch (t: Throwable) {
                _diagnosticReport.value = "Diagnostics failed: " +
                    (t.message ?: t.javaClass.simpleName)
            } finally {
                _diagnosticsRunning.value = false
            }
        }
    }

    fun retryShizukuEngine() {
        shizukuManager.refreshStatus()
        backendSelector.retryShizukuEngine()
        viewModelScope.launch {
            delay(900L)
            runDiagnostics()
        }
    }

    fun updateIsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateIsEnabled(enabled)
        }
    }

    fun updateMediaResolution(resolution: Float) {
        viewModelScope.launch {
            settingsRepository.updateMediaResolution(resolution.toInt())
        }
    }

    fun updateMediaIncrement(increment: Float) {
        viewModelScope.launch {
            settingsRepository.updateMediaIncrement(increment.toInt())
        }
    }

    fun updateLongPressRepeatInterval(ms: Float) {
        viewModelScope.launch {
            settingsRepository.updateLongPressRepeatInterval(ms.toInt())
        }
    }

    fun updateHapticEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateHapticLevel(if (enabled) 1 else 0)
        }
    }

    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }
}
