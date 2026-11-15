@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.albumclean.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albumclean.app.*
import com.albumclean.app.data.*
import com.albumclean.app.updates.PROJECT_URL
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

private val Light = lightColorScheme(
    primary = Color(0xFF4E6328), onPrimary = Color.White,
    primaryContainer = Color(0xFFD7ECA5), onPrimaryContainer = Color(0xFF293B12),
    background = Color(0xFFF5F5EF), onBackground = Color(0xFF22271F),
    surface = Color(0xFFFEFEFA), onSurface = Color(0xFF22271F),
    surfaceVariant = Color(0xFFEAECE3), onSurfaceVariant = Color(0xFF74796C),
    error = Color(0xFFAD4940), errorContainer = Color(0xFFF8E5DE), onErrorContainer = Color(0xFF9E3F35),
    outline = Color(0xFFD4D8CC),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFD0E995), onPrimary = Color(0xFF26371A),
    primaryContainer = Color(0xFF344329), onPrimaryContainer = Color(0xFFD0E995),
    background = Color(0xFF141713), onBackground = Color(0xFFF0F2E9),
    surface = Color(0xFF20251F), onSurface = Color(0xFFF0F2E9),
    surfaceVariant = Color(0xFF2C322A), onSurfaceVariant = Color(0xFFA0AA98),
    error = Color(0xFFFFADA1), errorContainer = Color(0xFF442B27), onErrorContainer = Color(0xFFFFADA1),
    outline = Color(0xFF414B3B),
)

