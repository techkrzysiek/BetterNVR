package pl.techkrzysiek.betternvr.ui.devices

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import pl.techkrzysiek.betternvr.R
import pl.techkrzysiek.betternvr.data.Device
import pl.techkrzysiek.betternvr.ui.common.shareMedia
import java.io.File

/**
 * One recorder as a QR code: `betternvr://device?n=…&h=…&p=…&a=…&u=…&pw=…` (a = alternative addresses, comma-separated). Read by the in-app scanner, from a
 * picture, or by the phone's own camera app (the link opens BetterNVR). Fully offline (ZXing).
 */
object DeviceQr {
    fun encode(d: Device, withPassword: Boolean): String = Uri.Builder().scheme(SCHEME).authority(HOST)
        .appendQueryParameter("n", d.name)
        .appendQueryParameter("h", d.host)
        .appendQueryParameter("p", d.port.toString())
        .apply { if (d.altHosts.isNotEmpty()) appendQueryParameter("a", d.altHosts.joinToString(",")) }
        .appendQueryParameter("u", d.user)
        .apply { if (withPassword) appendQueryParameter("pw", d.password) }
        .build().toString()

    /** A new recorder (fresh id) from the code's text, or null when it is not a BetterNVR code. */
    fun decode(text: String): Device? {
        val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val host = uri.getQueryParameter("h")?.takeIf { it.isNotBlank() } ?: return null
        return Device(
            name = uri.getQueryParameter("n").orEmpty(), host = host,
            port = uri.getQueryParameter("p")?.toIntOrNull() ?: 37777,
            user = uri.getQueryParameter("u").orEmpty(), password = uri.getQueryParameter("pw").orEmpty(),
            altHosts = uri.getQueryParameter("a").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
    }

    fun bitmap(text: String, size: Int = 768): Bitmap {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size) { if (m[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    /** Finds a QR code in a picture (screenshot, photo); null when there is none. */
    fun readImage(context: Context, uri: Uri): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val source = RGBLuminanceSource(bmp.width, bmp.height, px)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        return runCatching { QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text }.getOrNull()
            ?: runCatching { QRCodeReader().decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hints).text }.getOrNull()
    }

    const val SCHEME = "betternvr"
    const val HOST = "device"
}

/** QR code of one recorder – without the password unless switched on – and a button to send it as a picture. */
@Composable
fun DeviceQrDialog(device: Device, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var withPassword by remember { mutableStateOf(false) }
    val text = DeviceQr.encode(device, withPassword)
    val qr = remember(text) { DeviceQr.bitmap(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device.name.ifBlank { device.host }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    qr.asImageBitmap(), stringResource(R.string.device_qr),
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.Color.White),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.device_qr_password), modifier = Modifier.weight(1f))
                    Switch(checked = withPassword, onCheckedChange = { withPassword = it })
                }
                Text(
                    stringResource(if (withPassword) R.string.device_qr_warning else R.string.device_qr_no_password),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (withPassword) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val dir = File(context.cacheDir, "share").apply { mkdirs() }
                val file = File(dir, "BetterNVR_${device.name.ifBlank { device.host }.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")}.png")
                runCatching {
                    file.outputStream().use { qr.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    shareMedia(context, Uri.fromFile(file), "image/png")
                }
            }) { Text(stringResource(R.string.device_qr_share)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** "Scan QR" / "QR from picture" buttons of the add dialog; [onResult] gets the recorder or null for a foreign code. */
@Composable
fun QrImportButtons(onResult: (Device?) -> Unit) {
    val context = LocalContext.current
    val prompt = stringResource(R.string.device_qr_prompt)
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { onResult(DeviceQr.decode(it)) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { onResult(DeviceQr.readImage(context, it)?.let(DeviceQr::decode)) }
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilledTonalButton(
            onClick = {
                scan.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(prompt)
                        .setBeepEnabled(false).setOrientationLocked(false),
                )
            },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.device_qr_scan), maxLines = 1) }
        androidx.compose.material3.OutlinedButton(
            onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.device_qr_image), maxLines = 1) }
    }
}
