package pl.techkrzysiek.betternvr.player

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.techkrzysiek.betternvr.data.ChannelRef
import pl.techkrzysiek.betternvr.data.DeviceManager
import pl.techkrzysiek.betternvr.proto.TalkSession

/** Phone microphone → camera speaker. */
class TalkController(private val scope: CoroutineScope, private val manager: DeviceManager, private val ref: ChannelRef) {
    private val _talking = MutableStateFlow(false)
    val talking: StateFlow<Boolean> = _talking.asStateFlow()
    private var job: Job? = null
    @Volatile private var session: TalkSession? = null

    @SuppressLint("MissingPermission") // the UI asks for RECORD_AUDIO before calling start()
    fun start(onError: (String) -> Unit) {
        if (job != null) return
        _talking.value = true
        job = scope.launch(Dispatchers.IO) {
            var rec: AudioRecord? = null
            try {
                val s = manager.client(ref.deviceId).openTalk(ref.channel)
                session = s
                val min = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200))
                rec.startRecording()
                val pcm = ShortArray(320)
                val out = ByteArray(320)
                while (isActive && s.isOpen) {
                    var got = 0
                    while (got < pcm.size) {
                        val n = rec.read(pcm, got, pcm.size - got)
                        if (n <= 0) break
                        got += n
                    }
                    for (i in 0 until got) out[i] = TalkSession.alaw(pcm[i].toInt())
                    if (got == pcm.size) s.send(out)
                }
            } catch (e: Exception) {
                if (isActive) onError(e.message ?: "talk failed")
            } finally {
                rec?.let { runCatching { it.stop() }; it.release() }
                session?.close()
                session = null
                _talking.value = false
                job = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        session?.close()
    }
}
