package pl.techkrzysiek.betternvr.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import pl.techkrzysiek.betternvr.data.ChannelRef
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.techkrzysiek.betternvr.R
import pl.techkrzysiek.betternvr.data.Device
import pl.techkrzysiek.betternvr.ui.common.app

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(onOpen: (String) -> Unit, onMultiView: (String) -> Unit, onOpenCamera: (String, Int) -> Unit) {
    val app = app()
    val devices by app.store.devices.collectAsState()
    val infos by app.manager.infos.collectAsState()
    val errors by app.manager.errors.collectAsState()
    val noCamera by app.manager.noCamera.collectAsState()
    val thumbVersion by app.thumbnails.version.collectAsState()
    var editing by remember { mutableStateOf<Device?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Device?>(null) }
    var qrFor by remember { mutableStateOf<Device?>(null) }
    var settings by remember { mutableStateOf(false) }
    // New release on GitHub: asked at most once a day when the list opens (can be switched off in settings).
    LaunchedEffect(Unit) { app.updater.checkIfDue() }
    // A recorder QR code opened from outside (phone camera app → betternvr:// link).
    val activity = androidx.compose.ui.platform.LocalContext.current as? pl.techkrzysiek.betternvr.MainActivity
    val incoming by (activity?.importDevice ?: remember { kotlinx.coroutines.flow.MutableStateFlow<Device?>(null) }).collectAsState()

    // Log in to every recorder in the background so the list shows model / status right away.
    devices.forEach { d -> LaunchedEffect(d) { runCatching { app.manager.client(d.id) } } }
    // Missing or stale thumbnails are fetched quietly (one keyframe of the sub-stream each).
    devices.forEach { d ->
        val info = infos[d.id]
        LaunchedEffect(d.id, info != null) {
            if (info == null) return@LaunchedEffect
            for (ch in 0 until info.channelCount) {
                val ref = ChannelRef(d.id, ch)
                if (!info.canLive(ch) || ref in app.manager.noCamera.value || app.thumbnails.ageMs(ref) < 15 * 60_000) continue
                pl.techkrzysiek.betternvr.player.ThumbnailGrabber.grab(app.manager, ref)?.let { app.thumbnails.put(ref, it) }
            }
        }
    }
    // Drag to reorder: long-press a card and move it.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val currentDevices by androidx.compose.runtime.rememberUpdatedState(devices)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                        val context = androidx.compose.ui.platform.LocalContext.current
                        val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
                        version?.let { Text(stringResource(R.string.version, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                actions = { IconButton(onClick = { settings = true }) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.device_add)) })
        },
    ) { padding ->
        if (devices.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
                UpdateCard()
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.devices_empty), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "update-card") { UpdateCard() }
                items(devices, key = { it.id }) { d ->
                    val info = infos[d.id]
                    val err = errors[d.id]
                    var menu by remember { mutableStateOf(false) }
                    val isDragging = dragging == d.id
                    ElevatedCard(
                        onClick = { onOpen(d.id) },
                        modifier = Modifier.fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) dragOffset else 0f
                                if (isDragging) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 16f }
                            }
                            .pointerInput(d.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { dragging = d.id; dragOffset = 0f },
                                    onDragEnd = { dragging = null; dragOffset = 0f },
                                    onDragCancel = { dragging = null; dragOffset = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val items = listState.layoutInfo.visibleItemsInfo
                                        val cur = items.firstOrNull { it.key == d.id } ?: return@detectDragGesturesAfterLongPress
                                        val center = cur.offset + cur.size / 2f + dragOffset
                                        val target = items.firstOrNull { it.key != d.id && it.key is String && center > it.offset && center < it.offset + it.size }
                                        val to = target?.let { t -> currentDevices.indexOfFirst { it.id == t.key } } ?: -1
                                        if (target != null && to >= 0) {
                                            app.store.moveTo(d.id, to)
                                            dragOffset -= (target.offset - cur.offset)
                                        }
                                    },
                                )
                            },
                    ) {
                        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(
                                when { info != null -> Color(0xFF4CAF50); err != null -> MaterialTheme.colorScheme.error; else -> Color.Gray },
                            ))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(d.name.ifBlank { d.host }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    when {
                                        info != null -> stringResource(R.string.device_connected, info.type.ifBlank { "NVR" }, (0 until info.channelCount).count { info.canLive(it) || info.canPlay(it) })
                                        err != null -> err
                                        else -> stringResource(R.string.device_testing)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (err != null && info == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { onMultiView(d.id) }, enabled = info != null) {
                                Icon(Icons.Filled.PlayCircle, stringResource(R.string.multiview), tint = MaterialTheme.colorScheme.primary)
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.device_edit)) }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                        onClick = { menu = false; editing = d })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.device_qr)) }, leadingIcon = { Icon(Icons.Filled.QrCode2, null) },
                                        onClick = { menu = false; qrFor = d })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.device_delete)) }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                        onClick = { menu = false; deleting = d })
                                }
                            }
                        }
                        // camera thumbnails (last picture seen), tap to open that camera
                        val channels = info?.let { i -> (0 until i.channelCount).filter { (i.canLive(it) || i.canPlay(it)) && ChannelRef(d.id, it) !in noCamera } }.orEmpty()
                        if (channels.isNotEmpty()) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(channels, key = { it }) { ch ->
                                    val ref = ChannelRef(d.id, ch)
                                    val thumb = remember(thumbVersion, ref) { app.thumbnails.get(ref)?.asImageBitmap() }
                                    Column(Modifier.width(132.dp).clickable { onOpenCamera(d.id, ch) }) {
                                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1C222A)), contentAlignment = Alignment.Center) {
                                            if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                            else Icon(Icons.Filled.Videocam, null, tint = Color.Gray)
                                        }
                                        Text(
                                            info?.channelNames?.getOrNull(ch)?.takeIf { it.isNotBlank() } ?: stringResource(R.string.channel_n, ch + 1),
                                            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                                        )
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }

    if (adding || editing != null || incoming != null) {
        DeviceEditDialog(
            initial = editing,
            prefill = incoming,
            onDismiss = { adding = false; editing = null; activity?.importDevice?.value = null },
            onSave = { d ->
                app.store.save(d)
                app.manager.disconnect(d.id)
                adding = false; editing = null; activity?.importDevice?.value = null
            },
        )
    }
    qrFor?.let { d -> DeviceQrDialog(d, onDismiss = { qrFor = null }) }
    if (settings) SettingsDialog(onDismiss = { settings = false })
    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.device_delete_confirm, d.name.ifBlank { d.host })) },
            confirmButton = {
                TextButton(onClick = { app.manager.disconnect(d.id); app.store.delete(d.id); app.thumbnails.forget(d.id); deleting = null }) {
                    Text(stringResource(R.string.device_delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DeviceEditDialog(initial: Device?, prefill: Device? = null, onDismiss: () -> Unit, onSave: (Device) -> Unit) {
    val app = app()
    val scope = rememberCoroutineScope()
    val start = initial ?: prefill
    var name by remember { mutableStateOf(start?.name ?: "") }
    var host by remember { mutableStateOf(start?.host ?: "") }
    var port by remember { mutableStateOf((start?.port ?: 37777).toString()) }
    var user by remember { mutableStateOf(start?.user ?: "admin") }
    var password by remember { mutableStateOf(start?.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val filledFromQr = stringResource(R.string.device_qr_imported)
    val notOurQr = stringResource(R.string.device_qr_invalid)
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(if (prefill != null) true to filledFromQr else null) }
    val okFormat = stringResource(R.string.device_test_ok)

    fun build() = Device(
        id = initial?.id ?: java.util.UUID.randomUUID().toString(),
        name = name.trim(), host = host.trim(), port = port.toIntOrNull() ?: 37777, user = user.trim(), password = password,
    )
    val valid = host.isNotBlank() && user.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.device_add else R.string.device_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (initial == null) QrImportButtons { d ->
                    if (d == null) result = false to notOurQr else {
                        name = d.name; host = d.host; port = d.port.toString(); user = d.user
                        if (d.password.isNotEmpty()) password = d.password
                        result = true to filledFromQr
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.device_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    host, { host = it }, label = { Text(stringResource(R.string.device_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.device_port)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.device_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it }, label = { Text(stringResource(R.string.device_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                        }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = valid && !testing,
                        onClick = {
                            testing = true; result = null
                            scope.launch {
                                result = try {
                                    val info = app.manager.test(build())
                                    true to String.format(okFormat, info.type, info.version, info.channelCount)
                                } catch (e: Exception) {
                                    false to (e.message ?: e.javaClass.simpleName)
                                }
                                testing = false
                            }
                        },
                    ) { Text(stringResource(if (testing) R.string.device_testing else R.string.device_test)) }
                    if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                result?.let { (ok, msg) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null,
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Text(msg, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSave(build()) }) { Text(stringResource(R.string.device_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
