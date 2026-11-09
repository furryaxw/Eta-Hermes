package io.github.mangi.eta.agent.hermes

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class HermesRpcEvent(val name: String, val data: JSONObject)

/**
 * Dashboard 侧的 tui_gateway JSON-RPC 客户端。
 *
 * 与 [HermesLink] 分工：这条连接负责**完整会话功能**——会话列表/恢复/删除、发送、
 * 思考与工具事件、重试；[HermesLink] 那条只管手机工具（MCP）与主动推送。
 *
 * 认证用同一个设备令牌：插件注册了 `eta-device` dashboard auth provider，所以
 * `?token=<设备令牌>` 能直接开 `/api/ws`。
 */
internal class HermesRpc(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private val client: OkHttpClient = HermesTransport
        .clientBuilder(HermesSettings.isTrustingSelfSigned(appContext))
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val pending = ConcurrentHashMap<Int, CompletableFuture<JSONObject>>()
    private val seq = AtomicInteger(0)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var readyLatch: CountDownLatch? = null

    @Volatile
    private var url: String = ""

    /** 明文/密文基址（去掉 `/eta`）与令牌：REST 上传走同一套认证。 */
    @Volatile
    private var httpBase: String = ""

    @Volatile
    private var authToken: String = ""

    /** 单独的 HTTP 客户端：WS 那个把 readTimeout 设成 0，不能拿来发普通请求。 */
    private val httpClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var sessionId: String = ""

    /** Eta 会话 id → 该会话自己的 Hermes 会话 id。 */
    private val sessions = ConcurrentHashMap<String, String>()

    @Volatile
    private var activeEtaSession: String = ""

    @Volatile
    private var sink: TurnSink? = null

    @Volatile
    var onEvent: ((HermesRpcEvent) -> Unit)? = null

    val isConnected: Boolean get() = socket != null

    // ------------------------------------------------------------------ //
    // lifecycle
    // ------------------------------------------------------------------ //

    fun connect(baseUrl: String, token: String, timeoutSeconds: Long = 15): Boolean {
        val nextUrl = dashboardWsUrl(baseUrl, token)
        if (nextUrl.isBlank() || token.isBlank()) return false
        if (nextUrl == url && socket != null) return true
        runCatching { socket?.close(1000, "reconnect") }
        socket = null
        url = nextUrl
        httpBase = baseUrl.trim().trimEnd('/').removeSuffix("/eta")
        authToken = token
        val latch = CountDownLatch(1)
        readyLatch = latch
        socket = client.newWebSocket(
            Request.Builder()
                .url(nextUrl)
                .header("Authorization", "Bearer $token")
                .build(),
            Listener(),
        )
        val ok = latch.await(timeoutSeconds, TimeUnit.SECONDS)
        if (!ok) AndroidAgentLogger.warn("Hermes RPC handshake timed out")
        return ok
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            val method = frame.optString("method")
            val params = frame.optJSONObject("params")
            if (method == "event" && params != null) {
                val type = params.optString("type")
                val payload = params.optJSONObject("payload") ?: JSONObject()
                if (type == "gateway.ready") {
                    readyLatch?.countDown()
                    // 声明能回答服务端请求（审批 / 澄清），否则服务端会快速失败。
                    runCatching {
                        socket?.send(
                            JSONObject()
                                .put("jsonrpc", "2.0")
                                .put("id", seq.incrementAndGet())
                                .put("method", "client.capabilities")
                                .put("params", JSONObject().put("server_requests", true))
                                .toString(),
                        )
                    }
                    return
                }
                sink?.accept(type, payload)
                onEvent?.invoke(HermesRpcEvent(type, payload))
                return
            }
            if (frame.has("id")) {
                pending.remove(frame.optInt("id"))?.complete(frame)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AndroidAgentLogger.warn("Hermes RPC failure: ${t.javaClass.simpleName}: ${t.message}")
            drop("Hermes 会话连接已断开")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            drop("Hermes 会话连接已关闭（$code）")
        }
    }

    private fun drop(reason: String) {
        socket = null
        readyLatch?.countDown()
        sink?.fail(reason)
        pending.values.forEach { runCatching { it.completeExceptionally(IOException(reason)) } }
        pending.clear()
    }

    // ------------------------------------------------------------------ //
    // RPC
    // ------------------------------------------------------------------ //

    fun request(method: String, params: JSONObject = JSONObject(), timeoutSeconds: Long = 60): JSONObject {
        val ws = socket ?: throw IOException("Hermes 会话连接未建立")
        val id = seq.incrementAndGet()
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        val frame = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        if (!ws.send(frame.toString())) throw IOException("Hermes 连接已断开")
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (timeout: java.util.concurrent.TimeoutException) {
            pending.remove(id)
            throw IOException("Hermes 没有响应 $method")
        } finally {
            pending.remove(id)
        }
    }

    /** 服务端返回的错误文本，成功时返回 null。 */
    private fun errorOf(response: JSONObject): String? {
        val error = response.optJSONObject("error") ?: return null
        return error.optString("message").ifBlank { "Hermes 返回错误" }
    }

    // ------------------------------------------------------------------ //
    // sessions
    // ------------------------------------------------------------------ //

    /** 该 Eta 会话对应的 Hermes 会话 id；没有就建一个（source=eta → 用 eta 工具集）。 */
    fun ensureSession(etaSessionId: String, title: String): String {
        sessions[etaSessionId]?.let {
            markActive(etaSessionId, it)
            return it
        }
        val stored = HermesSettings.rpcSessionId(appContext, etaSessionId)
            // 镜像模式下列表 id 就是 stored_session_id：没存过映射也先拿它去 resume，别急着新建。
            .ifBlank { etaSessionId }
        if (stored.isNotBlank()) {
            // resume 只认 stored_session_id，并且返回一个新的运行时 id —— 提交必须用后者。
            val resumed = request("session.resume", JSONObject().put("session_id", stored), 30)
            if (errorOf(resumed) == null) {
                val live = resumed.optJSONObject("result")
                    ?.optString("session_id")
                    .orEmpty()
                    .ifBlank { stored }
                sessions[etaSessionId] = live
                markActive(etaSessionId, live)
                return live
            }
            // 会话在 Hermes 侧被删了：清掉本地映射，重新建。
            HermesSettings.clearRpcSessionId(appContext, etaSessionId)
        }
        val created = request(
            "session.create",
            JSONObject()
                .put("source", "eta")
                .put("title", title.ifBlank { "Eta" }.take(120)),
            30,
        )
        errorOf(created)?.let { throw IOException(it) }
        val result = created.optJSONObject("result") ?: JSONObject()
        val live = result.optString("session_id")
        if (live.isBlank()) throw IOException("Hermes 没有返回会话 id")
        // 持久化的是 stored_session_id（形如 20261109_114505_3dbcaf）；短 id 只活在这个进程里。
        val storedId = result.optString("stored_session_id").ifBlank { live }
        sessions[etaSessionId] = live
        HermesSettings.setRpcSessionId(appContext, etaSessionId, storedId)
        markActive(etaSessionId, live)
        return live
    }

    /** 拉取该设备的会话列表（桌面端 / 其它平台产生的也一并返回，由调用方筛选）。 */
    fun listSessions(limit: Int = 200): List<JSONObject> {
        val response = request(
            "session.list",
            JSONObject().put("limit", limit),
            30,
        )
        errorOf(response)?.let { throw IOException(it) }
        val rows = response.optJSONObject("result")?.optJSONArray("sessions") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }

    /** 打开已镜像的会话时绑定：key 就是 Hermes 的 stored id。 */
    fun adopt(storedSessionId: String, liveSessionId: String) {
        if (storedSessionId.isBlank() || liveSessionId.isBlank()) return
        sessions[storedSessionId] = liveSessionId
        markActive(storedSessionId, liveSessionId)
    }

    /** 打开一个已存在的会话：resume 后返回运行时 id 与历史消息。 */
    fun openSession(storedSessionId: String): JSONObject {
        val resumed = request(
            "session.resume",
            JSONObject().put("session_id", storedSessionId),
            45,
        )
        errorOf(resumed)?.let { throw IOException(it) }
        return resumed.optJSONObject("result") ?: JSONObject()
    }

    private fun markActive(etaSessionId: String, hermesSessionId: String) {
        activeEtaSession = etaSessionId
        sessionId = hermesSessionId
    }

    /** 忘掉某个 Eta 会话的映射（本地会话被删除时调用）。 */
    fun forgetSession(etaSessionId: String) {
        sessions.remove(etaSessionId)
        HermesSettings.clearRpcSessionId(appContext, etaSessionId)
        if (activeEtaSession == etaSessionId) {
            activeEtaSession = ""
            sessionId = ""
        }
    }

    fun deleteSession(id: String): Boolean {
        // 关闭只认运行时 id，删除只认持久 id；活跃会话不关闭会被拒（cannot delete an active session）。
        var live = sessions[id].orEmpty()
        if (live.isBlank()) {
            live = runCatching { openSession(id).optString("session_id") }.getOrNull().orEmpty()
        }
        if (live.isNotBlank()) {
            runCatching { request("session.close", JSONObject().put("session_id", live), 20) }
        }
        sessions.remove(id)
        if (sessionId == live || sessionId == id) {
            sessionId = ""
            activeEtaSession = ""
        }
        val response = request("session.delete", JSONObject().put("session_id", id), 30)
        return errorOf(response) == null
    }

    fun interrupt() {
        val id = sessionId.ifBlank { return }
        runCatching { request("session.interrupt", JSONObject().put("session_id", id), 15) }
    }

    // ------------------------------------------------------------------ //
    // turns
    // ------------------------------------------------------------------ //

    private inner class TurnSink(
        private val latch: CountDownLatch,
        private val handler: (HermesRpcEvent) -> Boolean,
    ) {
        private var done = false
        private val textBuf = StringBuilder()
        private val thinkingBuf = StringBuilder()

        @Volatile
        var interrupted = false
            private set

        @Synchronized
        fun accept(type: String, payload: JSONObject) {
            if (done) return
            when (type) {
                "message.start" -> {
                    // 每条助手消息重新计数：前缀去重只在本条消息内有效。
                    textBuf.setLength(0)
                    thinkingBuf.setLength(0)
                }

                "message.delta" -> {
                    val text = payload.optString("text")
                    if (text.isEmpty()) return
                    // 服务端可能在重发全文（fresh-final）：已收内容是新文本的前缀时，只补多出来的部分。
                    val delta = if (textBuf.isNotEmpty() && text.startsWith(textBuf.toString())) {
                        text.substring(textBuf.length)
                    } else {
                        text
                    }
                    textBuf.append(delta)
                    if (delta.isEmpty()) return
                    if (!handler(HermesRpcEvent("assistant.delta", JSONObject().put("delta", delta)))) {
                        finish()
                    }
                }

                "reasoning.delta", "thinking.delta" -> {
                    val text = payload.optString("text")
                    if (text.isEmpty()) return
                    val delta = if (thinkingBuf.isNotEmpty() && text.startsWith(thinkingBuf.toString())) {
                        text.substring(thinkingBuf.length)
                    } else {
                        text
                    }
                    thinkingBuf.append(delta)
                    if (delta.isEmpty()) return
                    handler(
                        HermesRpcEvent(
                            "tool.progress",
                            JSONObject().put("tool_name", "_thinking").put("delta", delta),
                        ),
                    )
                }

                "tool.start" -> {
                    thinkingBuf.setLength(0)
                    handler(
                        HermesRpcEvent(
                            "tool.started",
                            JSONObject()
                                .put("tool_name", payload.optString("name"))
                                .put("tool_id", payload.optString("tool_id"))
                                .put("args", payload.optJSONObject("args") ?: JSONObject()),
                        ),
                    )
                }

                "tool.complete" -> handler(
                    HermesRpcEvent(
                        "tool.completed",
                        JSONObject()
                            .put("tool_name", payload.optString("name"))
                            .put("tool_id", payload.optString("tool_id"))
                            .put(
                                "preview",
                                payload.optString("summary")
                                    .ifBlank { payload.optString("result_text") },
                            ),
                    ),
                )

                "message.complete" -> {
                    handler(
                        HermesRpcEvent(
                            "assistant.completed",
                            JSONObject().put("content", payload.optString("text")),
                        ),
                    )
                    finish()
                }

                "error" -> {
                    handler(
                        HermesRpcEvent(
                            "run.failed",
                            JSONObject().put("error", payload.optString("message").ifBlank { "Hermes 运行失败" }),
                        ),
                    )
                    finish()
                }
            }
        }

        @Synchronized
        fun fail(reason: String) {
            if (done) return
            handler(HermesRpcEvent("run.failed", JSONObject().put("error", reason)))
            finish()
        }

        @Synchronized
        fun finish() {
            if (done) return
            done = true
            latch.countDown()
        }

        fun interrupt() {
            interrupted = true
            finish()
        }
    }

    /**
     * 发送一轮并阻塞消费事件，直到 `message.complete`。
     *
     * 事件名沿用执行器已有的 SSE 名字，所以 `HermesRunExecutor` 的翻译表不用重写。
     */
    fun submit(
        etaSessionId: String,
        text: String,
        images: List<AgentModelClient.ModelImage>,
        timeoutSeconds: Long = 30 * 60,
        truncateBeforeRowId: Long? = null,
        confirmEmptyTruncate: Boolean = false,
        confirmDeepTruncate: Boolean = false,
        handler: (HermesRpcEvent) -> Boolean,
    ) {
        val id = ensureSession(etaSessionId, text.take(60))
        val latch = CountDownLatch(1)
        val turn = TurnSink(latch, handler)
        sink = turn
        try {
            val params = JSONObject().put("session_id", id).put("text", text)
            if (truncateBeforeRowId != null) {
                // 服务端要求两者同传：只有 row_id 会被 4029 拒绝，确认位是"我确实要丢弃历史"。
                params.put("truncate_before_row_id", truncateBeforeRowId)
                params.put("confirm_truncate", true)
                // 清空整个会话要单独点头，否则 4028。
                if (confirmEmptyTruncate) params.put("confirm_empty_truncate", true)
                // 一次砍掉多个用户轮次：深度截断（目前服务端只警告，未来会硬拦）。
                if (confirmDeepTruncate) params.put("confirm_deep_truncate", true)
            }
            // 图片先落到服务端并挂到会话，然后才提交文本。
            if (images.isNotEmpty()) attachImages(id, images)
            val response = request("prompt.submit", params, 60)
            errorOf(response)?.let { throw IOException(it) }
            if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                throw IOException("Hermes 没有在 ${timeoutSeconds / 60} 分钟内完成")
            }
            if (turn.interrupted) throw java.util.concurrent.CancellationException("Hermes 运行已取消")
        } finally {
            sink = null
        }
    }

    /**
     * 编辑某条用户消息：截断到它之前并重新提交。
     *
     * 只传序号不够——服务端对持久会话的"仅序号截断"直接 4004 拒绝，必须换成 row_id。
     */
    fun rewindAndSubmit(
        etaSessionId: String,
        ordinal: Int,
        text: String,
        images: List<AgentModelClient.ModelImage>,
        timeoutSeconds: Long = 30 * 60,
        handler: (HermesRpcEvent) -> Boolean,
    ) {
        val id = ensureSession(etaSessionId, text.take(60))
        val rows = userRowIds(id)
        if (ordinal !in rows.indices) {
            throw IOException("Hermes 历史里找不到第 ${ordinal + 1} 个用户消息，无法定位编辑位置")
        }
        submit(
            etaSessionId = etaSessionId,
            text = text,
            images = images,
            timeoutSeconds = timeoutSeconds,
            truncateBeforeRowId = rows[ordinal],
            confirmEmptyTruncate = ordinal == 0,
            confirmDeepTruncate = rows.size - ordinal > 1,
            handler = handler,
        )
    }

    /**
     * 上传一个非图片文件，返回服务端可用的 `@file:` 引用文本。
     *
     * 手机本地路径服务端读不到；桌面端远端模式同样是把字节传上去再引用服务端路径。
     */
    fun attachFile(sessionId: String, dataUrl: String, name: String): String? {
        val response = request(
            "file.attach",
            JSONObject()
                .put("session_id", sessionId)
                .put("data_url", dataUrl)
                .put("name", name),
            180,
        )
        if (errorOf(response) != null) return null
        return response.optJSONObject("result")
            ?.optString("ref_text")
            ?.takeIf { it.isNotBlank() }
    }

    /** 直接读历史（不 resume）：会话正忙时也能显示内容。 */
    fun historyMessages(sessionId: String): org.json.JSONArray? {
        val response = request(
            "session.history",
            JSONObject().put("session_id", sessionId),
            45,
        )
        if (errorOf(response) != null) return null
        return response.optJSONObject("result")?.optJSONArray("messages")
    }

    /**
     * 真删除：按消息 row_id 定位它所属的用户轮次，直接改写历史，不触发模型。
     *
     * 走插件的 `/api/plugins/eta/truncate`（dashboard 进程内调用 tui_gateway 的截断实现），
     * 而不是 `prompt.submit` + 空文本 —— 后者必然起一轮空跑并留下 failed_turn 收盘行。
     */
    fun deleteTurn(etaSessionId: String, rowId: Long): Long {
        val id = ensureSession(etaSessionId, "")
        val rows = historyRows(id)
        var index = -1
        for (i in rows.indices) {
            if (rows[i].optLong("row_id", 0L) == rowId) {
                index = i
                break
            }
        }
        if (index < 0) throw IOException("Hermes 历史里找不到该消息（row=$rowId）")
        var target = 0L
        for (i in index downTo 0) {
            if (rows[i].optString("role") == "user") {
                target = rows[i].optLong("row_id", 0L)
                break
            }
        }
        if (target <= 0L) throw IOException("这之前没有用户消息可删")
        truncateAt(id, rows, target)
        return target
    }

    /** 真删除：按第 [ordinal] 个用户轮次截断。 */
    fun deleteTurnAtOrdinal(etaSessionId: String, ordinal: Int): Long {
        val id = ensureSession(etaSessionId, "")
        val rows = historyRows(id)
        val targets = rows.filter { it.optString("role") == "user" }
            .mapNotNull { it.optLong("row_id", 0L).takeIf { value -> value > 0L } }
        if (ordinal !in targets.indices) throw IOException("找不到第 ${ordinal + 1} 个用户消息")
        truncateAt(id, rows, targets[ordinal])
        return targets[ordinal]
    }

    private fun truncateAt(sessionId: String, rows: List<JSONObject>, targetRowId: Long) {
        val userRows = rows.filter { it.optString("role") == "user" }
            .mapNotNull { it.optLong("row_id", 0L).takeIf { value -> value > 0L } }
        val ordinal = userRows.indexOf(targetRowId).takeIf { it >= 0 } ?: 0
        // `session.truncate` 只改写历史，不提交、不起一轮模型。
        val params = JSONObject()
            .put("session_id", sessionId)
            .put("truncate_before_row_id", targetRowId)
            .put("confirm_truncate", true)
            .put("confirm_empty_truncate", ordinal == 0)
            .put("confirm_deep_truncate", userRows.size - ordinal > 1)
        errorOf(request("session.truncate", params, 60))?.let { throw IOException(it) }
    }

    private fun historyRows(sessionId: String): List<JSONObject> {
        val response = request(
            "session.history",
            JSONObject().put("session_id", sessionId),
            45,
        )
        errorOf(response)?.let { throw IOException(it) }
        val rows = response.optJSONObject("result")?.optJSONArray("messages") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }

    /** 新建一个 Hermes 会话，返回 (持久 id, 运行时 id)。 */
    fun createNew(title: String): Pair<String, String>? {
        val created = request(
            "session.create",
            JSONObject().put("source", "eta").put("title", title.take(120)),
            30,
        )
        if (errorOf(created) != null) return null
        val result = created.optJSONObject("result") ?: return null
        val live = result.optString("session_id")
        if (live.isBlank()) return null
        val stored = result.optString("stored_session_id").ifBlank { live }
        sessions[stored] = live
        HermesSettings.setRpcSessionId(appContext, stored, stored)
        markActive(stored, live)
        return stored to live
    }

    /** 读取会话引用过的图片（服务端限死在 profile home 内），返回 data URL。 */
    fun readImage(path: String): String? {
        val response = request("image.read", JSONObject().put("path", path), 60)
        if (errorOf(response) != null) return null
        val result = response.optJSONObject("result") ?: return null
        val encoded = result.optString("base64")
        if (encoded.isBlank()) return null
        val mime = result.optString("mime").ifBlank { "image/jpeg" }
        return "data:$mime;base64,$encoded"
    }

    /** 批量取工具结果（history 里只有调用记录，结果在 state.db）。 */
    fun toolResults(sessionId: String, toolCallIds: List<String>): Map<String, String> {
        if (toolCallIds.isEmpty()) return emptyMap()
        val ids = org.json.JSONArray().apply { toolCallIds.forEach { put(it) } }
        val response = request(
            "session.tool_results",
            JSONObject().put("session_id", sessionId).put("tool_call_ids", ids),
            60,
        )
        if (errorOf(response) != null) return emptyMap()
        val results = response.optJSONObject("result")?.optJSONObject("results") ?: return emptyMap()
        return results.keys().asSequence().associateWith { key -> results.optString(key) }
    }

    /** 全部用户消息行的 row_id（按出现顺序）。 */
    private fun userRowIds(sessionId: String): List<Long> {
        val response = request(
            "session.history",
            JSONObject().put("session_id", sessionId),
            45,
        )
        errorOf(response)?.let { throw IOException(it) }
        val rows = response.optJSONObject("result")?.optJSONArray("messages") ?: return emptyList()
        val out = mutableListOf<Long>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optString("role") != "user") continue
            val rowId = row.optLong("row_id", 0L)
            if (rowId > 0L) out += rowId
        }
        return out
    }

    fun cancel() {
        sink?.interrupt()
        interrupt()
    }

    /** busy 时补一句：Hermes 按 platform 语义并入当前轮或排队。 */
    fun steer(text: String) {
        if (text.isBlank()) return
        val id = sessionId.ifBlank { return }
        runCatching {
            request("prompt.submit", JSONObject().put("session_id", id).put("text", text), 30)
        }
    }

    /**
     * 把图片字节交给服务端并挂到会话上。
     *
     * `prompt.submit` 没有客户端图片字段：桌面端远端模式也是先
     * `POST /api/chat/image-upload` 落盘，再 `image.attach` 那个路径。
     */
    private fun attachImages(sessionId: String, images: List<AgentModelClient.ModelImage>) {
        val usable = images.filter { it.reference.startsWith("data:") }
        if (usable.isEmpty()) return
        if (httpBase.isBlank() || authToken.isBlank()) throw IOException("Hermes 地址或令牌缺失，无法上传图片")
        usable.forEach { image ->
            val path = uploadImage(image.reference, image.mimeType)
            val response = request(
                "image.attach",
                JSONObject().put("session_id", sessionId).put("path", path),
                60,
            )
            errorOf(response)?.let { throw IOException(it) }
        }
    }

    private fun uploadImage(dataUrl: String, mimeType: String): String {
        val stamp = System.currentTimeMillis()
        val filename = "eta-$stamp.${extensionFor(mimeType)}"
        val body = JSONObject().put("data_url", dataUrl).put("filename", filename)
        val request = Request.Builder()
            .url("$httpBase/api/chat/image-upload")
            .header("Authorization", "Bearer $authToken")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Hermes 图片上传失败(${response.code}): ${text.take(160)}")
            }
            val path = runCatching { JSONObject(text).optString("path") }.getOrDefault("")
            if (path.isBlank()) throw IOException("Hermes 没有返回图片路径")
            return path
        }
    }

    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic" -> "heic"
        else -> "png"
    }

    override fun close() {
        sink?.fail("Hermes 会话连接已关闭")
        runCatching { socket?.close(1000, "shutdown") }
        socket = null
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        /** `https://host:9119/eta` → `wss://host:9119/api/ws?token=…`。 */
        fun dashboardWsUrl(baseUrl: String, token: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            val origin = trimmed.removeSuffix("/eta")
            val ws = origin
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://")
            return "$ws/api/ws?token=${URLEncoder.encode(token, "UTF-8")}"
        }
    }
}

/**
 * 进程级唯一的 [HermesRpc]。
 *
 * 会话功能只允许一条连接：两条连接会在同一条 Hermes 会话上互相顶掉事件。
 */
internal object HermesRpcHolder {

    @Volatile
    private var instance: HermesRpc? = null

    fun link(context: Context): HermesRpc = synchronized(this) {
        instance ?: HermesRpc(context.applicationContext).also { instance = it }
    }

    fun close() {
        synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
