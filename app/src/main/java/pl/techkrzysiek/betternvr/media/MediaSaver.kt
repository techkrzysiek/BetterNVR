package pl.techkrzysiek.betternvr.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/** Saves snapshots to Pictures/BetterNVR and clips to Movies/BetterNVR. */
object MediaSaver {
    private const val FOLDER = "BetterNVR"

    fun saveJpeg(context: Context, bitmap: Bitmap, name: String): Uri? {
        val file = "$name.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER).apply { mkdirs() }
            val f = File(dir, file)
            FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
            Uri.fromFile(f)
        }
    }

    class PendingVideo(val uri: Uri, val pfd: ParcelFileDescriptor, internal val legacyFile: File?)

    fun createVideo(context: Context, name: String): PendingVideo? {
        val file = "$name.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return null
            PendingVideo(uri, pfd, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER).apply { mkdirs() }
            val f = File(dir, file)
            PendingVideo(Uri.fromFile(f), ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE), f)
        }
    }

    fun finishVideo(context: Context, v: PendingVideo, keep: Boolean) {
        runCatching { v.pfd.close() }
        if (!keep) {
            if (v.legacyFile != null) v.legacyFile.delete() else runCatching { context.contentResolver.delete(v.uri, null, null) }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(v.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } else if (v.legacyFile != null) {
            MediaScannerConnection.scanFile(context, arrayOf(v.legacyFile.absolutePath), arrayOf("video/mp4"), null)
        }
    }
}
