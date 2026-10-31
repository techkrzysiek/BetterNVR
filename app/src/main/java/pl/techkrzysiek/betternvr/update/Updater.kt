package pl.techkrzysiek.betternvr.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.techkrzysiek.betternvr.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long, val page: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Ready(val release: Release, val file: File) : UpdateState
    /** [release] is set when the download failed (the card offers to try again). */
    data class Failed(val problem: Problem, val release: Release? = null) : UpdateState
}

/** Why an update check or download failed, in terms the user can act on. */
sealed interface Problem {
    /** GitHub limits requests per address (shared on mobile networks); [retryAt] = epoch ms when known. */
    data class RateLimited(val retryAt: Long?) : Problem
    data object Offline : Problem
    data object NoRelease : Problem
    data object WrongKey : Problem
    data class Other(val detail: String) : Problem
}

private class UpdateException(val problem: Problem) : Exception(problem.toString())

/**
 * Over-the-air updates from the GitHub releases of [BuildConfig.UPDATE_REPO], built and signed by the
 * repository's release workflow. Only GitHub is contacted: when the app opens, at most once a day (can be
 * switched off), or from the button in settings. The version comes from the redirect of the "latest release"
 * page, not from api.github.com, whose 60 requests an hour per address are easily used up behind a shared
 * (mobile, CGNAT) address; the API is asked only for the release notes, and may refuse. A downloaded APK must
 * carry this app's own signing key before Android's installer is opened (the installer checks it again).
 */
class Updater(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Builds made outside the release workflow have no repository to ask. */
    val enabled get() = BuildConfig.UPDATE_REPO.isNotBlank()
    val repoUrl get() = if (enabled) "https://github.com/${BuildConfig.UPDATE_REPO}" else null

    var autoCheck: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(v) { prefs.edit().putBoolean("autoUpdate", v).apply() }

    suspend fun checkIfDue() {
        if (enabled && autoCheck && System.currentTimeMillis() - prefs.getLong("lastUpdateCheck", 0) > DAY_MS) check()
    }

    suspend fun check() = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        _state.value = UpdateState.Checking
        _state.value = try {
            val latest = latestRelease("https://github.com/${BuildConfig.UPDATE_REPO}")
            prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
            val (repo, tag) = latest ?: (null to null)
            val version = tag?.removePrefix("v")
            if (repo != null && tag != null && version != null && isNewer(version, BuildConfig.VERSION_NAME)) {
                val notes = runCatching {
                    JSONObject(fetch("https://api.github.com/repos/${repo.substringAfter("github.com/")}/releases/tags/$tag")).optString("body")
                }.getOrDefault("")
                // asset name as published by .github/workflows/release.yml
                UpdateState.Available(Release(version, notes, "$repo/releases/download/$tag/BetterNVR-$version.apk", 0, "$repo/releases/tag/$tag"))
            } else UpdateState.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateState.Failed(problemOf(e))
        }
    }

    suspend fun download(release: Release) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Downloading(release, 0f)
        _state.value = try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val file = File(dir, "BetterNVR-${release.version}.apk")
            val conn = open(release.apkUrl)
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (!sameSigner(file)) {
                file.delete()
                throw UpdateException(Problem.WrongKey)
            }
            UpdateState.Ready(release, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpdateState.Failed(problemOf(e), release)
        }
    }

    /** Opens Android's installer; the first time, Android asks to allow installing apps from BetterNVR. */
    fun install(file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true // release assets redirect to GitHub's file storage
        setRequestProperty("User-Agent", "BetterNVR/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept", if (url.contains("api.github.com")) "application/vnd.github+json" else "application/octet-stream")
        if (responseCode !in 200..299) throw httpError(this)
    }

    /** GitHub answers 403 (API, with X-RateLimit-Remaining: 0) or 429 (web, with Retry-After) when it limits an address. */
    private fun httpError(conn: HttpURLConnection): UpdateException {
        val code = conn.responseCode
        if (code == 429 || (code == 403 && conn.getHeaderField("X-RateLimit-Remaining") == "0")) {
            val reset = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.times(1000)
                ?: conn.getHeaderField("Retry-After")?.toLongOrNull()?.let { System.currentTimeMillis() + it * 1000 }
            return UpdateException(Problem.RateLimited(reset))
        }
        if (code == 404) return UpdateException(Problem.NoRelease)
        return UpdateException(Problem.Other("HTTP $code"))
    }

    private fun problemOf(e: Exception): Problem = when (e) {
        is UpdateException -> e.problem
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException,
        is java.net.NoRouteToHostException, is javax.net.ssl.SSLException -> Problem.Offline
        else -> Problem.Other(e.message ?: e.javaClass.simpleName)
    }

    /**
     * Repository address and tag of the newest release, from where ".../releases/latest" redirects to; null when
     * nothing is published yet. A transferred or renamed repository first redirects to its new address – followed,
     * so apps built for the old address keep finding updates.
     */
    private fun latestRelease(repo: String): Pair<String, String>? {
        var url = "$repo/releases/latest"
        repeat(4) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                setRequestProperty("User-Agent", "BetterNVR/${BuildConfig.VERSION_NAME}")
            }
            try {
                val code = conn.responseCode
                if (code !in 300..399) throw httpError(conn)
                val location = URL(URL(url), conn.getHeaderField("Location") ?: return null).toString()
                val tag = location.substringAfter("/releases/tag/", "").substringBefore('?')
                if (tag.isNotBlank()) return location.substringBefore("/releases/tag/") to tag
                if (!location.substringBefore('?').endsWith("/releases/latest")) return null // e.g. to /releases: no release yet
                url = location
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun fetch(url: String): String = open(url).inputStream.use { it.readBytes().decodeToString() }

    /** Refuses only a positively different key; when Android does not report the archive's signer, its installer decides. */
    private fun sameSigner(apk: File): Boolean {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, signingFlags()) ?: return false
        if (archive.packageName != context.packageName) return false
        val theirs = signers(archive)
        val ours = signers(pm.getPackageInfo(context.packageName, signingFlags()))
        return theirs.isEmpty() || ours.isEmpty() || theirs == ours
    }

    @Suppress("DEPRECATION")
    private fun signingFlags() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(p: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) p.signingInfo?.apkContentsSigners else p.signatures
        return certs.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    companion object {
        private const val DAY_MS = 24 * 3600 * 1000L

        /** "0.2.0" > "0.1.10": compared number by number. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
