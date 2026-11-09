package io.github.mangi.eta.agent.hermes

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 一条来自 Hermes 的流事件；`name` 沿用旧的 SSE 事件名，便于执行器直接消费。 */
internal data class HermesLinkEvent(val name: String, val data: JSONObject)

/**
 * Eta ⇄ Hermes 的唯一长连接。
 *
 * 一条 WebSocket 同时承载两件事：
 *  - 对话：上行 `message`，下行 `reply` / `reply.edit` / `typing`；
 *  - 工具：Hermes 下行 `tools.list` / `tool.call`，本机执行后回 `*.result`。
 *
 * 协议版本 2。登录凭据是配对换来的设备令牌（Bearer），不是 API Key。
 */
internal class HermesLink(
    context: Context,
    private val onStatusChanged: (Boolean) -> Unit = {},
) : AutoCloseable {

    private val appContext = context.applicationContext
    private val toolHost = HermesToolHost(appContext)
    private val client: OkHttpClient = HermesTransport
        .clientBuilder(HermesSettings.isTrustingSelfSigned(appContext))
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-hermes-tools").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "eta-hermes-link").apply { isDaemon = true }
    }

    private val closed = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val awaiting = ConcurrentHashMap<String, ReplySink>()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var url: String = ""

    @Volatile
    private var token: String = ""

    @Volatile
    private var retryDelayMs = 1_000L

    @Volatile
    private var currentSession: String = ""

    val isConnected: Boolean get() = connected.get() && socket != null

    // ------------------------------------------------------------------ //
    // lifecycle
    // ------------------------------------------------------------------ //

    fun connect(baseUrl: String, deviceToken: String) {
        if (closed.get()) return
        val nextUrl = websocketUrl(baseUrl)
        if (nextUrl.isBlank() || deviceToken.isBlank()) return
        if (nextUrl == url && deviceToken == token && isConnected) return
        url = nextUrl
        token = deviceToken
        runCatching { socket?.close(1000, "reconnect") }
        socket = null
        open()
    }

    private fun open() {
        if (closed.get() || url.isBlank() || token.isBlank()) return
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    private fun scheduleReconnect() {
        if (closed.get()) return
        val delay = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
        scheduler.schedule({ if (!closed.get()) open() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun send(payload: JSONObject): Boolean = socket?.send(payload.toString()) ?: false

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            retryDelayMs = 1_000L
            send(
                JSONObject()
                    .put("type", "hello")
                    .put("protocol", PROTOCOL)
                    .put("app", "eta")
                    .put("version", appVersion())
                    .put("device", android.os.Build.MODEL.orEmpty()),
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (frame.optString("type")) {
                "ready" -> {
                    connected.set(true)
                    onStatusChanged(true)
                    AndroidAgentLogger.info("Hermes link connected (device=${frame.optString("device_id")})")
                }

                "tools.list" -> worker.execute {
                    val reply = JSONObject()
                        .put("type", "tools.list.result")
                        .put("id", frame.optString("id"))
                        .put("tools", runCatching { toolHost.buildTools() }.getOrDefault(JSONArray()))
                    runCatching { send(reply) }
                }

                "tool.call" -> worker.execute {
                    val id = frame.optString("id")
                    val name = frame.optString("name")
                    val arguments = frame.optJSONObject("arguments") ?: JSONObject()
                    val reply = runCatching {
                        JSONObject()
                            .put("type", "tool.call.result")
                            .put("id", id)
                            .put("result", toolHost.call(name, arguments))
                    }.getOrElse { throwable ->
                        JSONObject()
                            .put("type", "tool.call.result")
                            .put("id", id)
                            .put(
                                "error",
                                JSONObject().put(
                                    "message",
                                    throwable.message ?: throwable.javaClass.simpleName,
                                ),
                            )
                    }
                    runCatching { send(reply) }
                }

                "reply", "reply.edit", "turn.done" -> deliver(frame)
                "tool.start", "tool.complete", "thinking" -> emitTool(frame)
                "typing" -> Unit
                "error" -> failAwaiting(frame.optString("error").ifBlank { "Hermes 返回错误" })
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AndroidAgentLogger.warn("Hermes link failure: ${t.javaClass.simpleName}: ${t.message}")
            onDisconnected("Hermes 连接已断开")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            onDisconnected("Hermes 连接已关闭（$code）")
        }
    }

    private fun onDisconnected(reason: String) {
        if (connected.getAndSet(false)) onStatusChanged(false)
        socket = null
        failAwaiting(reason)
        scheduleReconnect()
    }

    // ------------------------------------------------------------------ //
    // replies
    // ------------------------------------------------------------------ //

    private inner class ReplySink(
        private val latch: CountDownLatch,
        private val handler: (HermesLinkEvent) -> Boolean,
    ) {
        // One Hermes turn may deliver several messages (length splitting, notes after a tool).
        // They are paragraphs of the SAME reply: keyed by message_id, a later frame for the same
        // id REPLACES it (reply.edit), a new id appends. The turn closes on `turn.done`.
        private val paragraphs = LinkedHashMap<String, String>()
        private val merged = StringBuilder()
        private var done = false

        @Volatile
        var interrupted = false
            private set

        @Synchronized
        fun accept(frame: JSONObject) {
            if (done) return
            val id = frame.optString("message_id").ifBlank { "default" }
            val text = frame.optString("text")
            // A fresh-final re-send repeats the WHOLE reply under a new id.
            if (merged.isNotEmpty() && id !in paragraphs && text.startsWith(merged.toString())) {
                paragraphs.clear()
                merged.setLength(0)
            }
            paragraphs[id] = text
            var full = buildString { paragraphs.values.forEach(::append) }
            val common = commonPrefixLength(merged, full)
            if (common < merged.length) {
                // The text was rewritten or shrank (edit that is not an append): the client must
                // rebuild this block instead of appending a second copy.
                handler(HermesLinkEvent("assistant.reset", JSONObject()))
                merged.setLength(0)
                paragraphs.clear()
                paragraphs[id] = text
                full = text
            }
            if (common >= full.length) return
            val delta = full.substring(if (merged.isEmpty()) 0 else common)
            if (delta.isEmpty()) return
            merged.append(delta)
            if (!handler(HermesLinkEvent("assistant.delta", JSONObject().put("delta", delta)))) {
                done = true
                latch.countDown()
            }
        }

        private fun commonPrefixLength(a: CharSequence, b: CharSequence): Int {
            val max = minOf(a.length, b.length)
            var i = 0
            while (i < max && a[i] == b[i]) i++
            return i
        }

        @Synchronized
        fun complete() {
            if (done) return
            done = true
            handler(
                HermesLinkEvent(
                    "assistant.completed",
                    JSONObject().put("content", merged.toString()),
                ),
            )
            latch.countDown()
        }

        /** A side-channel event (tool card, thinking chunk) for this turn. */
        @Synchronized
        fun emit(name: String, data: JSONObject) {
            if (done) return
            handler(HermesLinkEvent(name, data))
        }

        @Synchronized
        fun fail(message: String) {
            if (done) return
            done = true
            handler(HermesLinkEvent("run.failed", JSONObject().put("error", message)))
            latch.countDown()
        }

        fun interrupt() {
            if (done) return
            done = true
            interrupted = true
            latch.countDown()
        }
    }

    private fun deliver(frame: JSONObject) {
        val session = frame.optString("session")
        val sink = awaiting[session.ifBlank { currentSession }] ?: return
        if (frame.optString("type") == "turn.done") sink.complete() else sink.accept(frame)
    }

    /** Tool cards and thinking chunks: same names the executor already understands. */
    private fun emitTool(frame: JSONObject) {
        val session = frame.optString("session")
        val sink = awaiting[session.ifBlank { currentSession }] ?: return
        when (frame.optString("type")) {
            "tool.start" -> sink.emit(
                "tool.started",
                JSONObject()
                    .put("tool_name", frame.optString("name"))
                    .put("args", frame.optJSONObject("args") ?: JSONObject()),
            )

            "tool.complete" -> sink.emit(
                "tool.completed",
                JSONObject()
                    .put("tool_name", frame.optString("name"))
                    .put("preview", frame.optString("preview")),
            )

            "thinking" -> sink.emit(
                "tool.progress",
                JSONObject()
                    .put("tool_name", "_thinking")
                    .put("delta", frame.optString("text")),
            )
        }
    }

    private fun failAwaiting(reason: String) {
        awaiting.values.forEach { it.fail(reason) }
    }

    /**
     * 发送一轮消息并阻塞消费回复，直到服务端给出 final 回复、连接断开或超时。
     *
     * 与旧 SSE 版本的签名保持一致，事件名沿用 `assistant.delta` / `assistant.completed`，
     * 因此执行器的事件翻译逻辑不需要重写。
     */
    fun streamChat(
        sessionId: String,
        text: String,
        images: List<AgentModelClient.ModelImage>,
        timeoutMs: Long = DEFAULT_STREAM_TIMEOUT_MS,
        handler: (HermesLinkEvent) -> Boolean,
    ) {
        if (!isConnected) throw IOException("Hermes 未连接，请检查服务地址与登录状态")
        val session = sessionId.ifBlank { "main" }
        currentSession = session
        val latch = CountDownLatch(1)
        val sink = ReplySink(latch, handler)
        awaiting[session] = sink
        try {
            val payload = JSONObject()
                .put("type", "message")
                .put("id", "m-${System.currentTimeMillis()}")
                .put("session", session)
                .put("text", text)
            val messageImages = buildImages(images)
            if (messageImages.length() > 0) payload.put("images", messageImages)
            if (!send(payload)) throw IOException("Hermes 连接已断开")
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IOException("Hermes 超过 ${timeoutMs / 60_000} 分钟没有回复")
            }
            if (sink.interrupted) throw CancellationException("Hermes 运行已取消")
        } finally {
            awaiting.remove(session)
        }
    }

    /** busy 时补发一句：服务端按 platform 语义把它并入当前这一轮（steer）或排队。 */
    fun steer(sessionId: String, text: String): Boolean {
        if (text.isBlank()) return false
        return send(
            JSONObject()
                .put("type", "message")
                .put("id", "s-${System.currentTimeMillis()}")
                .put("session", sessionId.ifBlank { currentSession })
                .put("text", text),
        )
    }

    fun cancelStream(sessionId: String) {
        awaiting[sessionId.ifBlank { currentSession }]?.interrupt()
    }

    private fun buildImages(
        images: List<AgentModelClient.ModelImage>,
    ): JSONArray {
        val output = JSONArray()
        images.filter { it.reference.isNotBlank() }.forEach { image ->
            output.put(JSONObject().put("url", image.reference))
        }
        return output
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connected.set(false)
        failAwaiting("Hermes 连接已关闭")
        runCatching { socket?.close(1000, "shutdown") }
        socket = null
        runCatching { toolHost.close() }
        worker.shutdownNow()
        scheduler.shutdownNow()
    }

    private fun appVersion(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    companion object {
        const val PROTOCOL = 2
        private const val DEFAULT_STREAM_TIMEOUT_MS = 30 * 60 * 1000L

        /** `https://host:9119/eta` → `wss://host:9119/eta/ws`。 */
        fun websocketUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            val withScheme = when {
                trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
                else -> "https://$trimmed"
            }
            return withScheme
                .replaceFirst("https://", "wss://")
                .replaceFirst("http://", "ws://") + "/ws"
        }
    }
}