@Composable
fun CleanApp(model: PhotoViewModel, onPermission: () -> Unit, onSettings: () -> Unit, onShare: (Photo) -> Unit, onDelete: (List<Photo>) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val dark = state.theme == ThemeMode.DARK || state.theme == ThemeMode.SYSTEM && systemDark
    val colors = if (dark) Dark else Light
    val activity = LocalActivity.current
    var route by rememberSaveable { mutableStateOf("home") }
    var settings by rememberSaveable { mutableStateOf(false) }
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    fun openProject(suffix: String = "") { runCatching { uriHandler.openUri(PROJECT_URL + suffix) }.onFailure { model.notify("无法打开链接，请检查是否安装了浏览器。") } }
    val snackbar = remember { SnackbarHostState() }
    val detail = detailKey?.let { key -> state.photos.find { it.key == key } ?: state.pending.find { it.key == key } }
    LaunchedEffect(model) { model.messages.collect { snackbar.showSnackbar(it, duration = SnackbarDuration.Short) } }
    SideEffect {
        activity?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = !dark && detail == null
                isAppearanceLightNavigationBars = !dark && detail == null
            }
        }
    }
    BackHandler(detailKey != null || route != "home") { if (detailKey != null) detailKey = null else route = "home" }
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().semantics { if (detail != null) hideFromAccessibility() }) {
                AnimatedContent(targetState = route, transitionSpec = {
                    (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { if (targetState == "pending") it / 5 else -it / 5 }) togetherWith fadeOut(tween(120))
                }, label = "page") { page ->
                    if (page == "pending") PendingScreen(state, onBack = { route = "home" }, onRestore = model::restore,
                        onOpen = { detailKey = it.key }, onDelete = { confirmDelete = true }, onPermission = onPermission)
                    else HomeScreen(state, onSettings = { settings = true }, onPermission = onPermission, onSystemSettings = onSettings,
                        onRefresh = { model.refresh() }, onUndo = model::undo, onDecide = model::decide,
                        onOpen = { detailKey = it.key }, onShare = onShare, onPending = { route = "pending" })
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
            if (detail != null) DetailScreen(detail, onClose = { detailKey = null }, onShare = { onShare(detail) })
        }
        if (settings) ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = colors.surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 30.dp)) {
                Text("按你的习惯整理", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                Label("外观模式")
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        val selected = state.theme == mode
                        Surface(onClick = { model.theme(mode) }, modifier = Modifier.weight(1f).testTag("theme_${mode.name}"),
                            shape = RoundedCornerShape(16.dp), color = if (selected) colors.primaryContainer else colors.surfaceVariant) {
                            Column(Modifier.padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Glyph(if (mode == ThemeMode.DARK) "moon" else if (mode == ThemeMode.LIGHT) "sun" else "system", color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
                                Text(mode.label, Modifier.padding(top = 9.dp), fontSize = 13.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("照片访问", fontWeight = FontWeight.SemiBold)
                Text(when (state.access) { PhotoAccess.FULL -> "已允许访问全部照片"; PhotoAccess.PARTIAL -> "目前仅能整理你授权的照片"; else -> "尚未授权访问照片" }, color = colors.onSurfaceVariant, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPermission) { Text("调整照片权限") }
                    TextButton(onClick = onSettings) { Text("打开系统设置") }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.outline.copy(alpha = .5f))
                TextButton(enabled = state.kept > 0 && !state.busy, onClick = { settings = false; confirmReset = true }) { Glyph("refresh"); Spacer(Modifier.width(10.dp)); Text("重新整理已保留的照片") }
                Spacer(Modifier.height(18.dp))
                UpdateCard(model.updater)
                Text("关于拾光", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
                Text("Glimpse · ${BuildConfig.VERSION_NAME} · MIT License", fontSize = 12.sp, color = colors.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { openProject() }, modifier = Modifier.testTag("github_project")) { Text("GitHub 项目", fontSize = 12.sp) }
                    TextButton(onClick = { openProject("/blob/main/LICENSE") }) { Text("开源许可", fontSize = 12.sp) }
                    TextButton(onClick = { showPrivacy = true }) { Text("隐私说明", fontSize = 12.sp) }
                }
                Text("照片与整理记录仅保存在本机。\n仅检查和下载更新时连接 GitHub。", color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (showPrivacy) AlertDialog(onDismissRequest = { showPrivacy = false }, title = { Text("隐私说明") },
            text = { Text("照片直接从手机相册读取，整理记录和外观设置保存在应用私有存储。拾光不会上传照片、照片信息或整理记录，没有账号、广告和统计服务。\n\n只有你点击检查或下载更新时，应用才会连接 GitHub 获取版本信息和安装包；GitHub 可接收到正常网络请求中的 IP 地址与请求信息。\n\n分享照片由安卓系统交给你选择的应用。安装更新需要你的操作和系统确认，应用不会静默安装。") },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("知道了") } })
        if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("重新整理已保留照片？") },
            text = { Text("清除 ${state.kept} 张照片的保留记录，它们会重新加入随机队列。待删除清单会继续保留。") },
            confirmButton = { TextButton(onClick = { confirmReset = false; model.resetKept() }) { Text("重新整理") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })
        if (confirmDelete) {
            val accessible = state.photos.mapTo(HashSet()) { it.key }
            val deletable = state.pending.filter { it.key in accessible }
            AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("确认删除 ${deletable.size} 张照片？") },
                text = { Text("将永久删除这些原始照片，共 ${formatBytes(deletable.sumOf { it.bytes })}。系统会再次请你确认，删除完成后无法在本应用中撤销。" +
                    if (deletable.size < state.pending.size) "\n另有 ${state.pending.size - deletable.size} 张暂时无法访问，将继续留在清单中。" else "") },
                confirmButton = { TextButton(enabled = deletable.isNotEmpty(), onClick = { confirmDelete = false; onDelete(deletable) }) { Text("继续删除", color = colors.error) } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("再检查一下") } })
        }
    }
}

