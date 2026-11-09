package dev.finevolume.app.diagnostics

import android.content.Context
import android.media.AudioManager
import android.os.Build
import dev.finevolume.app.backend.BackendSelector
import dev.finevolume.app.shizuku.ShizukuManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-app diagnostics for the always-available public Android audio backend and
 * the optional Shizuku player engine. Tests are read-only except for writing the
 * current media index back unchanged to verify AudioManager write access.
 */
@Singleton
class FineVolumeDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val backendSelector: BackendSelector
) {
    suspend fun runChecks(): String = withContext(Dispatchers.IO) {
        shizukuManager.refreshStatus()

        val lines = mutableListOf<String>()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date())
        lines += "FineVolume diagnostics"
        lines += "Time: " + timestamp
        lines += "Device: " + Build.MANUFACTURER + " " + Build.MODEL
        lines += "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
        lines += ""

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            lines += "[FAIL] Android AudioManager could not be obtained."
        } else {
            val nativeCheck = runCatching {
                val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val fixed = audio.isVolumeFixed
                check(maximum > 0) { "Media stream reports an invalid maximum volume." }
                check(current in 0..maximum) { "Media stream returned an out-of-range volume index." }

                // No audible change: verify the setter accepts the current index,
                // then confirm Android reports the same value back.
                audio.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    current,
                    AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
                )
                val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                check(after == current) {
                    "Media volume readback changed unexpectedly (before=$current, after=$after)."
                }

                val outputs = runCatching {
                    audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .joinToString { device ->
                            device.productName?.toString()?.takeIf { it.isNotBlank() }
                                ?: "output-type-" + device.type
                        }
                }.getOrDefault("Unavailable")

                lines += if (fixed) {
                    "[WARN] Native media stream is fixed by device policy."
                } else {
                    "[PASS] Native AudioManager read/write check passed."
                }
                lines += "Media index: " + current + "/" + maximum
                lines += "Fixed-volume policy: " + fixed
                lines += "Audio mode: " + audio.mode
                lines += "Output devices: " + outputs
            }.onFailure {
                lines += "[FAIL] Native AudioManager check: " +
                    (it.message ?: it.javaClass.simpleName)
            }
            if (nativeCheck.isSuccess) {
                // Keep this branch explicit so report construction records pass/warn above.
            }
        }

        val serverRunning = shizukuManager.isConnected.value
        val permission = shizukuManager.permissionGranted.value
        val uid = shizukuManager.serverUid.value
        lines += ""
        lines += "Optional Shizuku engine"
        lines += if (serverRunning) "[PASS] Shizuku server binder is alive."
            else "[INFO] Shizuku server is not running. Native mode remains available."
        lines += if (permission) "[PASS] FineVolume Shizuku permission is granted."
            else "[INFO] Shizuku permission is absent or was reset."
        if (uid != null) {
            val identity = when (uid) {
                0 -> "root"
                2000 -> "ADB shell"
                else -> "UID " + uid
            }
            lines += "Shizuku identity: " + identity + " (" + uid + ")"
        }
        shizukuManager.lastError.value?.let { lines += "Last Shizuku note: " + it }

        val serviceProbe = backendSelector.probeShizukuEngine()
        lines += "Audio user service: " + serviceProbe
        lines += "Active backend: " + backendSelector.activeBackend.value.type.name
        lines += ""
        lines += "Interpretation:"
        lines += "- Native volume control uses Android's public AudioManager API and does not require Shizuku or root."
        lines += "- Per-player fractional attenuation is an optional Shizuku hidden-API path. It is unavailable while that service or permission is unavailable."
        lines += "- A Shizuku failure should select the native backend instead of disabling volume control."

        val report = lines.joinToString("\n")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_REPORT, report)
            .putLong(KEY_LAST_RUN, System.currentTimeMillis())
            .apply()
        report
    }

    fun lastReport(): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_REPORT, null)

    companion object {
        private const val PREFS = "finevolume_diagnostics"
        private const val KEY_LAST_REPORT = "last_report"
        private const val KEY_LAST_RUN = "last_run"
    }
}
