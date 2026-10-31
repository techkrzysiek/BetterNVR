package pl.techkrzysiek.betternvr.player

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import pl.techkrzysiek.betternvr.data.ChannelRef
import pl.techkrzysiek.betternvr.data.DeviceManager
import pl.techkrzysiek.betternvr.media.ClipRecorder
import pl.techkrzysiek.betternvr.media.MediaSaver
import pl.techkrzysiek.betternvr.proto.DhavFrame
import pl.techkrzysiek.betternvr.proto.MediaListener
import pl.techkrzysiek.betternvr.proto.RecordFile
import java.time.Duration
import java.time.LocalDateTime
import kotlin.coroutines.resume

/**
 * Saves a stretch of recording as MP4 (no re-encoding), fetched 4× faster than real time like DMSS
 * does. Spans across files; progress follows the timestamps of the frames written.
 */
class DownloadController(private val scope: CoroutineScope, private val manager: DeviceManager) {
    private val _progress = MutableStateFlow<Float?>(null)
    /** null = idle, 0..1 while downloading. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()
    private var job: Job? = null

    fun start(context: Context, ref: ChannelRef, files: List<RecordFile>, from: LocalDateTime, to: LocalDateTime, label: String, onDone: (Uri?, String?) -> Unit) {
        if (job != null) return
        val parts = files.filter { it.end.isAfter(from) && it.start.isBefore(to) }.sortedBy { it.start }
        if (parts.isEmpty()) { onDone(null, null); return }
        _progress.value = 0f
        job = scope.launch(Dispatchers.IO) {
            val video = MediaSaver.createVideo(context, StreamPlayer.fileName(label))
            if (video == null) { _progress.value = null; job = null; onDone(null, "storage"); return@launch }
            val recorder = ClipRecorder(video.pfd.fileDescriptor)
            val total = Duration.between(from, to).seconds.coerceAtLeast(1).toFloat()
            var error: String? = null
            try {
                val client = manager.client(ref.deviceId)
                for (file in parts) {
                    val start = maxOf(from, file.start)
                    val end = minOf(to, file.end)
                    suspendCancellableCoroutine<Unit> { cont ->
                        val s = client.openPlayback(file, start, end, speed = 4, listener = object : MediaListener {
                            override fun onFrame(frame: DhavFrame) {
                                val t = frame.time
                                if (t != null && t.isAfter(to)) return
                                recorder.onFrame(frame)
                                if (t != null) _progress.value = (Duration.between(from, t).seconds / total).coerceIn(0f, 1f)
                            }
                            override fun onEnd() { if (cont.isActive) cont.resume(Unit) }
                            override fun onClosed(error: Throwable?) { if (cont.isActive) cont.resume(Unit) }
                        })
                        cont.invokeOnCancellation { s.close() }
                    }
                }
            } catch (e: CancellationException) {
                error = "cancelled"
            } catch (e: Exception) {
                error = e.message ?: "error"
            }
            val ok = recorder.stop()
            MediaSaver.finishVideo(context, video, keep = ok && error == null)
            _progress.value = null
            job = null
            onDone(if (ok && error == null) video.uri else null, error)
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
