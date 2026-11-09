package dev.finevolume.app.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes the optional Shizuku server. FineVolume never relies on this state
 * for native AudioManager volume control.
 *
 * Permission is re-queried whenever the app resumes, because the user may
 * restart Shizuku or its permission grant may be reset while FineVolume is away.
 */
@Singleton
class ShizukuManager @Inject constructor() {

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted

    private val _serverUid = MutableStateFlow<Int?>(null)
    val serverUid: StateFlow<Int?> = _serverUid

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        onBinderDead()
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_CODE) {
            _permissionGranted.value = grantResult == PackageManager.PERMISSION_GRANTED
            if (_permissionGranted.value) {
                _lastError.value = null
                _serverUid.value = runCatching { Shizuku.getUid() }.getOrNull()
            } else {
                _serverUid.value = null
                _lastError.value = "Shizuku permission was denied or reset."
            }
        }
    }

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }.onFailure {
            _lastError.value = it.message ?: it.javaClass.simpleName
        }
        refreshStatus()
    }

    /**
     * Safe to call on every Activity resume. Shizuku APIs that require a live
     * binder are only called after pingBinder() succeeds.
     */
    fun refreshStatus() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        _isConnected.value = alive
        if (!alive) {
            _permissionGranted.value = false
            _serverUid.value = null
            return
        }

        runCatching {
            _permissionGranted.value =
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            _serverUid.value = if (_permissionGranted.value) Shizuku.getUid() else null
            if (_permissionGranted.value) _lastError.value = null
            else _lastError.value = "Shizuku is running, but FineVolume permission is not granted."
        }.onFailure {
            _permissionGranted.value = false
            _serverUid.value = null
            _lastError.value = it.message ?: it.javaClass.simpleName
        }
    }

    fun onBinderDead() {
        _isConnected.value = false
        _permissionGranted.value = false
        _serverUid.value = null
        _lastError.value = "Shizuku server stopped or its binder died. Native volume control remains available."
    }

    fun requestPermission(requestCode: Int = SHIZUKU_PERMISSION_CODE) {
        refreshStatus()
        if (!_isConnected.value) {
            _lastError.value = "Shizuku server is not running. Start it in Shizuku, then tap Recheck."
            return
        }

        runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _permissionGranted.value = true
                _serverUid.value = Shizuku.getUid()
                _lastError.value = null
                return
            }
            Shizuku.requestPermission(requestCode)
        }.onFailure {
            _permissionGranted.value = false
            _lastError.value = it.message ?: it.javaClass.simpleName
        }
    }

    fun cleanup() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        }
    }

    companion object {
        const val SHIZUKU_PERMISSION_CODE = 1001
    }
}
