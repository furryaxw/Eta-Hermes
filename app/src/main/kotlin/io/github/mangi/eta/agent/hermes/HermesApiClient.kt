package io.github.mangi.eta.agent.hermes

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Hermes 请求失败（非 2xx）。 */
internal class HermesApiException(
    val statusCode: Int,
    message: String,
) : IOException(message)

/**
 * Hermes 平台客户端。
 *
 * Eta 现在以 gateway 的 `eta` platform 接入：对话和工具走同一条 WebSocket（[HermesLink]），
 * 认证是配对换来的设备令牌。这个类只保留执行器需要的那几个动作，避免改动上层。
 */
internal class HermesApiClient(
    context: Context,
    baseUrl: String,
    private val authToken: String,
    trustSelfSigned: Boolean = false,
) {
    private val appContext = context.applicationContext
    private val baseUrl = HermesSettings.normalizeBaseUrl(baseUrl)
    private val link: HermesLink = HermesLinkHolder.link(appContext)
    private val rpc: HermesRpc = HermesRpcHolder.link(appContext)
    private val http: OkHttpClient = HermesTransport.clientBuilder(trustSelfSigned)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    init {
        // platform 那条：手机工具 + 主动推送。幂等。
        link.connect(this.baseUrl, authToken)
        // dashboard 那条：完整会话功能（会话/思考/工具事件/重试）。
        runCatching { rpc.connect(this.baseUrl, authToken) }
            .onFailure { AndroidAgentLogger.warn("Hermes RPC connect failed: ${it.message}") }
    }

    /** 探活：走 `/eta/healthz`，返回 HTTP 状态码供设置页判断地址与凭据是否可用。 */
    fun capabilitiesStatus(): Int = try {
        val request = Request.Builder()
            .url("$baseUrl/healthz")
            .header("Authorization", "Bearer $authToken")
            .build()
        http.newCall(request).execute().use { return it.code }
    } catch (throwable: Throwable) {
        AndroidAgentLogger.warn(
            "Hermes health probe failed: ${throwable.javaClass.simpleName}: ${throwable.message}",
        )
        throw throwable
    }

    /** 平台模式下会话由 Hermes 按 chat_id 维护，不需要预建。 */
    fun ensureSession(sessionId: String, title: String? = null) = Unit

    /**
     * 发送一轮消息并同步消费回复，直到服务端给出 final 回复。
     *
     * [handler] 返回 false 时主动停止消费。取消整轮请调用 [cancel]。
     */
    fun streamChat(
        sessionId: String,
        text: String,
        images: List<AgentModelClient.ModelImage>,
        rewindOrdinal: Int? = null,
        handler: (HermesLinkEvent) -> Boolean,
    ) {
        // 对话走 dashboard 的 tui_gateway：那边才有思考、工具事件和 Hermes 自己的重试语义。
        val forward: (HermesRpcEvent) -> Boolean = { event ->
            handler(HermesLinkEvent(event.name, event.data))
        }
        // 运行时给的是 content:// URI（本地模型能直接读），上传需要 base64。
        val uploadable = images.mapNotNull(::asDataUrlImage)
        if (rewindOrdinal != null) {
            // 编辑用户消息：先截断到该轮之前再提交新文本（Hermes 侧执行截断）。
            rpc.rewindAndSubmit(sessionId, rewindOrdinal, text, uploadable, handler = forward)
        } else {
            rpc.submit(sessionId, text, uploadable, handler = forward)
        }
    }

    /** `content://` / 文件路径 → data URL；已经是 data URL 的原样返回。 */
    private fun asDataUrlImage(image: AgentModelClient.ModelImage): AgentModelClient.ModelImage? {
        val reference = image.reference
        val bytes = if (reference.startsWith("data:")) {
            val comma = reference.indexOf(',')
            if (comma < 0) return null
            runCatching { android.util.Base64.decode(reference.substring(comma + 1), android.util.Base64.DEFAULT) }
                .getOrNull() ?: return null
        } else {
            runCatching {
                appContext.contentResolver.openInputStream(android.net.Uri.parse(reference))
                    ?.use { it.readBytes() }
            }.getOrNull()
                ?: runCatching { java.io.File(reference).readBytes() }.getOrNull()
                ?: return null
        }
        if (bytes.isEmpty()) return null
        val mime = image.mimeType.takeIf { it.startsWith("image/") }
            ?: runCatching {
                appContext.contentResolver.getType(android.net.Uri.parse(reference))
            }.getOrNull()?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"

        val (data, outMime) = compressForUpload(bytes, mime)
        val encoded = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        return image.copy(reference = "data:$outMime;base64,$encoded", mimeType = outMime, bytes = data.size)
    }

    /**
     * 有损压缩到模型看得清的上限。
     *
     * 手机照片动辄 4000×3000、几 MB，而模型识图拿不到那个分辨率：缩到 [MAX_IMAGE_EDGE]
     * 长边再按 JPEG 重编码，体积通常降到几百 KB，上行也更稳。
     */
    private fun compressForUpload(bytes: ByteArray, mime: String): Pair<ByteArray, String> {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return bytes to mime
        // 已经足够小：保留原编码（PNG 的透明度、精细截图都不必重压）。
        if (longest <= MAX_IMAGE_EDGE && bytes.size <= KEEP_AS_IS_BYTES) return bytes to mime

        var sample = 1
        while (longest / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
        val decoded = android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return bytes to mime

        val upright = applyExifRotation(decoded, bytes)
        val scaled = runCatching {
            val edge = maxOf(upright.width, upright.height)
            if (edge <= MAX_IMAGE_EDGE) {
                upright
            } else {
                val ratio = MAX_IMAGE_EDGE.toFloat() / edge
                android.graphics.Bitmap.createScaledBitmap(
                    upright,
                    (upright.width * ratio).toInt().coerceAtLeast(1),
                    (upright.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            }
        }.getOrDefault(upright)

        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, out)
        val compressed = out.toByteArray()
        // 压完反而更大（少见，比如小尺寸 PNG）就退回原图。
        return if (compressed.size >= bytes.size) bytes to mime else compressed to "image/jpeg"
    }

    /** 手机竖拍的照片方向记在 EXIF 里，直接缩放会让模型看到躺倒的图。 */
    private fun applyExifRotation(bitmap: android.graphics.Bitmap, bytes: ByteArray): android.graphics.Bitmap {
        val orientation = runCatching {
            android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
        val matrix = android.graphics.Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    /** 追加一句：服务端在 busy 时按 platform 语义并入当前轮或排队。 */
    fun steer(runId: String, text: String) {
        rpc.steer(text)
    }

    /** platform 模式没有独立审批接口：审批走聊天文本（/approve、/deny）。 */
    fun approve(runId: String, choice: String) {
        AndroidAgentLogger.info("Hermes approval handled over chat text: $choice")
    }

    /** 停止当前轮：让 Hermes 中断并放开本地等待。 */
    fun stop(runId: String) {
        rpc.cancel()
    }

    /** 取消正在进行的流（run 取消时调用）。 */
    fun cancel() {
        rpc.cancel()
    }

    private companion object {
        /** 模型识图拿不到更高分辨率，再大也只是被服务端缩。 */
        const val MAX_IMAGE_EDGE = 1568

        const val IMAGE_QUALITY = 85

        /** 尺寸不超限且小于这个体积就原样上传，避免无谓重编码。 */
        const val KEEP_AS_IS_BYTES = 300 * 1024
    }
}

/**
 * 进程级唯一的 [HermesLink]。
 *
 * 前台服务负责维持它；执行器和设置页通过这里取同一个连接，避免两条 WebSocket 互相顶掉
 * （adapter 只接受一条活动连接）。
 */
internal object HermesLinkHolder {

    @Volatile
    private var instance: HermesLink? = null

    @Volatile
    private var statusListener: ((Boolean) -> Unit)? = null

    fun setStatusListener(listener: ((Boolean) -> Unit)?) {
        statusListener = listener
    }

    fun link(context: Context): HermesLink = synchronized(this) {
        instance ?: HermesLink(context.applicationContext) { connected ->
            statusListener?.invoke(connected)
        }.also { instance = it }
    }

    fun peek(): HermesLink? = instance

    fun close() {
        synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
