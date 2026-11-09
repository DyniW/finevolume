package dev.finevolume.app.backend

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import dev.finevolume.app.shizuku.FineVolumeUserService
import dev.finevolume.app.shizuku.IFineVolumeService
import dev.finevolume.app.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects the optional Shizuku player backend only while its permission and
 * UserService IPC are healthy. Native AudioManager control is always available.
 */
@Singleton
class BackendSelector @Inject constructor(
    private val nativeBackend: NativeVolumeBackend,
    private val shizukuPlayerBackend: ShizukuPlayerBackend,
    private val fallbackBackend: FallbackBackend,
    private val shizukuManager: ShizukuManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeBackend = MutableStateFlow<VolumeBackend>(nativeBackend)
    val activeBackend: StateFlow<VolumeBackend> = _activeBackend

    private val _shizukuServiceStatus = MutableStateFlow("Checking optional Shizuku engine…")
    val shizukuServiceStatus: StateFlow<String> = _shizukuServiceStatus

    @Volatile private var bindRequested = false

    private fun userServiceArgs() =
        Shizuku.UserServiceArgs(
            ComponentName("dev.finevolume.app", FineVolumeUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(false)
            .version(2)
            .tag("finevolume-audio")

    private fun fallBackToNative(reason: String) {
        shizukuPlayerBackend.unbindUserService()
        bindRequested = false
        _activeBackend.value = nativeBackend
        _shizukuServiceStatus.value = reason + " Native AudioManager control is active."
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.isBinderAlive || !binder.pingBinder()) {
                fallBackToNative("Shizuku returned an unavailable audio-service binder.")
                return
            }

            val remote = IFineVolumeService.Stub.asInterface(binder)
            if (remote == null) {
                fallBackToNative("Shizuku audio-service interface could not be created.")
                return
            }

            _shizukuServiceStatus.value = "Audio user service connected. Verifying IPC…"
            shizukuPlayerBackend.bindUserService(remote)

            scope.launch {
                val probe = withContext(Dispatchers.IO) { shizukuPlayerBackend.probeUserService() }
                probe.fold(
                    onSuccess = { sessions ->
                        if (shizukuManager.isConnected.value && shizukuManager.permissionGranted.value) {
                            _activeBackend.value = shizukuPlayerBackend
                            _shizukuServiceStatus.value =
                                "Ready. AIDL health check passed; " + sessions + " active media session(s)."
                        } else {
                            fallBackToNative("Shizuku permission changed during service startup.")
                        }
                    },
                    onFailure = {
                        fallBackToNative(
                            "Audio user-service health check failed: " +
                                (it.message ?: it.javaClass.simpleName) + "."
                        )
                    }
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fallBackToNative("Shizuku audio user service disconnected or crashed.")
        }

        override fun onBindingDied(name: ComponentName?) {
            fallBackToNative("Android reported that the Shizuku service binding died.")
        }

        override fun onNullBinding(name: ComponentName?) {
            fallBackToNative("Shizuku returned a null service binding.")
        }
    }

    init {
        shizukuPlayerBackend.onServiceFailure = { reason ->
            scope.launch {
                fallBackToNative("Shizuku audio IPC failed: " + reason + ".")
            }
        }

        scope.launch {
            combine(shizukuManager.isConnected, shizukuManager.permissionGranted) { connected, granted ->
                connected to granted
            }.collect { state ->
                val connected = state.first
                val granted = state.second
                when {
                    !connected -> {
                        if (bindRequested) {
                            runCatching { Shizuku.unbindUserService(userServiceArgs(), serviceConnection, true) }
                        }
                        fallBackToNative("Shizuku server is unavailable.")
                    }
                    !granted -> {
                        if (bindRequested) {
                            runCatching { Shizuku.unbindUserService(userServiceArgs(), serviceConnection, true) }
                        }
                        fallBackToNative("Shizuku permission is missing or was reset.")
                    }
                    else -> bindOptionalEngine()
                }
            }
        }
    }

    private fun bindOptionalEngine() {
        if (bindRequested) return
        bindRequested = true
        _activeBackend.value = nativeBackend
        _shizukuServiceStatus.value = "Permission granted. Starting optional audio user service…"
        try {
            Shizuku.bindUserService(userServiceArgs(), serviceConnection)
        } catch (t: Throwable) {
            fallBackToNative(
                "Could not start Shizuku audio user service: " +
                    (t.message ?: t.javaClass.simpleName) + "."
            )
        }
    }

    /**
     * Explicit retry from the in-app diagnostics panel. Native audio remains
     * selected until the new UserService answers a health-check IPC.
     */
    fun retryShizukuEngine() {
        shizukuManager.refreshStatus()
        scope.launch {
            if (!shizukuManager.isConnected.value) {
                fallBackToNative("Shizuku server is not running. Native audio remains available.")
                return@launch
            }
            if (!shizukuManager.permissionGranted.value) {
                fallBackToNative("Shizuku permission is not granted. Native audio remains available.")
                return@launch
            }

            if (bindRequested) {
                runCatching { Shizuku.unbindUserService(userServiceArgs(), serviceConnection, true) }
                shizukuPlayerBackend.unbindUserService()
                bindRequested = false
                delay(250L)
            }
            bindOptionalEngine()
        }
    }

    /**
     * A safe, read-only probe of the optional UserService. This never changes
     * the system volume.
     */
    suspend fun probeShizukuEngine(): String = withContext(Dispatchers.IO) {
        if (!shizukuManager.isConnected.value) {
            return@withContext "Not running: Shizuku binder is unavailable."
        }
        if (!shizukuManager.permissionGranted.value) {
            return@withContext "Not ready: permission is not granted."
        }

        val result = shizukuPlayerBackend.probeUserService()
        result.fold(
            onSuccess = { "Responding: AIDL health check passed; " + it + " active media session(s)." },
            onFailure = {
                "Not responding: " + (it.message ?: it.javaClass.simpleName) + ". Native fallback selected."
            }
        )
    }
}
