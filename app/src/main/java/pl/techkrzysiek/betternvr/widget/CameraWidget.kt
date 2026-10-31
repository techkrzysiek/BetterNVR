package pl.techkrzysiek.betternvr.widget

import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import pl.techkrzysiek.betternvr.App
import pl.techkrzysiek.betternvr.MainActivity
import pl.techkrzysiek.betternvr.R
import pl.techkrzysiek.betternvr.data.ChannelRef
import pl.techkrzysiek.betternvr.player.ThumbnailGrabber
import pl.techkrzysiek.betternvr.proto.DahuaClient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Home-screen widget with the latest picture of one camera. Widgets cannot play video, so it shows a still
 * frame, refreshed without any background service: a non-waking alarm, which simply waits while the phone
 * sleeps, checks every few seconds while the screen is on and fetches a frame only when the phone is unlocked,
 * the home screen is in front (known when usage access is granted) and the picture is older than the chosen
 * interval. Each fetch is a short separate session: log in, one keyframe of the main stream, log out.
 */
class CameraWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { CameraWidgets.render(context, it) }
        CameraWidgets.schedule(context, 1_000)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { CameraWidgets.remove(context, it) }
    }

    override fun onDisabled(context: Context) = CameraWidgets.cancel(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            CameraWidgets.ACTION_REFRESH -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val ids = if (id != AppWidgetManager.INVALID_APPWIDGET_ID) listOf(id) else CameraWidgets.ids(context).toList()
                runAsync { CameraWidgets.refresh(context, ids) }
            }
            CameraWidgets.ACTION_TICK -> runAsync { CameraWidgets.tick(context) }
        }
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // main-stream keyframes can be a few seconds apart; a broadcast may run up to a minute
                withTimeoutOrNull(25_000) { block() }
            } finally {
                pending.finish()
            }
        }
    }
}

data class WidgetConfig(val ref: ChannelRef, val name: String)

object CameraWidgets {
    const val ACTION_REFRESH = "pl.techkrzysiek.betternvr.widget.REFRESH"
    const val ACTION_TICK = "pl.techkrzysiek.betternvr.widget.TICK"
    val INTERVALS = listOf(60_000L, 5 * 60_000L, 15 * 60_000L)
    private const val TAG = "CameraWidget"
    private const val WIDTH = 640
    private const val CHECK_MS = 10_000L
    private const val LOCKED_CHECK_MS = 5_000L
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY_TIME = DateTimeFormatter.ofPattern("d.MM HH:mm")

    /** Last fetch failed, per camera (shown on the widget until the next good picture). */
    private val failed = HashSet<ChannelRef>()