@Composable
private fun HomeScreen(state: CleanState, onSettings: () -> Unit, onPermission: () -> Unit, onSystemSettings: () -> Unit,
    onRefresh: () -> Unit, onUndo: () -> Unit, onDecide: (Choice) -> Unit, onOpen: (Photo) -> Unit, onShare: (Photo) -> Unit, onPending: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(13.dp), color = colors.primaryContainer, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) { Glyph("photos", color = colors.onPrimaryContainer) }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("拾光", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.6).sp)
                Text("随机一张，轻松取舍", fontSize = 11.sp, color = colors.onSurfaceVariant, letterSpacing = 1.sp)
            }
            IconControl("外观与设置", "sun", onSettings, Modifier.testTag("settings"))
        }
        when {
            state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.access == PhotoAccess.NONE -> Welcome(Modifier.weight(1f), onPermission, onSystemSettings)
            state.error != null -> EmptyState(Modifier.weight(1f), "照片读取遇到问题", state.error, "重新读取", onRefresh)
            state.photos.isEmpty() -> EmptyState(Modifier.weight(1f), "还没有可整理的照片", if (state.access == PhotoAccess.PARTIAL) "你可以再授权一些照片，开始这一轮整理。" else "拍下新回忆后，回来给相册留点空间。", "调整照片权限", onPermission)
            else -> {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("待整理", state.deck.size, colors.onSurface)
                    Stat("已保留", state.kept, colors.primary)
                    Stat("待删除", state.pending.size, colors.error)
                }
                val processed = (state.photos.size - state.deck.size).coerceAtLeast(0)
                val progress by animateFloatAsState(processed.toFloat() / state.photos.size.coerceAtLeast(1), label = "progress")
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 8.dp).height(3.dp), color = colors.primary, trackColor = colors.surfaceVariant, drawStopIndicator = {})
                if (state.access == PhotoAccess.PARTIAL) {
                    Surface(onClick = onPermission, shape = RoundedCornerShape(10.dp), color = colors.primaryContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Text("正在整理已授权照片  ·  轻触添加更多", Modifier.padding(10.dp), fontSize = 11.sp, color = colors.onPrimaryContainer)
                    }
                }
                val photo = state.current
                if (photo == null) {
                    EmptyState(Modifier.weight(1f), "这一轮，整理好了", "每一次取舍，都在为新回忆留位置。\n待删除照片还可以在清单中检查和恢复。", "刷新相册", onRefresh)
                    ActionBar(null, state, onUndo, onDecide, {})
                } else {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        if (maxWidth > 500.dp && maxHeight < 400.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                SwipeCard(photo, !state.busy, Modifier.weight(1.15f).fillMaxHeight(), onDecide, { onOpen(photo) })
                                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                                    PhotoMetadata(photo)
                                    Text("← / ↓ 待删除     ↑ / → 保留", Modifier.fillMaxWidth().padding(top = 15.dp), color = colors.onSurfaceVariant, fontSize = 12.sp)
                                    ActionBar(photo, state, onUndo, onDecide, { onShare(photo) })
                                }
                            }
                        } else Column {
                            SwipeCard(photo, !state.busy, Modifier.weight(1f).fillMaxWidth(), onDecide, { onOpen(photo) })
                            PhotoMetadata(photo)
                            Text("← / ↓ 待删除        ↑ / → 保留", Modifier.align(Alignment.CenterHorizontally).padding(top = 3.dp), color = colors.onSurfaceVariant, fontSize = 11.sp, letterSpacing = .8.sp)
                            ActionBar(photo, state, onUndo, onDecide, { onShare(photo) })
                        }
                    }
                }
            }
        }
        Surface(onClick = onPending, shape = RoundedCornerShape(19.dp), color = colors.surface,
            border = BorderStroke(1.dp, colors.outline.copy(alpha = .45f)), modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp).testTag("pending_open")) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("trash", color = colors.error, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text("待删除清单", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (state.pending.isEmpty()) "先挑选，最后统一确认" else "${state.pending.size} 张照片 · ${formatBytes(state.pending.sumOf { it.bytes })}", fontSize = 11.sp, color = colors.onSurfaceVariant)
                }
                Text("检查清单  ›", color = colors.primary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Welcome(modifier: Modifier, onPermission: () -> Unit, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.size(116.dp, 145.dp).graphicsLayer { rotationZ = -12f; translationX = -22f }, shape = RoundedCornerShape(23.dp), color = colors.surfaceVariant) {}
            Surface(Modifier.size(116.dp, 145.dp).graphicsLayer { rotationZ = 8f; translationX = 18f }, shape = RoundedCornerShape(23.dp), color = colors.primaryContainer) {
                Box(contentAlignment = Alignment.Center) { Glyph("photos", Modifier.size(50.dp), colors.onPrimaryContainer) }
            }
        }
        Text("给相册，\n留点空间。", fontSize = 40.sp, lineHeight = 49.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp, modifier = Modifier.padding(top = 20.dp))
        Text("从一张随机照片开始。\n留下喜欢的，告别多余的。", color = colors.onSurfaceVariant, fontSize = 15.sp, lineHeight = 25.sp, modifier = Modifier.padding(top = 14.dp, bottom = 27.dp))
        Button(onClick = onPermission, modifier = Modifier.fillMaxWidth().height(54.dp).testTag("permission_start"), shape = RoundedCornerShape(17.dp)) { Text("允许访问照片，开始整理", fontSize = 15.sp) }
        TextButton(onClick = onSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("前往系统设置", fontSize = 12.sp) }
        Text("本机处理 · 无需登录", color = colors.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 15.dp))
    }
}

