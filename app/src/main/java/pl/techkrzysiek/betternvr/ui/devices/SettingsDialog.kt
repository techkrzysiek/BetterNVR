package pl.techkrzysiek.betternvr.ui.devices

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.techkrzysiek.betternvr.BuildConfig
import pl.techkrzysiek.betternvr.R
import pl.techkrzysiek.betternvr.data.Language
import pl.techkrzysiek.betternvr.ui.common.app
import pl.techkrzysiek.betternvr.update.Problem
import pl.techkrzysiek.betternvr.update.UpdateState

/** Language, updates and what this app is. */
@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val updater = app().updater
    val scope = rememberCoroutineScope()
    val state by updater.state.collectAsState()
    var language by remember { mutableStateOf(Language.current(context)) }
    var autoCheck by remember { mutableStateOf(updater.autoCheck) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleSmall)
                Language.TAGS.forEach { tag ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (tag != language) { language = tag; (context as? Activity)?.let { Language.set(it, tag) } }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == tag, onClick = null, modifier = Modifier.padding(8.dp))
                        Text(
                            when (tag) {
                                "pl" -> "Polski"
                                "en" -> "English"
                                else -> stringResource(R.string.language_system)
                            },
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(stringResource(R.string.settings_updates), style = MaterialTheme.typography.titleSmall)
                if (!updater.enabled) {
                    Text(stringResource(R.string.update_not_in_build), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.update_auto), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = autoCheck, onCheckedChange = { autoCheck = it; updater.autoCheck = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { scope.launch { updater.check() } }, enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading) {
                            Text(stringResource(R.string.update_check_now))
                        }
                        if (state is UpdateState.Checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    when (val s = state) {
                        UpdateState.UpToDate -> Text(stringResource(R.string.update_up_to_date), style = MaterialTheme.typography.bodySmall)
                        is UpdateState.Failed -> Text(problemText(s.problem), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Ready ->
                            Text(stringResource(R.string.update_see_list), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        else -> Unit
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("BetterNVR ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                updater.repoUrl?.let { url ->
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text(stringResource(R.string.about_source)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun problemText(p: Problem): String = when (p) {
    is Problem.RateLimited -> p.retryAt?.let {
        stringResource(R.string.update_rate_limited_until, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)))
    } ?: stringResource(R.string.update_rate_limited)
    Problem.Offline -> stringResource(R.string.update_offline)
    Problem.NoRelease -> stringResource(R.string.update_no_release)
    Problem.WrongKey -> stringResource(R.string.update_wrong_key)
    is Problem.Other -> stringResource(R.string.update_failed, p.detail)
}

/** Top of the recorder list when a newer release exists: what's new, download, install. */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val updater = app().updater
    val scope = rememberCoroutineScope()
    val state by updater.state.collectAsState()
    val release = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.Ready -> s.release
        is UpdateState.Failed -> s.release ?: return // a failed check shows in settings only
        else -> return
    }
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.update_available, release.version), style = MaterialTheme.typography.titleMedium)
            if (release.notes.isNotBlank()) {
                Text(release.notes.trim(), style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            when (val s = state) {
                is UpdateState.Downloading -> LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                is UpdateState.Ready -> FilledTonalButton(onClick = { updater.install(s.file) }) { Text(stringResource(R.string.update_install)) }
                is UpdateState.Failed -> {
                    Text(problemText(s.problem), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(onClick = { scope.launch { updater.download(release) } }) { Text(stringResource(R.string.update_retry)) }
                }
                else -> FilledTonalButton(onClick = { scope.launch { updater.download(release) } }) { Text(stringResource(R.string.update_download)) }
            }
        }
    }
}
