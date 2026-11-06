package org.siloserver.silo.common.player.audio

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

/**
 * Keeps a passthrough (bitstream) audio output open across seeks.
 *
 * Media3's DefaultAudioSink releases its audio output on every flush, so every
 * seek closes the AudioTrack and opens a new one. For PCM that is harmless: the
 * platform mixer keeps the HDMI link running. For passthrough (Dolby Digital
 * Plus, TrueHD, DTS) the direct output closes with the track, the HDMI
 * bitstream stops, and the AV receiver or soundbar mutes for seconds while it
 * locks onto the new stream. Every seek and Skip Intro loses the first seconds
 * of sound.
 *
 * Here, releasing a passthrough output pauses and flushes its AudioTrack and
 * parks it instead. The sink asks for an output with the same configuration a
 * moment later and gets the parked one back, still open, so the HDMI stream
 * never closes. A parked output nobody reclaims within [PARK_TIMEOUT_MS] is
 * released for real. PCM, offloaded and tunneled outputs are untouched.
 */
@UnstableApi
class PassthroughKeepAliveAudioOutputProvider(
    delegate: AudioOutputProvider,
) : ForwardingAudioOutputProvider(delegate) {

    private var parked: KeptAliveAudioOutput? = null
    private var parkedConfig: OutputConfig? = null
    private var parkHandler: Handler? = null
    private val releaseParked = Runnable { releaseParkedOutput() }

    override fun getAudioOutput(config: OutputConfig): AudioOutput {
        val reusable = parked
        if (reusable != null && parkedConfig == config) {
            cancelParkTimeout()
            parked = null
            parkedConfig = null
            Log.i(TAG, "keep_alive reuse encoding=${config.encoding}")
            return reusable
        }
        releaseParkedOutput()
        val output = super.getAudioOutput(config)
        return if (keepsAlive(config)) KeptAliveAudioOutput(output, config) else output
    }

    override fun release() {
        releaseParkedOutput()
        super.release()
    }

    private fun park(output: KeptAliveAudioOutput, config: OutputConfig) {
        releaseParkedOutput()
        parked = output
        parkedConfig = config
        val handler = Handler(Looper.myLooper() ?: Looper.getMainLooper())
        parkHandler = handler
        handler.postDelayed(releaseParked, PARK_TIMEOUT_MS)
    }

    private fun cancelParkTimeout() {
        parkHandler?.removeCallbacks(releaseParked)
        parkHandler = null
    }

    private fun releaseParkedOutput() {
        cancelParkTimeout()
        val output = parked ?: return
        parked = null
        parkedConfig = null
        output.releaseForReal()
    }

    private fun keepsAlive(config: OutputConfig): Boolean =
        !Util.isEncodingLinearPcm(config.encoding) && !config.isOffload && !config.isTunneling

    /**
     * Wraps a passthrough output so that a release parks it, paused and
     * flushed, rather than closing its AudioTrack.
     */
    private inner class KeptAliveAudioOutput(
        private val output: AudioOutput,
        private val config: OutputConfig,
    ) : ForwardingAudioOutput(output) {

        private val listeners = mutableListOf<AudioOutput.Listener>()

        override fun addListener(listener: AudioOutput.Listener) {
            listeners += listener
            super.addListener(listener)
        }

        override fun removeListener(listener: AudioOutput.Listener) {
            listeners -= listener
            super.removeListener(listener)
        }

        override fun release() {
            try {
                output.pause()
                output.flush()
            } catch (e: RuntimeException) {
                // A track that cannot be flushed cannot be reused safely.
                Log.w(TAG, "keep_alive flush failed; releasing", e)
                releaseForReal()
                return
            }
            // The sink counts outstanding releases and waits on onReleased.
            // To the sink this output is gone: tell its listeners so, and
            // drop them so a later owner starts clean.
            val released = listeners.toList()
            listeners.clear()
            released.forEach { listener ->
                super.removeListener(listener)
                listener.onReleased()
            }
            park(this, config)
        }

        fun releaseForReal() {
            listeners.clear()
            output.release()
        }
    }

    private companion object {
        const val TAG = "PassthroughKeepAlive"

        // A seek served from memory reclaims the output within milliseconds;
        // one that rebuffers within a few seconds. After this the output is
        // closed as Media3 would have closed it.
        const val PARK_TIMEOUT_MS = 5_000L
    }
}
