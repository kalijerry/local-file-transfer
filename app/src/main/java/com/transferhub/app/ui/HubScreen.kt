package com.transferhub.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.transferhub.app.data.FileSource
import com.transferhub.app.data.HubFile
import com.transferhub.app.data.HubRepository
import com.transferhub.app.data.Settings
import com.transferhub.app.service.HubService
import com.transferhub.app.service.HubStatus
import com.transferhub.app.util.QrCode
import com.transferhub.app.util.formatSize
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen() {
    val context = LocalContext.current
    val status by HubService.status.collectAsState()
    val files by HubRepository.files.collectAsState()
    val pending by HubRepository.pendingImports.collectAsState()
    val allowDelete by Settings.allowWebDelete.collectAsState()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) HubRepository.addShared(uris)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("传送站") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(status) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("允许网页端删除文件", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "关闭时，其他设备只能上传和下载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = allowDelete, onCheckedChange = { Settings.setAllowWebDelete(context, it) })
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "传送站文件（${files.size}）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("分享本机文件")
                    }
                }
            }
            if (pending > 0) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在导入 $pending 个文件…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (files.isEmpty()) {
                item {
                    Text(
                        "还没有文件。其他设备上传的文件，以及你分享出去的文件都会出现在这里。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(files, key = { it.id }) { file -> FileRow(file) }
        }
    }
}

@Composable
private fun StatusCard(status: HubStatus) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (status) {
                HubStatus.Stopped, is HubStatus.Failed -> {
                    Text("传送站未启动", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "启动后，同一 Wi-Fi（或连着本机热点）的手机、电脑扫码即可用浏览器收发文件，无需安装任何软件。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status is HubStatus.Failed) {
                        Spacer(Modifier.height(8.dp))
                        Text("上次启动失败：${status.message}", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { HubService.start(context) }, modifier = Modifier.fillMaxWidth()) {
                        Text("启动传送站")
                    }
                }
                HubStatus.Starting -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("正在启动…")
                }
                is HubStatus.Running -> RunningContent(status)
            }
        }
    }
}

@Composable
private fun RunningContent(status: HubStatus.Running) {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf(0) }
    val address = status.addresses.getOrNull(selected) ?: status.addresses.firstOrNull()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(Color(0xFF2EA043), RoundedCornerShape(50)))
        Spacer(Modifier.width(8.dp))
        Text("传送站运行中", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { HubService.refresh() }) {
            Icon(Icons.Default.Refresh, contentDescription = "刷新地址")
        }
    }

    if (address == null) {
        Spacer(Modifier.height(12.dp))
        Text(
            "没有找到可用的局域网地址。\n请连接 Wi-Fi，或打开系统热点让其他设备连进来，然后点右上角刷新。",
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        val url = status.urlFor(address)
        val qr = remember(url) { QrCode.encode(url).asImageBitmap() }
        if (status.addresses.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                status.addresses.forEachIndexed { i, a ->
                    FilterChip(
                        selected = a == address,
                        onClick = { selected = i },
                        label = { Text(a.label) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Image(
            bitmap = qr,
            contentDescription = "传送站二维码",
            modifier = Modifier
                .size(240.dp)
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(8.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("用相机扫码，或在浏览器输入：", style = MaterialTheme.typography.bodySmall)
        Text(
            url,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { copyToClipboard(context, url) },
        )
        Text("（点击网址可复制）", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = { HubService.stop(context) }, modifier = Modifier.fillMaxWidth()) {
        Text("停止传送站")
    }
}

@Composable
private fun FileRow(file: HubFile) {
    val context = LocalContext.current
    val received = file.source == FileSource.RECEIVED
    Card(modifier = Modifier.fillMaxWidth().clickable { openFile(context, file) }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .background(
                        if (received) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.tertiaryContainer,
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(if (received) "收到" else "分享", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatSize(file.size)} · ${formatTime(file.time)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { shareFile(context, file) }) {
                Icon(Icons.Default.Share, contentDescription = "发送到其他 App")
            }
            IconButton(onClick = { HubRepository.remove(file.id) }) {
                Icon(Icons.Default.Delete, contentDescription = "从传送站移除")
            }
        }
    }
}

// ---------------------------------------------------------------- 辅助函数

private fun contentUri(context: Context, file: HubFile): Uri {
    val uri = Uri.parse(file.uri)
    if (uri.scheme != "file") return uri
    return FileProvider.getUriForFile(context, "${context.packageName}.files", File(uri.path!!))
}

private fun openFile(context: Context, file: HubFile) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(contentUri(context, file), file.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "没有可以打开此文件的应用", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开：${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: Context, file: HubFile) {
    runCatching {
        val uri = contentUri(context, file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(file.mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(file.name, uri)
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "无法分享：${it.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText("传送站地址", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

private fun formatTime(time: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
