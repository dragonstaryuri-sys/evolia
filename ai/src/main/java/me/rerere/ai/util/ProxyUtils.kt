package me.rerere.ai.util

import me.rerere.rikkahub.common.utils.LogUtil
import me.rerere.ai.provider.ProviderProxy
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress
import java.net.Proxy

private const val TAG = "ProxyUtils"

fun OkHttpClient.configureClientWithProxy(proxyConfig: ProviderProxy): OkHttpClient {
    return when (proxyConfig) {
        is ProviderProxy.None -> this

        is ProviderProxy.Http -> {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyConfig.address, proxyConfig.port))
            val clientBuilder = this.newBuilder().proxy(proxy)

            LogUtil.d(TAG, "configureClientWithProxy: $proxy for ${proxyConfig.address}:${proxyConfig.port}")
            // 如果有用户名和密码，添加代理认证
            if (!proxyConfig.username.isNullOrEmpty() && !proxyConfig.password.isNullOrEmpty()) {
                clientBuilder.proxyAuthenticator { _: Route?, response: Response ->
                    val credential = Credentials.basic(proxyConfig.username, proxyConfig.password)
                    // 不打印凭据与完整 URL（Base64(用户名:密码) 落日志 = 凭据泄露；host 即可定位问题）
                    LogUtil.d(TAG) { "proxy auth challenge from ${response.request.url.host}" }
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }

            clientBuilder.build()
        }
    }
}
