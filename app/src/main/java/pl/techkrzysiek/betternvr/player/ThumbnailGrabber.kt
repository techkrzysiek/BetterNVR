package pl.techkrzysiek.betternvr.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pl.techkrzysiek.betternvr.data.ChannelRef
import pl.techkrzysiek.betternvr.data.DeviceManager
import pl.techkrzysiek.betternvr.media.Nal
import pl.techkrzysiek.betternvr.proto.DahuaClient
import pl.techkrzysiek.betternvr.proto.DhavFrame
import pl.techkrzysiek.betternvr.proto.MediaListener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.LocalDateTime

/** Fresh thumbnail without showing anything: one keyframe of a live stream, decoded off-screen. */
object ThumbnailGrabber {
    /** A decoded picture and the recorder time it was taken. */
    class Grab(val bitmap: Bitmap, val time: LocalDateTime?)

    suspend fun grab(manager: DeviceManager, ref: ChannelRef, width: Int = 480): Bitmap? = withContext(Dispatchers.IO) {
        val client = runCatching { manager.client(ref.deviceId) }.getOrNull() ?: return@withContext null
        grabFrame(client, ref.channel, width)?.bitmap
    }

    /** [sub] = sub-stream (small, quick); the main stream gives a sharper picture but waits longer for its keyframe. */
    suspend fun grabFrame(client: DahuaClient, channel: Int, width: Int, sub: Boolean = true, timeoutMs: Long = 6000): Grab? = withContext(Dispatchers.IO) {
        val key = CompletableDeferred<DhavFrame>()
        val stream = runCatching {
            client.openLive(channel, sub, object : MediaListener {
                override fun onFrame(frame: DhavFrame) { if (frame.isKey && frame.videoCodec != null) key.complete(frame) }
            })
        }.getOrNull() ?: return@withContext null
        try {
            val frame = withTimeoutOrNull(timeoutMs) { key.await() } ?: return@withContext null
            decode(frame, width)?.let { Grab(it, frame.time) }
        } finally {
            stream.close()
        }
    }

    /** Decodes one keyframe into CPU memory (no surface): codec.getOutputImage gives readable YUV planes. */
    private fun decode(f: DhavFrame, width: Int): Bitmap? {
        val vc = f.videoCodec ?: return null
        val csd = Nal.codecConfig(vc, f.payload) ?: return null
        val w = if (f.width > 0) f.width else 704
        val h = if (f.height > 0) f.height else 576
        val codec = MediaCodec.createDecoderByType(vc.mime)
        try {
            val fmt = MediaFormat.createVideoFormat(vc.mime, w, h).apply {
                csd.forEachIndexed { i, b -> setByteBuffer("csd-$i", ByteBuffer.wrap(b)) }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(w * h, 1024 * 1024))
                setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = false
            var eos = false
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                if (!eos) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        if (!fed) {
                            codec.getInputBuffer(idx)!!.apply { clear(); put(f.payload) }
                            codec.queueInputBuffer(idx, 0, f.payload.size, 0, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                            fed = true
                        } else {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out >= 0) {
                    val bmp = if (info.size > 0) codec.getOutputImage(out)?.use { toBitmap(it, width) } else null
                    codec.releaseOutputBuffer(out, false)
                    if (bmp != null) return bmp
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                }
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /**
     * YUV planes → NV21 → bitmap. Big frames (4K main stream) are thinned out while copying, down to about
     * twice the wanted width, so a 4K keyframe never becomes a 33 MB bitmap.
     */
    private fun toBitmap(img: Image, width: Int): Bitmap? {
        val crop = img.cropRect
        val step = maxOf(1, crop.width() / (width * 2))
        val w = (crop.width() / step) and 1.inv()
        val h = (crop.height() / step) and 1.inv()
        val nv21 = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]
        var pos = 0
        for (row in 0 until h) {
            val buf = y.buffer
            val base = (row * step + crop.top) * y.rowStride + crop.left * y.pixelStride
            for (col in 0 until w) nv21[pos++] = buf.get(base + col * step * y.pixelStride)
        }
        val u = img.planes[1]
        val v = img.planes[2]
        for (row in 0 until h / 2) {
            val ub = (row * step + crop.top / 2) * u.rowStride + (crop.left / 2) * u.pixelStride
            val vb = (row * step + crop.top / 2) * v.rowStride + (crop.left / 2) * v.pixelStride
            for (col in 0 until w / 2) {
                nv21[pos++] = v.buffer.get(vb + col * step * v.pixelStride)
                nv21[pos++] = u.buffer.get(ub + col * step * u.pixelStride)
            }
        }
        val jpeg = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 90, jpeg)
        val full = BitmapFactory.decodeByteArray(jpeg.toByteArray(), 0, jpeg.size()) ?: return null
        // D1 / CIF sub-streams squeeze a 16:9 camera into 704x576: show them as 16:9; anything else keeps its shape
        val squeezed = crop.width() in SQUEEZED_WIDTHS && crop.height() in SQUEEZED_HEIGHTS
        val outH = if (squeezed) width * 9 / 16 else (width.toLong() * crop.height() / crop.width()).toInt()
        return Bitmap.createScaledBitmap(full, width, outH.coerceAtLeast(1), true)
    }

    private val SQUEEZED_WIDTHS = setOf(352, 704, 720)
    private val SQUEEZED_HEIGHTS = setOf(240, 288, 480, 576)
}