@Composable
private fun EmptyState(modifier: Modifier, title: String, description: String, button: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = CircleShape, color = colors.primaryContainer, modifier = Modifier.size(80.dp)) { Box(contentAlignment = Alignment.Center) { Glyph("check", Modifier.size(34.dp), colors.onPrimaryContainer) } }
        Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
        Text(description, color = colors.onSurfaceVariant, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.padding(top = 12.dp))
        OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 22.dp)) { Text(button) }
    }
}

@Composable
private fun Stat(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
            Text(count.toString(), color = color, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(label, Modifier.padding(start = 6.dp, bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun PhotoMetadata(photo: Photo) {
    Column(Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 1.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(photo.dateLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Text(photo.dateText, Modifier.padding(start = 9.dp).weight(1f), fontWeight = FontWeight.Medium, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${formatBytes(photo.bytes)}   ·   ${photo.dimensions} 像素", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(photo.album, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 100.dp))
        }
    }
}

@Composable
private fun SwipeCard(photo: Photo, enabled: Boolean, modifier: Modifier, onChoice: (Choice) -> Unit, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val offset = remember(photo.key) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val threshold = with(density) { 82.dp.toPx() }
    var exiting by remember(photo.key) { mutableStateOf(false) }
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnChoice by rememberUpdatedState(onChoice)
    val latestOnOpen by rememberUpdatedState(onOpen)
    val choice = swipeChoice(offset.value.x, offset.value.y, threshold / 3)
    val overlayColor = if (choice == Choice.KEEP) Color(0xFFD6ED9A) else Color(0xFFFFB5A8)
    Box(modifier.testTag("photo_card").semantics(mergeDescendants = true) {
        contentDescription = "照片卡片：${photo.name}。轻触查看详情，左滑或下滑待删除，右滑或上滑保留。"
        onClick("查看照片详情") { onOpen(); true }
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp).graphicsLayer { rotationZ = 3f; scaleX = .96f; scaleY = .96f }.clip(RoundedCornerShape(26.dp)).background(colors.surfaceVariant))
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = offset.value.x; translationY = offset.value.y
            rotationZ = (offset.value.x / threshold * 5).coerceIn(-13f, 13f)
        }.clip(RoundedCornerShape(25.dp)).background(colors.surface)
            .pointerInput(photo.key, threshold) {
                awaitEachGesture {
                    val first = awaitFirstDown()
                    if (!latestEnabled || exiting) return@awaitEachGesture
                    var delta = Offset.Zero
                    var multiple = false
                    var dragged = false
                    var lastChoice: Choice? = null
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) multiple = true
                        val change = event.changes.find { it.id == first.id }
                        if (change != null && !multiple) {
                            delta += change.positionChange()
                            if (delta.getDistance() > viewConfiguration.touchSlop) dragged = true
                            if (dragged) {
                                change.consume()
                                coroutineScope.launch { offset.snapTo(delta) }
                                val currentChoice = swipeChoice(delta.x, delta.y, threshold)
                                if (currentChoice != null && currentChoice != lastChoice) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                lastChoice = currentChoice
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (multiple) { coroutineScope.launch { offset.animateTo(Offset.Zero, spring(stiffness = Spring.StiffnessMedium)) }; latestOnOpen() }
                    else if (!dragged) latestOnOpen()
                    else {
                        val decided = swipeChoice(delta.x, delta.y, threshold)
                        coroutineScope.launch {
                            if (decided != null) {
                                exiting = true
                                val horizontal = abs(delta.x) >= abs(delta.y)
                                val target = if (horizontal) Offset(if (delta.x > 0) size.width * 1.4f else -size.width * 1.4f, delta.y) else Offset(delta.x, if (delta.y > 0) size.height * 1.4f else -size.height * 1.4f)
                                offset.animateTo(target, tween(180, easing = FastOutLinearInEasing))
                                latestOnChoice(decided)
                                offset.snapTo(Offset.Zero); exiting = false
                            } else offset.animateTo(Offset.Zero, spring(dampingRatio = .7f, stiffness = Spring.StiffnessMedium))
                        }
                    }
                }
            }.semantics { contentDescription = "照片卡片：${photo.name}。轻触查看详情，左滑或下滑待删除，右滑或上滑保留。" }) {
            AnimatedContent(photo, transitionSpec = { (fadeIn(tween(230)) + scaleIn(tween(230), initialScale = .96f)) togetherWith fadeOut(tween(100)) }, label = "photo") {
                PhotoImage(it, Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxWidth().height(66.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .22f), Color.Transparent))))
            Surface(shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = .38f), modifier = Modifier.align(Alignment.TopStart).padding(14.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Glyph("shuffle", Modifier.size(13.dp), Color.White); Text("随机抽取", fontSize = 10.sp, color = Color.White, modifier = Modifier.padding(start = 6.dp)) }
            }
            Surface(onClick = onOpen, shape = CircleShape, color = Color.Black.copy(alpha = .38f), modifier = Modifier.align(Alignment.TopEnd).padding(14.dp).size(34.dp)) { Box(contentAlignment = Alignment.Center) { Glyph("zoom", Modifier.size(17.dp), Color.White) } }
            if (choice != null) {
                Surface(shape = RoundedCornerShape(18.dp), color = overlayColor.copy(alpha = .93f), modifier = Modifier.align(Alignment.Center).graphicsLayer { alpha = (maxOf(abs(offset.value.x), abs(offset.value.y)) / threshold).coerceIn(0f, 1f); rotationZ = -8f }) {
                    Text(if (choice == Choice.KEEP) "留下这张" else "加入待删除", Modifier.padding(horizontal = 23.dp, vertical = 13.dp), color = Color(0xFF27301D), fontWeight = FontWeight.Bold, fontSize = 23.sp)
                }
            }
        }
    }
}

