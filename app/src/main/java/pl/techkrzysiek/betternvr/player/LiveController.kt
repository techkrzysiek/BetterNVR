package pl.techkrzysiek.betternvr.player

import android.view.Surface
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import pl.techkrzysiek.betternvr.data.ChannelRef
import pl.techkrzysiek.betternvr.data.DeviceManager
import pl.techkrzysiek.betternvr.proto.DhavFrame
import pl.techkrzysiek.betternvr.proto.LoginException
import pl.techkrzysiek.betternvr.proto.MediaListener
import pl.techkrzysiek.betternvr.proto.MediaStream
import kotlin.coroutines.resume

/**
 * One live tile. Streams only while it has a surface (visible), reconnects with backoff on network
 * errors and never retries a rejected login (Dahua locks accounts after a few bad attempts).
 */
class LiveController(private val scope: CoroutineScope, private val manager: DeviceManager) {
    val player = StreamPlayer(live = true)
    private val _source = MutableStateFlow<ChannelRef?>(null)
    val source: StateFlow<ChannelRef?> = _source.asStateFlow()
    private val _subStream = MutableStateFlow(true)
    val subStream: StateFlow<Boolean> = _subStream.asStateFlow()
    private var job: Job? = null
    @Volatile private var stream: MediaStream? = null
    private var hasSurface = false
    /** null = automatic (sub-stream in multi-view, main stream when single). */
    private var forcedSub: Boolean? = null
    private var autoSub = true

    init {
        // the recorder became reachable on a preferred address (e.g. home network after the VPN): continue there
        // (not while a clip is being recorded – that stream keeps the old session until it ends)
        scope.launch {
            manager.moved.collect { id ->
                if (id == _source.value?.deviceId && job?.isActive == true && !player.recording.value) restart()
            }
        }
    }

    fun setSource(ref: ChannelRef?) {
        if (_source.value == ref) return
        _source.value = ref
        forcedSub = null
        restart()
    }

    fun attachSurface(view: View, surface: Surface) {
        player.attachSurface(view, surface)
        hasSurface = true
        restart()
    }

    fun detachSurface() {
        hasSurface = false
        stop()
        player.detachSurface()
    }

    fun setAutoSubStream(sub: Boolean) {
        autoSub = sub
        updateStream()
    }

    fun toggleQuality() {
        forcedSub = !(_subStream.value)
        updateStream()
    }

    private fun updateStream() {
        val want = forcedSub ?: autoSub
        if (want != _subStream.value) {
            _subStream.value = want
            restart()
        }
    }

    fun retry() = restart()

    fun stop() {
        job?.cancel() // reference kept so the next start can join() the teardown
        stream?.close()
        stream = null
        player.setState(PlayerState.Idle)
    }

    private fun restart() {
        val previous = job
        stop()
        val ref = _source.value ?: return
        if (!hasSurface) return
        job = scope.launch(Dispatchers.IO) {
            // Let the previous attempt finish tearing down so it cannot close this stream.
            previous?.join()
            var backoff = 1000L
            while (isActive) {
                player.setState(PlayerState.Connecting)
                player.reset()
                val error: Throwable? = try {
                    val client = manager.client(ref.deviceId)
                    suspendCancellableCoroutine<Throwable?> { cont ->
                        val s = client.openLive(ref.channel, _subStream.value, object : MediaListener {
                            override fun onFrame(frame: DhavFrame) = player.onFrame(frame)
                            override fun onClosed(error: Throwable?) { if (cont.isActive) cont.resume(error ?: Exception("closed")) }
                        })
                        stream = s
                        cont.invokeOnCancellation { s.close() }
                    }
                } catch (e: LoginException) {
                    player.setState(PlayerState.Error(e.message ?: "login", retrying = false))
                    return@launch
                } catch (e: pl.techkrzysiek.betternvr.proto.NoCameraException) {
                    manager.markNoCamera(ref)
                    player.setState(PlayerState.NoCamera)
                    return@launch
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                }
                if (!isActive) break
                if (player.state.value is PlayerState.Playing) backoff = 1000L
                player.setState(PlayerState.Error(error?.message ?: "disconnected", retrying = true))
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000)
            }
        }
    }

    fun release(context: android.content.Context) {
        stop()
        player.release(context)
    }
}
