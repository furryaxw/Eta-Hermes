package io.github.mangi.eta.agent.hermes

import android.annotation.SuppressLint
import io.github.mangi.eta.agent.model.AgentHttpClient
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Hermes 连接的 HTTP/WebSocket 客户端工厂。
 *
 * Hermes 常部署在自家机器上、用自签名或私有 CA 证书，Android 的信任锚里没有它。用户显式打开
 * 「信任自签名证书」后，只对 Hermes 这条连接跳过证书链校验；其他网络请求仍走系统默认策略。
 */
internal object HermesTransport {

    fun clientBuilder(trustSelfSigned: Boolean): OkHttpClient.Builder {
        val builder = AgentHttpClient.client.newBuilder()
        if (!trustSelfSigned) return builder
        val trustManager = InsecureTrustManager()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        return builder
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private class InsecureTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
