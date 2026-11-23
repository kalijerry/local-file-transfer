package com.transferhub.app.server

import android.content.Context
import android.os.Build
import android.util.Log
import com.transferhub.app.BuildConfig
import com.transferhub.app.data.FileSource
import com.transferhub.app.data.HubRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLEncoder

/**
 * 传送站内置 HTTP 服务器。
 *
 * - `GET  /`                    传输网页（任何设备的浏览器打开即可用）
 * - `GET  /api/info`            传送站信息
 * - `GET  /api/files`           文件列表
 * - `GET  /api/files/{id}`      下载文件
 * - `PUT  /api/upload?name=xx`  上传文件（请求体即文件内容，流式写盘）
 *
 * 所有 /api 请求都必须带访问令牌（`?t=` 或 `X-Hub-Token` 请求头），令牌在每次启动时随机生成，
 * 只通过二维码 / 网址分发，同一网络中不知道令牌的人无法访问。
 */
class TransferServer(
    private val context: Context,
    private val token: String,
    private val allowWebDelete: () -> Boolean = { false },
) {
    private var engine: ApplicationEngine? = null
    var port: Int = 0
        private set

    private val indexHtml: ByteArray by lazy {
        context.assets.open("web/index.html").use { it.readBytes() }
    }

    /** 启动服务器，返回实际监听的端口。 */
    fun start(preferredPorts: IntRange = 8080..8099): Int {
        var lastError: Throwable? = null
        for (p in preferredPorts) {
            if (!isPortFree(p)) continue
            try {
                val e = embeddedServer(CIO, port = p, host = "0.0.0.0") { routes() }
                e.start(wait = false)
                engine = e
                port = p
                Log.i(TAG, "服务器已启动，端口 $p")
                return p
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "端口 $p 启动失败", t)
            }
        }
        throw IllegalStateException("没有可用端口", lastError)
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 300, timeoutMillis = 2000)
        engine = null
    }

    private fun isPortFree(p: Int): Boolean = runCatching {
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(p)) }
    }.isSuccess

    private fun io.ktor.server.application.Application.routes() {
        routing {
            get("/") {
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondBytes(indexHtml, ContentType.Text.Html.withCharset(Charsets.UTF_8))
            }

            get("/api/info") {
                if (!call.authorize()) return@get
                call.respondJson(JSONObject().apply {
                    put("name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                    put("version", BuildConfig.VERSION_NAME)
                    put("allowDelete", allowWebDelete())
                })
            }

            get("/api/files") {
                if (!call.authorize()) return@get
                val arr = JSONArray()
                HubRepository.files.value.forEach { f ->
                    arr.put(JSONObject().apply {
                        put("id", f.id); put("name", f.name); put("size", f.size)
                        put("mime", f.mime); put("time", f.time)
                        put("source", if (f.source == FileSource.RECEIVED) "received" else "shared")
                    })
                }
                call.respondJson(JSONObject().put("files", arr))
            }

            get("/api/files/{id}") {
                if (!call.authorize()) return@get
                val file = call.parameters["id"]?.let { HubRepository.get(it) }
                    ?: return@get call.respondText("文件不存在", status = HttpStatusCode.NotFound)
                val input = withContext(Dispatchers.IO) { HubRepository.openInput(file) }
                    ?: return@get call.respondText("文件无法读取（可能已被删除）", status = HttpStatusCode.Gone)
                val inline = call.request.queryParameters["inline"] == "1"
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    contentDisposition(file.name, inline),
                )
                call.respond(object : OutgoingContent.ReadChannelContent() {
                    override val contentType = runCatching { ContentType.parse(file.mime) }
                        .getOrDefault(ContentType.Application.OctetStream)
                    override val contentLength = file.size.takeIf { it >= 0 }
                    override fun readFrom(): ByteReadChannel = input.toByteReadChannel()
                })
            }

            delete("/api/files/{id}") {
                if (!call.authorize()) return@delete
                if (!allowWebDelete()) {
                    return@delete call.respondText("传送站未允许网页端删除", status = HttpStatusCode.Forbidden)
                }
                call.parameters["id"]?.let { HubRepository.remove(it) }
                call.respondJson(JSONObject().put("ok", true))
            }

            put("/api/upload") {
                if (!call.authorize()) return@put
                val name = call.request.queryParameters["name"]
                    ?: return@put call.respondText("缺少文件名", status = HttpStatusCode.BadRequest)
                val expected = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
                val channel = call.receiveChannel()
                val saved = HubRepository.receive(
                    rawName = name,
                    mimeHint = call.request.header(HttpHeaders.ContentType),
                    expectedSize = expected,
                ) { out ->
                    withContext(Dispatchers.IO) { channel.copyTo(out) }
                }
                if (saved == null) {
                    call.respondText("保存失败", status = HttpStatusCode.InternalServerError)
                } else {
                    call.respondJson(JSONObject().put("id", saved.id).put("name", saved.name))
                }
            }
        }
    }

    private suspend fun ApplicationCall.authorize(): Boolean {
        val given = request.queryParameters["t"] ?: request.header("X-Hub-Token")
        if (given == token) return true
        respondText("访问令牌无效，请重新扫描传送站上的二维码", status = HttpStatusCode.Unauthorized)
        return false
    }

    private suspend fun ApplicationCall.respondJson(obj: JSONObject) {
        response.header(HttpHeaders.CacheControl, "no-store")
        respondText(obj.toString(), ContentType.Application.Json.withCharset(Charsets.UTF_8))
    }

    private fun contentDisposition(name: String, inline: Boolean): String {
        val ascii = name.replace(Regex("[^\\x20-\\x7e]"), "_").replace("\"", "_")
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val type = if (inline) "inline" else "attachment"
        return "$type; filename=\"$ascii\"; filename*=UTF-8''$encoded"
    }

    companion object {
        private const val TAG = "TransferServer"
    }
}
