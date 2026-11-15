package com.albumclean.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.albumclean.app.BuildConfig
import com.albumclean.app.data.formatBytes
import com.albumclean.app.updates.*
import kotlinx.coroutines.launch

@Composable
fun UpdateCard(updater: AppUpdater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { revision++ }
    val allowed = remember(revision, state.stage) { updater.mayInstall() }
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = colors.surfaceVariant.copy(alpha = .6f), modifier = Modifier.fillMaxWidth().testTag("update_card")) {
        Column(Modifier.padding(16.dp)) {
            Text("应用更新", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("当前版本 ${BuildConfig.VERSION_NAME}", color = colors.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            state.release?.let { Text("新版本 ${it.version} · ${formatBytes(it.bytes)}", fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
            val message = when (state.stage) {
                UpdateStage.CHECKING -> "正在检查 GitHub 正式版本…"
                UpdateStage.CURRENT -> "已是最新版本"
                UpdateStage.UNPUBLISHED -> "暂未发布可用的正式版本"
                UpdateStage.AVAILABLE -> "发现新版本，下载后由系统确认安装。"
                UpdateStage.DOWNLOADING -> "正在下载更新… ${state.percent}%"
                UpdateStage.VERIFYING -> "正在校验安装包…"
                UpdateStage.READY -> if (allowed) "校验完成，可以安装更新。" else "校验完成。首次更新需要允许拾光安装应用。"
                UpdateStage.ERROR -> when (state.issue) {
                    UpdateIssue.RATE_LIMIT -> "GitHub 请求暂时受限，请稍后再试。"
                    UpdateIssue.METADATA -> "更新信息校验失败，请稍后重新检查。"
                    UpdateIssue.PACKAGE -> "安装包校验失败，请重新下载。"
                    UpdateIssue.INSTALLER -> "无法打开系统安装程序，请检查安装权限。"
                    else -> "更新未完成，请检查网络和存储空间后重试。"
                }
                else -> if (updater.supported) "手动检查 GitHub 更新，不会上传照片。" else "调试版本请使用开发工具更新。"
            }
            Text(message, fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 9.dp).testTag("update_status"))
            if (state.stage == UpdateStage.DOWNLOADING) LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            else if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            if (state.stage == UpdateStage.READY) {
                Button(modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("install_update"), onClick = {
                    if (!updater.mayInstall()) runCatching { permission.launch(updater.permissionIntent()) }.onFailure { updater.installerFailed() }
                    else scope.launch { updater.installIntent()?.let { intent -> runCatching { installer.launch(intent) }.onFailure { updater.installerFailed() } } }
                }) { Text(if (allowed) "安装更新" else "允许安装更新") }
            } else if (state.release != null && !state.busy) {
                Button(updater::download, Modifier.fillMaxWidth().padding(top = 12.dp).testTag("download_update")) { Text("下载更新") }
            }
            if (state.busy && state.stage != UpdateStage.VERIFYING) TextButton(updater::cancel) { Text("取消") }
            if (!state.busy) OutlinedButton(updater::check, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("check_update"), enabled = updater.supported) { Text("检查更新") }
        }
    }
}
