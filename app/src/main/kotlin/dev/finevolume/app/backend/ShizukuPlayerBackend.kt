package dev.finevolume.app.backend

import android.media.AudioManager
import dev.finevolume.app.domain.model.AudioStream
import dev.finevolume.app.domain.model.BackendType
import dev.finevolume.app.domain.model.VolumeCapabilities
import dev.finevolume.app.domain.model.VolumeState
import dev.finevolume.app.shizuku.IFineVolumeService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Volume backend using Shizuku user service for sub-step precision.
 *
 * Combines:
 * 1. NativeVolumeBackend for coarse step setting (AudioManager index)
 * 2. IPlayer.setVolume() via Shizuku for fractional gain within a step
 *
 * This provides genuine float-precision volume control for active media sessions.
 *
 * AUDIO QUALITY NOTE:
 * IPlayer.setVolume() applies a software gain multiplier to the player output.
 * This is digital attenuation and is not bit-perfect. FineVolume clamps gain to
 * 0..1 and does not claim lossless or exclusive audio output.
 *
 * Shizuku is optional — this backend is only used when Shizuku is connected and
 * the user service is bound. Falls back to NativeVolumeBackend automatically.
 */
@Singleton
class ShizukuPlayerBackend @Inject constructor(
    private val audioManager: AudioManager,
) : VolumeBackend {

    override val type = BackendType.SHIZUKU_PLAYER

    @Volatile private var userService: IFineVolumeService? = null
    @Volatile var onServiceFailure: ((String) -> Unit)? = null
    val currentUserService: IFineVolumeService? get() = userService
    @Volatile private var currentSessionGain: Float = 1.0f
    @Volatile private var cachedMaxVolume: Int = -1
    @Volatile private var lastAppliedNativeIndex: Int = -1
    @Volatile private var lastAppliedGain: Float = -1f

    private fun getMaxVolume(stream: AudioStream): Int {
        var max = cachedMaxVolume
        if (max <= 0) {
            max = audioManager.getStreamMaxVolume(stream.androidStream)
            cachedMaxVolume = max
        }
        return max
    }

    fun bindUserService(service: IFineVolumeService) {
        if (!service.asBinder().isBinderAlive || !service.asBinder().pingBinder()) {
            handleServiceFailure("Shizuku audio service binder is already dead.")
            return
        }
        userService = service
        currentSessionGain = 1.0f
        lastAppliedGain = -1f
    }

    fun unbindUserService() {
        userService = null
        currentSessionGain = 1.0f
        lastAppliedNativeIndex = -1
        lastAppliedGain = -1f
    }

    private fun handleServiceFailure(reason: String) {
        userService = null
        currentSessionGain = 1.0f
        lastAppliedGain = -1f
        onServiceFailure?.invoke(reason)
    }

    /**
     * Executes a small read-only AIDL call to verify that the optional user
     * service is alive and responsive. It does not alter the device volume.
     */
    fun probeUserService(): Result<Int> {
        val service = userService
            ?: return Result.failure(IllegalStateException("Shizuku audio user service is not bound."))
        return runCatching {
            val binder = service.asBinder()
            check(binder.isBinderAlive && binder.pingBinder()) { "Shizuku audio service binder is dead." }
            service.getActiveSessionCount(AudioManager.STREAM_MUSIC)
        }.onFailure {
            handleServiceFailure(it.message ?: it.javaClass.simpleName)
        }
    }


    override suspend fun getState(stream: AudioStream): VolumeState {
        val max = getMaxVolume(stream)
        val current = audioManager.getStreamVolume(stream.androidStream)
        
        var position = if (max > 0) current.toFloat() / max else 0f
        
        if (userService != null && max > 0 && current > 0) {
            val ceilIndex = current
            if (currentSessionGain < 1.0f) {
                // Reverse the cubic (x^3) math to reconstruct the exact floating point index using fast hardware cbrt
                val linearRatio = Math.cbrt(currentSessionGain.toDouble()).toFloat()
                val floatIndex = ceilIndex.toFloat() * linearRatio
                position = (floatIndex / max).coerceIn(0f, 1f)
            }
        }
        
        return VolumeState(
            stream = stream,
            position = position.coerceIn(0f, 1f),
            nativeIndex = current,
            nativeMax = max,
            sessionGain = currentSessionGain.takeIf { userService != null },
        )
    }

    override suspend fun setPosition(stream: AudioStream, position: Float): Result<Unit> {
        return runCatching {
            val clampedPosition = position.coerceIn(0f, 1f)
            val max = getMaxVolume(stream)
            if (max <= 0) return@runCatching

            val floatIndex = clampedPosition * max
            val ceilIndex = kotlin.math.ceil(floatIndex).toInt().coerceIn(0, max)
            val nativeIndex = ceilIndex

            // Compare against the actual system value, not only our last write.
            // Samsung SystemUI, Samsung Music, accessories, or another controller can
            // change the stream index independently while FineVolume is running.
            val actualNativeIndex = audioManager.getStreamVolume(stream.androidStream)
            if (nativeIndex != actualNativeIndex) {
                audioManager.setStreamVolume(stream.androidStream, nativeIndex, 0)
            }
            lastAppliedNativeIndex = nativeIndex

            // 2. Apply fractional attenuation within the native step via Shizuku
            val service = userService
            if (service != null) {
                val sessionGain = if (nativeIndex == 0) {
                    1.0f // Muted
                } else {
                    val linearRatio = floatIndex / ceilIndex.toFloat()
                    linearRatio * linearRatio * linearRatio // x^3 cubic perceptual taper
                }.coerceIn(0f, 1f)

                // Only send IPC if gain materially changed
                if (Math.abs(sessionGain - lastAppliedGain) > 0.0005f) {
                    currentSessionGain = sessionGain
                    lastAppliedGain = sessionGain
                    try {
                        service.applySessionGain(stream.androidStream, sessionGain)
                    } catch (t: Throwable) {
                        // Keep the native AudioManager stream adjustment. Only the
                        // optional fractional player gain is dropped on IPC failure.
                        handleServiceFailure(t.message ?: t.javaClass.simpleName)
                    }
                }
            }
        }
    }

    override fun capabilities(): VolumeCapabilities {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val hasService = userService?.asBinder()?.let { it.isBinderAlive && it.pingBinder() } == true
        if (!hasService && userService != null) handleServiceFailure("Shizuku audio service binder is not alive.")
        return VolumeCapabilities(
            // With float sub-step gain, effective resolution is very high.
            // 1000 is a practical upper limit — beyond this, gain differences are inaudible.
            effectiveResolution = if (hasService) 1000 else max,
            supportsSubStepGain = hasService,
            isBitPerfect = false, // Software gain is active — not bit-perfect
            backendType = BackendType.SHIZUKU_PLAYER,
            limitedReason = if (!hasService)
                "Shizuku user service not bound — falling back to native resolution ($max steps)"
            else null,
        )
    }
}