@Composable
private fun ActionBar(photo: Photo?, state: CleanState, onUndo: () -> Unit, onDecide: (Choice) -> Unit, onShare: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 1.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        ActionCircle("撤销", "undo", colors.surfaceVariant, colors.onSurfaceVariant, state.undo != null && !state.busy, onUndo, small = true, tag = "undo")
        ActionCircle("待删除", "trash", colors.errorContainer, colors.onErrorContainer, photo != null && !state.busy, { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDecide(Choice.DELETE) }, tag = "delete")
        ActionCircle("保留", "check", colors.primaryContainer, colors.onPrimaryContainer, photo != null && !state.busy, { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDecide(Choice.KEEP) }, tag = "keep")
        ActionCircle("分享", "share", colors.surfaceVariant, colors.onSurfaceVariant, photo != null, onShare, small = true, tag = "share")
    }
}

@Composable
private fun ActionCircle(label: String, icon: String, background: Color, foreground: Color, enabled: Boolean, onClick: () -> Unit, small: Boolean = false, tag: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = background,
            modifier = Modifier.size(if (small) 44.dp else 54.dp).testTag(tag).semantics { contentDescription = label }) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.graphicsLayer { alpha = if (enabled) 1f else .3f }) { Glyph(icon, Modifier.size(if (small) 19.dp else 25.dp), foreground) }
        }
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun PendingScreen(state: CleanState, onBack: () -> Unit, onRestore: (String) -> Unit, onOpen: (Photo) -> Unit, onDelete: () -> Unit, onPermission: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val available = state.photos.mapTo(HashSet()) { it.key }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconControl("返回整理", "back", onBack, Modifier.testTag("pending_back"))
            Text("待删除清单", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 9.dp))
        }
        Text("最后检查一次，再为新回忆腾出空间。", fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 17.dp, bottom = 14.dp))
        Surface(shape = RoundedCornerShape(19.dp), color = colors.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph("trash", Modifier.size(30.dp), colors.onErrorContainer)
                Column(Modifier.padding(start = 16.dp)) {
                    Text("${state.pending.size} 张待删除", color = colors.onErrorContainer, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text("共 ${formatBytes(state.pending.sumOf { it.bytes })} · 尚未删除原图", color = colors.onErrorContainer, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        if (state.pending.any { it.key !in available }) TextButton(onClick = onPermission) { Text("部分照片无法访问，轻触调整权限", fontSize = 12.sp) }
        if (state.pending.isEmpty()) {
            EmptyState(Modifier.weight(1f), "这里还空着", "整理时左滑或下滑，照片会先来到这里。", "继续整理", onBack)
        } else {
            LazyColumn(Modifier.weight(1f).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 15.dp)) {
                items(state.pending, key = { it.key }) { photo ->
                    Surface(shape = RoundedCornerShape(17.dp), color = colors.surface, modifier = Modifier.fillMaxWidth().animateItem()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            PhotoImage(photo, Modifier.size(68.dp).clip(RoundedCornerShape(11.dp)).clickable { onOpen(photo) }, thumbnail = true)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(photo.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(photo.dateText, fontSize = 10.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                                Text("${formatBytes(photo.bytes)} · ${photo.dimensions}", fontSize = 10.sp, color = colors.onSurfaceVariant)
                            }
                            TextButton(enabled = !state.busy, onClick = { onRestore(photo.key) }, modifier = Modifier.testTag("restore_${photo.name}")) { Text("恢复", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        Button(enabled = state.pending.any { it.key in available } && !state.busy, onClick = onDelete,
            colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.surface),
            shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth().height(54.dp).testTag("delete_confirm")) {
            if (state.deleting) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("正在等待系统确认…") }
            else Text("确认删除 ${state.pending.size} 张照片", fontSize = 15.sp)
        }
        Text("确认前可逐张恢复，删除后无法在应用内撤销。", color = colors.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 18.dp))
    }
}

@Composable
private fun DetailScreen(photo: Photo, onClose: () -> Unit, onShare: () -> Unit) {
    var scale by remember(photo.key) { mutableFloatStateOf(1f) }
    var error by remember(photo.key) { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = Color(0xFF0C0E0B)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconControl("关闭详情", "close", onClose, Modifier.testTag("detail_close"), Color.White)
                Text(photo.name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                IconControl("分享原图", "share", onShare, Modifier.testTag("detail_share"), Color.White)
            }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("zoom_view"), contentAlignment = Alignment.Center) {
                ZoomPhoto(photo, Modifier.fillMaxSize(), onScale = { scale = it }, onError = { error = true })
                if (error) Text("无法读取照片，请检查访问权限。", color = Color.White, fontSize = 13.sp)
                Surface(color = Color.Black.copy(alpha = .5f), shape = RoundedCornerShape(9.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                    Text(String.format(Locale.CHINA, "%.1f×", scale), color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 15.dp, bottom = 22.dp)) {
                Text("双指缩放 · 双击放大 / 复原 · 拖动查看", color = Color(0xFFB5BDAE), fontSize = 11.sp, modifier = Modifier.padding(bottom = 17.dp))
                Text("${photo.dateLabel}  ${photo.dateText}", color = Color.White, fontSize = 13.sp)
                Text("${formatBytes(photo.bytes)}    ·    ${photo.dimensions} 像素", color = Color(0xFFB5BDAE), fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp).testTag("detail_metadata"))
                Text(photo.album, color = Color(0xFF8B9485), fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
            }
        }
    }
}

@Composable
private fun Label(text: String) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }

@Composable
private fun IconControl(label: String, icon: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    IconButton(onClick, modifier.semantics { contentDescription = label }) { Glyph(icon, color = color) }
}

@Composable
internal fun Glyph(name: String, modifier: Modifier = Modifier.size(23.dp), color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier) {
        val u = size.minDimension / 24
        val stroke = 1.7f * u
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(x * u, y * u), Offset(xx * u, yy * u), stroke, StrokeCap.Round)
        fun circle(x: Float, y: Float, r: Float) = drawCircle(color, r * u, Offset(x * u, y * u), style = Stroke(stroke))
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) = drawRoundRect(color, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u), style = Stroke(stroke))
        when (name) {
            "check" -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 20f, 7f) }
            "trash" -> { line(4f, 6f, 20f, 6f); rect(7f, 3f, 10f, 3f, 1f); rect(6f, 6f, 12f, 15f); line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f) }
            "share" -> { circle(6f, 12f, 2.4f); circle(18f, 5f, 2.4f); circle(18f, 19f, 2.4f); line(8f, 11f, 16f, 6f); line(8f, 13f, 16f, 18f) }
            "undo", "refresh" -> { drawArc(color, 205f, 280f, false, Offset(5f * u, 5f * u), Size(15f * u, 15f * u), style = Stroke(stroke, cap = StrokeCap.Round)); line(4f, 4f, 4f, 10f); line(4f, 10f, 10f, 10f) }
            "back" -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "zoom" -> { circle(10f, 10f, 6f); line(15f, 15f, 21f, 21f); line(7f, 10f, 13f, 10f); line(10f, 7f, 10f, 13f) }
            "photos" -> { rect(3f, 3f, 13f, 16f); rect(8f, 7f, 13f, 15f); line(10f, 18f, 14f, 13f); line(14f, 13f, 19f, 18f); circle(17f, 11f, 1f) }
            "sun" -> { circle(12f, 12f, 4f); for (i in 0..7) { val a = i * Math.PI / 4; line((12 + 7 * kotlin.math.cos(a)).toFloat(), (12 + 7 * kotlin.math.sin(a)).toFloat(), (12 + 10 * kotlin.math.cos(a)).toFloat(), (12 + 10 * kotlin.math.sin(a)).toFloat()) } }
            "moon" -> { drawArc(color, 40f, 290f, false, Offset(4f * u, 3f * u), Size(17f * u, 17f * u), style = Stroke(stroke)); drawArc(color, 65f, 180f, false, Offset(10f * u, 0f), Size(13f * u, 14f * u), style = Stroke(stroke)) }
            "system" -> { rect(3f, 4f, 18f, 13f); line(12f, 17f, 12f, 21f); line(8f, 21f, 16f, 21f) }
            "shuffle" -> { line(4f, 6f, 8f, 6f); line(8f, 6f, 16f, 18f); line(16f, 18f, 21f, 18f); line(18f, 15f, 21f, 18f); line(21f, 18f, 18f, 21f); line(4f, 18f, 8f, 18f); line(8f, 18f, 16f, 6f); line(16f, 6f, 21f, 6f); line(18f, 3f, 21f, 6f); line(21f, 6f, 18f, 9f) }
        }
    }
}
