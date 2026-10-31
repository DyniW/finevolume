package dev.finevolume.app.shizuku

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Runs in the Shizuku user-service process and applies software gain to active
 * media PlayerProxy instances. The system stream volume remains the master ceiling.
 *
 * The stream gain and per-app gain are stored independently. Every player receives
 * their product, so changing either setting cannot accidentally overwrite the other.
 */
@Keep
class FineVolumeUserService : IFineVolumeService.Stub() {

    private var audioManager: AudioManager? = null
    private var packageManager: android.content.pm.PackageManager? = null
    private var getPlayerProxyMethod: Method? = null
    @Volatile private var setVolumeMethod: Method? = null
    private var isActiveMethod: Method? = null
    private var getPlayerStateMethod: Method? = null
    private var getClientUidMethod: Method? = null
    private var clientUidField: java.lang.reflect.Field? = null
    private var callbackHandler: Handler? = null

    private val appGainMap = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private val uidPackageCache = java.util.concurrent.ConcurrentHashMap<Int, String>()

    @Volatile
    private var mediaStreamGain: Float = 1.0f

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val handler = callbackHandler ?: return
            // Samsung Music can replace/recreate its player while changing tracks or
            // resuming. Reapply after the framework publishes the new proxy.
            handler.post { reapplyAllPlayerGains() }
            handler.postDelayed({ reapplyAllPlayerGains() }, 120L)
            handler.postDelayed({ reapplyAllPlayerGains() }, 450L)
        }
    }

    init {
        try {
            val looper = Looper.myLooper() ?: run {
                Looper.prepare()
                Looper.myLooper()
            }
            callbackHandler = Handler(looper ?: Looper.getMainLooper())

            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val systemMainMethod = activityThreadClass.getDeclaredMethod("systemMain").apply {
                isAccessible = true
            }
            val activityThread = systemMainMethod.invoke(null)
            val getSystemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext").apply {
                isAccessible = true
            }
            val systemContext = getSystemContextMethod.invoke(activityThread) as Context

            audioManager = systemContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            packageManager = systemContext.packageManager

            getPlayerProxyMethod = AudioPlaybackConfiguration::class.java
                .getDeclaredMethod("getPlayerProxy")
                .apply { isAccessible = true }

            isActiveMethod = runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredMethod("isActive")
                    .apply { isAccessible = true }
            }.getOrNull()

            getPlayerStateMethod = runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerState")
                    .apply { isAccessible = true }
            }.getOrNull() ?: runCatching {
                AudioPlaybackConfiguration::class.java.getMethod("getPlayerState")
                    .apply { isAccessible = true }
            }.getOrNull()

            getClientUidMethod = runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredMethod("getClientUid")
                    .apply { isAccessible = true }
            }.getOrNull() ?: runCatching {
                AudioPlaybackConfiguration::class.java.getMethod("getClientUid")
                    .apply { isAccessible = true }
            }.getOrNull()

            clientUidField = runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredField("mClientUid")
                    .apply { isAccessible = true }
            }.getOrNull()

            audioManager?.registerAudioPlaybackCallback(
                playbackCallback,
                callbackHandler
            )
            Log.i(TAG, "FineVolume playback observer registered")
            reapplyAllPlayerGains()
        } catch (t: Throwable) {
            Log.e(TAG, "FineVolume Shizuku audio service initialization failed", t)
        }
    }

    private fun getUidForConfig(config: AudioPlaybackConfiguration): Int {
        val fromMethod = runCatching { getClientUidMethod?.invoke(config) as? Int }
            .getOrNull()?.takeIf { it > 0 }
        if (fromMethod != null) return fromMethod

        return runCatching { clientUidField?.getInt(config) ?: -1 }
            .getOrDefault(-1)
    }

    private fun getPackageNameForUid(uid: Int): String? {
        if (uid <= 1000) return null
        uidPackageCache[uid]?.let { return it }

        val packageName = runCatching {
            packageManager?.getPackagesForUid(uid)?.firstOrNull {
                it !in SYSTEM_PACKAGES && !it.startsWith("com.android.keyguard")
            }
        }.getOrNull()

        if (packageName != null) uidPackageCache[uid] = packageName
        return packageName
    }

    private fun findSetVolumeMethod(playerProxy: Any): Method? {
        setVolumeMethod?.let { return it }
        var type: Class<*>? = playerProxy.javaClass
        while (type != null) {
            val currentType = type
            val method = runCatching {
                currentType.getDeclaredMethod("setVolume", java.lang.Float.TYPE)
                    .apply { isAccessible = true }
            }.getOrNull()
            if (method != null) {
                setVolumeMethod = method
                return method
            }
            type = currentType.superclass
        }
        Log.w(TAG, "PlayerProxy.setVolume(float) was not found")
        return null
    }

    private fun isMusicSession(config: AudioPlaybackConfiguration): Boolean {
        val attributes = config.audioAttributes ?: return false
        return runCatching {
            attributes.volumeControlStream == AudioManager.STREAM_MUSIC
        }.getOrDefault(false)
    }

    private fun applyGainToConfig(config: AudioPlaybackConfiguration) {
        try {
            // STREAM_MUSIC attenuation must not alter calls, alarms, ringtones,
            // notification sounds, or other non-media playback sessions.
            if (!isMusicSession(config)) return
            val playerProxy = getPlayerProxyMethod?.invoke(config) ?: return
            val uid = getUidForConfig(config)
            val packageName = if (uid > 0) getPackageNameForUid(uid) else null
            val appGain = packageName?.let { appGainMap[it] } ?: 1.0f
            val effectiveGain = (mediaStreamGain * appGain).coerceIn(0f, 1f)

            val method = findSetVolumeMethod(playerProxy) ?: return
            method.invoke(playerProxy, effectiveGain)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not apply player gain to an active session", t)
        }
    }

    private fun reapplyAllPlayerGains() {
        val configs = runCatching {
            audioManager?.activePlaybackConfigurations.orEmpty()
        }.getOrElse {
            Log.w(TAG, "Could not query active playback configurations", it)
            return
        }

        configs.forEach(::applyGainToConfig)
    }

    override fun applySessionGain(streamType: Int, gain: Float) {
        if (streamType != AudioManager.STREAM_MUSIC) return
        mediaStreamGain = gain.coerceIn(0f, 1f)
        reapplyAllPlayerGains()
    }

    override fun applyAppGain(packageName: String, gain: Float) {
        appGainMap[packageName] = gain.coerceIn(0f, 1f)
        reapplyAllPlayerGains()
    }

    override fun getActiveAppPackages(): List<String> {
        val configs = runCatching {
            audioManager?.activePlaybackConfigurations.orEmpty()
        }.getOrDefault(emptyList())

        return configs.mapNotNull { config ->
            if (!isMusicSession(config)) return@mapNotNull null
            val state = runCatching { getPlayerStateMethod?.invoke(config) as? Int }
                .getOrNull()
            val isPlaying = state?.let { it == 2 } ?: runCatching {
                isActiveMethod?.invoke(config) as? Boolean ?: true
            }.getOrDefault(true)

            if (!isPlaying) return@mapNotNull null
            val uid = getUidForConfig(config)
            if (uid <= 0) return@mapNotNull null
            getPackageNameForUid(uid)?.takeIf { it !in SYSTEM_PACKAGES }
        }.distinct()
    }

    override fun getAppGain(packageName: String): Float =
        appGainMap[packageName] ?: 1.0f

    override fun getActiveSessionCount(streamType: Int): Int {
        if (streamType != AudioManager.STREAM_MUSIC) return 0
        return runCatching {
            audioManager?.activePlaybackConfigurations?.size ?: 0
        }.getOrDefault(0)
    }

    override fun destroy() {
        runCatching {
            audioManager?.unregisterAudioPlaybackCallback(playbackCallback)
        }
        callbackHandler?.removeCallbacksAndMessages(null)
        Log.i(TAG, "FineVolumeUserService destroy")
        exitProcess(0)
    }

    companion object {
        private const val TAG = "FineVolumeUserService"
        private val SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "dev.finevolume.app"
        )
    }
}