    private fun prefs(context: Context) = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun ids(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, CameraWidgetProvider::class.java))

    fun config(context: Context, id: Int): WidgetConfig? = prefs(context).getString("w$id", null)?.let { s ->
        runCatching { JSONObject(s).let { WidgetConfig(ChannelRef.fromJson(it), it.optString("n")) } }.getOrNull()
    }

    fun save(context: Context, id: Int, config: WidgetConfig) {
        prefs(context).edit().putString("w$id", config.ref.toJson().put("n", config.name).toString()).apply()
    }

    fun remove(context: Context, id: Int) = prefs(context).edit().remove("w$id").apply()

    fun interval(context: Context): Long = prefs(context).getLong("interval", INTERVALS.first())

    fun setInterval(context: Context, ms: Long) = prefs(context).edit().putLong("interval", ms).apply()

    /** Fetches new pictures for the given widgets now (refresh button, a newly added widget). */
    fun requestRefresh(context: Context, id: Int) {
        context.sendBroadcast(Intent(context, CameraWidgetProvider::class.java).setAction(ACTION_REFRESH).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
    }

    // ---- scheduling ----

    /**
     * Non-waking alarm: while the phone sleeps it is not delivered at all, it waits for the next wake-up.
     * So with the screen off nothing runs, and turning the screen on brings an overdue check at once.
     */
    fun schedule(context: Context, delayMs: Long) {
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, tickIntent(context))
    }

    fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(tickIntent(context))

    private fun tickIntent(context: Context) = PendingIntent.getBroadcast(
        context, Int.MAX_VALUE, Intent(context, CameraWidgetProvider::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    suspend fun tick(context: Context) {
        val ids = ids(context)
        if (ids.isEmpty()) return
        var next = CHECK_MS
        try {
            val power = context.getSystemService(PowerManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            when {
                !power.isInteractive -> next = interval(context)
                keyguard.isKeyguardLocked -> next = LOCKED_CHECK_MS
                homeInFront(context) == false -> Unit
                else -> {
                    val app = context.applicationContext as App
                    val due = interval(context) - 3_000
                    val stale = ids.filter { id -> config(context, id)?.let { app.thumbnails.ageMs(it.ref) >= due } ?: false }
                    if (stale.isNotEmpty()) refresh(context, stale)
                }
            }
        } finally {
            schedule(context, next)
        }
    }

    // ---- what is in front ----

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** true / false when known (usage access granted), null when it cannot be told. */
    private fun homeInFront(context: Context): Boolean? {
        if (!hasUsageAccess(context)) return null
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val events = usage.queryEvents(end - 3_600_000L, end) ?: return null
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.getNextEvent(e)) {
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName
        }
        last ?: return null
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName ?: return null
        return last == home
    }

    // ---- fetching ----

    suspend fun refresh(context: Context, ids: List<Int>) {
        val app = context.applicationContext as App
        val configs = ids.mapNotNull { id -> config(context, id)?.let { id to it } }
        configs.forEach { (id, _) -> setLoading(context, id, true) }
        try {
            configs.groupBy { it.second.ref.deviceId }.forEach { (deviceId, list) ->
                val device = app.store.device(deviceId) ?: return@forEach
                val refs = list.map { it.second.ref }.distinct()
                runCatching {
                    DahuaClient(device.host, device.port, device.user, device.password).use { client ->
                        client.connect()
                        refs.forEach { ref ->
                            // main stream (HD) like the camera view; the sub-stream only if that gives nothing
                            val grab = ThumbnailGrabber.grabFrame(client, ref.channel, WIDTH, sub = false, timeoutMs = 8_000)
                                ?: ThumbnailGrabber.grabFrame(client, ref.channel, WIDTH)
                            if (grab == null) synchronized(failed) { failed += ref } else {
                                app.thumbnails.put(ref, grab.bitmap)
                                prefs(context).edit().putString("t_${ref.deviceId}_${ref.channel}", (grab.time ?: LocalDateTime.now()).toString()).apply()
                                synchronized(failed) { failed -= ref }
                            }
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "refresh ${device.host} failed", it)
                    synchronized(failed) { failed += refs }
                }
            }
        } finally {
            configs.forEach { (id, _) -> render(context, id) }
        }
    }

    // ---- drawing ----

    fun render(context: Context, id: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_camera)
        val config = config(context, id)
        views.setViewVisibility(R.id.widget_progress, View.GONE)
        views.setViewVisibility(R.id.widget_refresh, View.VISIBLE)
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshIntent(context, id))
        if (config == null) {
            views.setTextViewText(R.id.widget_name, context.getString(R.string.widget_not_configured))
            views.setTextViewText(R.id.widget_time, "")
            views.setOnClickPendingIntent(R.id.widget_image, configureIntent(context, id))
        } else {
            val app = context.applicationContext as App
            views.setTextViewText(R.id.widget_name, config.name)
            val picture = app.thumbnails.get(config.ref)
            if (picture != null) {
                views.setImageViewBitmap(R.id.widget_image, forWidget(picture))
                views.setViewVisibility(R.id.widget_empty, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            }
            val error = synchronized(failed) { config.ref in failed }
            val time = timeLabel(context, config.ref)
            views.setTextViewText(
                R.id.widget_time,
                if (error) listOfNotNull(time, context.getString(R.string.widget_offline)).joinToString(" · ") else time.orEmpty(),
            )
            views.setOnClickPendingIntent(R.id.widget_image, openIntent(context, id, config.ref))
        }
        AppWidgetManager.getInstance(context).updateAppWidget(id, views)
    }

    private fun setLoading(context: Context, id: Int, on: Boolean) {
        val views = RemoteViews(context.packageName, R.layout.widget_camera)
        views.setViewVisibility(R.id.widget_progress, if (on) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_refresh, if (on) View.GONE else View.VISIBLE)
        AppWidgetManager.getInstance(context).partiallyUpdateAppWidget(id, views)
    }

    /** Recorder time of the picture: just the hour today, with the date otherwise. */
    private fun timeLabel(context: Context, ref: ChannelRef): String? {
        val t = prefs(context).getString("t_${ref.deviceId}_${ref.channel}", null)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
        return t.format(if (t.toLocalDate() == LocalDate.now()) TIME else DAY_TIME)
    }

    /** Widget pictures travel to the launcher through a binder call: keep them small (16-bit colour). */
    private fun forWidget(b: Bitmap): Bitmap {
        val scaled = if (b.width > WIDTH) Bitmap.createScaledBitmap(b, WIDTH, b.height * WIDTH / b.width, true) else b
        return scaled.copy(Bitmap.Config.RGB_565, false) ?: scaled
    }

    private fun openIntent(context: Context, id: Int, ref: ChannelRef): PendingIntent = PendingIntent.getActivity(
        context, id,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DEVICE, ref.deviceId)
            .putExtra(MainActivity.EXTRA_CHANNEL, ref.channel)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun refreshIntent(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context, id,
        Intent(context, CameraWidgetProvider::class.java).setAction(ACTION_REFRESH).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun configureIntent(context: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        context, id,
        Intent(context, WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
