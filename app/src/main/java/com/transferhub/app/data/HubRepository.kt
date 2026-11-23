package com.transferhub.app.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** 传送站文件索引：所有收到的、分享出去的文件都在这里登记，并持久化到本地。 */
object HubRepository {
    private const val TAG = "HubRepository"
    const val FOLDER = "TransferHub"

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _files = MutableStateFlow<List<HubFile>>(emptyList())
    val files: StateFlow<List<HubFile>> = _files.asStateFlow()

    /** 正在后台复制的分享文件数量（用于界面提示） */
    private val _pendingImports = MutableStateFlow(0)
    val pendingImports: StateFlow<Int> = _pendingImports.asStateFlow()

    private val indexFile by lazy { File(app.filesDir, "hub_index.json") }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        _files.value = load()
    }

    fun get(id: String): HubFile? = _files.value.firstOrNull { it.id == id }

    fun openInput(file: HubFile): InputStream? =
        runCatching { app.contentResolver.openInputStream(Uri.parse(file.uri)) }
            .onFailure { Log.w(TAG, "无法打开 ${file.name}", it) }
            .getOrNull()

    // ---------------------------------------------------------------- 接收

    /**
     * 接收一个上传的文件。[writer] 负责把数据写入输出流并返回写入的字节数；
     * 返回 null 表示写入失败（已清理残留文件）。
     */
    suspend fun receive(
        rawName: String,
        mimeHint: String?,
        expectedSize: Long?,
        writer: suspend (OutputStream) -> Long,
    ): HubFile? {
        val name = sanitizeName(rawName)
        val mime = guessMime(name, mimeHint)
        val target = createReceiveTarget(name, mime) ?: return null
        val written = try {
            val out = app.contentResolver.openOutputStream(target.uri) ?: error("无法写入")
            out.use { writer(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "接收失败：$name", e)
            target.discard()
            return null
        }
        if (expectedSize != null && expectedSize >= 0 && written != expectedSize) {
            Log.w(TAG, "接收不完整：$name $written/$expectedSize")
            target.discard()
            return null
        }
        val finalName = target.commit() ?: name
        val entry = HubFile(
            id = newId(),
            name = finalName,
            size = written,
            mime = mime,
            uri = target.uri.toString(),
            source = FileSource.RECEIVED,
            time = System.currentTimeMillis(),
        )
        add(entry)
        return entry
    }

    private class ReceiveTarget(val uri: Uri, val commit: () -> String?, val discard: () -> Unit)

    private fun createReceiveTarget(name: String, mime: String): ReceiveTarget? {
        val resolver = app.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：通过 MediaStore 写入“下载/TransferHub”，在文件管理器里直接可见
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = runCatching {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }.onFailure { Log.w(TAG, "MediaStore 插入失败", it) }.getOrNull() ?: return null
            return ReceiveTarget(
                uri = uri,
                commit = {
                    resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    }, null, null)
                    queryDisplayName(uri)
                },
                discard = { runCatching { resolver.delete(uri, null, null) } },
            )
        }
        // Android 8/9：有存储权限就写公共下载目录，否则写 App 专属目录
        val canWritePublic = ContextCompat.checkSelfPermission(
            app, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val dir = if (canWritePublic) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        } else {
            File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        }
        dir.mkdirs()
        val file = uniqueFile(dir, name)
        file.createNewFile()
        return ReceiveTarget(
            uri = Uri.fromFile(file),
            commit = { file.name },
            discard = { file.delete() },
        )
    }

    // ---------------------------------------------------------------- 分享

    /**
     * 把本机文件加入传送站。能长期授权的（文件选择器选的）直接引用原文件；
     * 临时授权的（从其他 App 分享过来的）先复制一份到私有目录，避免授权过期后无法读取。
     */
    fun addShared(uris: List<Uri>) {
        for (uri in uris) {
            val persisted = runCatching {
                app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            val name = sanitizeName(queryDisplayName(uri) ?: uri.lastPathSegment ?: "file")
            val mime = guessMime(name, app.contentResolver.getType(uri))
            if (persisted || uri.scheme == "file") {
                add(
                    HubFile(
                        id = newId(), name = name, size = querySize(uri) ?: -1, mime = mime,
                        uri = uri.toString(), source = FileSource.SHARED,
                        time = System.currentTimeMillis(),
                    )
                )
            } else {
                importCopy(uri, name, mime)
            }
        }
    }

    private fun importCopy(uri: Uri, name: String, mime: String) {
        _pendingImports.update { it + 1 }
        scope.launch {
            try {
                val dir = File(app.filesDir, "shared").apply { mkdirs() }
                val dest = uniqueFile(dir, name)
                val size = app.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it, 256 * 1024) }
                } ?: error("无法读取")
                add(
                    HubFile(
                        id = newId(), name = name, size = size, mime = mime,
                        uri = Uri.fromFile(dest).toString(), source = FileSource.SHARED,
                        time = System.currentTimeMillis(), ownedCopy = true,
                    )
                )
            } catch (e: Throwable) {
                Log.w(TAG, "导入分享文件失败：$name", e)
            } finally {
                _pendingImports.update { it - 1 }
            }
        }
    }

    /** 从传送站移除。收到的文件仍保留在“下载”目录，只是不再对外提供。 */
    fun remove(id: String) {
        val target = get(id) ?: return
        _files.update { list -> list.filterNot { it.id == id } }
        persist()
        if (target.ownedCopy) {
            Uri.parse(target.uri).path?.let { File(it).delete() }
        } else if (target.source == FileSource.SHARED) {
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    Uri.parse(target.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private fun add(entry: HubFile) {
        _files.update { listOf(entry) + it }
        persist()
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(12)

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.lastPathSegment
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun querySize(uri: Uri): Long? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.path?.let { File(it).length() }
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }.getOrNull()

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (file.exists()) file = File(dir, "$base ($i)$ext").also { i++ }
        return file
    }

    fun sanitizeName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
            .trim().trimStart('.')
        return cleaned.ifEmpty { "file" }.take(200)
    }

    private fun guessMime(name: String, hint: String?): String {
        if (!hint.isNullOrBlank() && hint != "application/octet-stream") return hint
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    // ---------------------------------------------------------------- 持久化

    @Synchronized
    private fun persist() {
        val arr = JSONArray()
        _files.value.forEach { f ->
            arr.put(JSONObject().apply {
                put("id", f.id); put("name", f.name); put("size", f.size); put("mime", f.mime)
                put("uri", f.uri); put("source", f.source.name); put("time", f.time)
                put("ownedCopy", f.ownedCopy)
            })
        }
        runCatching {
            val tmp = File(indexFile.path + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(indexFile)
        }.onFailure { Log.w(TAG, "保存索引失败", it) }
    }

    private fun load(): List<HubFile> = runCatching {
        if (!indexFile.exists()) return emptyList()
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HubFile(
                id = o.getString("id"), name = o.getString("name"), size = o.getLong("size"),
                mime = o.getString("mime"), uri = o.getString("uri"),
                source = FileSource.valueOf(o.getString("source")), time = o.getLong("time"),
                ownedCopy = o.optBoolean("ownedCopy"),
            )
        }
    }.getOrElse {
        Log.w(TAG, "读取索引失败", it)
        emptyList()
    }
}
