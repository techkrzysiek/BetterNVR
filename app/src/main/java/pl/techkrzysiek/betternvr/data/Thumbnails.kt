package pl.techkrzysiek.betternvr.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Last picture of every camera, kept in the app's cache: shown instantly on the home screen and as a
 * placeholder while a tile's live stream starts. Refreshed from live tiles, at most once a minute.
 */
class Thumbnails(context: Context) {
    private val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val memory = HashMap<ChannelRef, Bitmap>()
    private val savedAt = HashMap<ChannelRef, Long>()
    private val _version = MutableStateFlow(0)
    /** Bumps whenever a thumbnail changes. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun file(ref: ChannelRef) = File(dir, "${ref.deviceId}_${ref.channel}.jpg")

    fun get(ref: ChannelRef): Bitmap? = synchronized(memory) {
        memory[ref] ?: file(ref).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }?.also { memory[ref] = it }
    }

    fun isFresh(ref: ChannelRef) = synchronized(memory) { System.currentTimeMillis() - (savedAt[ref] ?: 0L) < 60_000 }

    /** How old the stored thumbnail is (Long.MAX_VALUE when there is none). */
    fun ageMs(ref: ChannelRef): Long = synchronized(memory) {
        val t = savedAt[ref] ?: file(ref).takeIf { it.exists() }?.lastModified() ?: return Long.MAX_VALUE
        System.currentTimeMillis() - t
    }

    suspend fun put(ref: ChannelRef, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        synchronized(memory) { memory[ref] = bitmap; savedAt[ref] = System.currentTimeMillis() }
        runCatching { FileOutputStream(file(ref)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        _version.update { it + 1 }
    }

    fun forget(deviceId: String) {
        synchronized(memory) { memory.keys.removeAll { it.deviceId == deviceId } }
        dir.listFiles { f -> f.name.startsWith("${deviceId}_") }?.forEach { it.delete() }
        _version.update { it + 1 }
    }
}
