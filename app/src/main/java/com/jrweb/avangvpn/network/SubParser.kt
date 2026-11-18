package com.jrweb.avangvpn.network

import android.net.Uri
import android.util.Base64
import com.jrweb.avangvpn.model.SubscriptionInfo
import com.jrweb.avangvpn.model.VpnConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit

object SubParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun fetchSubscription(input: String): Pair<SubscriptionInfo, List<VpnConfig>> = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val request = Request.Builder().url(trimmed).header("User-Agent", "v2rayNG/1.8.23").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("خطا در دریافت اشتراک: کد ${response.code}")
                val body = response.body?.string() ?: ""
                val subInfo = parseUserInfo(
                    response.header("Subscription-Userinfo") ?: "",
                    response.header("Profile-Title") ?: "",
                    trimmed
                )
                return@withContext Pair(subInfo, parseRawConfigs(body))
            }
        } else {
            return@withContext Pair(SubscriptionInfo(title = "کانفیگ مستقیم"), parseRawConfigs(trimmed))
        }
    }

    private fun parseUserInfo(header: String, titleHeader: String, url: String): SubscriptionInfo {
        var upload = 0L; var download = 0L; var total = 0L; var expire = 0L
        header.split(";").forEach { part ->
            val kv = part.trim().split("=")
            if (kv.size == 2) {
                when (kv[0].lowercase()) {
                    "upload" -> upload = kv[1].toLongOrNull() ?: 0L
                    "download" -> download = kv[1].toLongOrNull() ?: 0L
                    "total" -> total = kv[1].toLongOrNull() ?: 0L
                    "expire" -> expire = kv[1].toLongOrNull() ?: 0L
                }
            }
        }
        val title = when {
            titleHeader.startsWith("base64:") -> try { String(Base64.decode(titleHeader.removePrefix("base64:"), Base64.DEFAULT)) } catch (_: Exception) { "اشتراک فعال" }
            titleHeader.isNotEmpty() -> try { URLDecoder.decode(titleHeader, "UTF-8") } catch (_: Exception) { titleHeader }
            else -> "اشتراک آونگ"
        }
        return SubscriptionInfo(title, url, upload, download, total, expire)
    }

    fun parseRawConfigs(rawText: String): List<VpnConfig> {
        val decoded = try {
            if (!rawText.contains("://")) String(Base64.decode(rawText.trim(), Base64.DEFAULT)) else rawText
        } catch (_: Exception) { rawText }

        return decoded.lines().map { it.trim() }
            .filter { it.startsWith("vless://") || it.startsWith("vmess://") || it.startsWith("trojan://") || it.startsWith("ss://") }
            .mapNotNull { parseSingleUri(it) }
    }

    private fun parseSingleUri(uriString: String): VpnConfig? {
        return try {
            if (uriString.startsWith("vmess://")) {
                val json = JSONObject(String(Base64.decode(uriString.removePrefix("vmess://"), Base64.DEFAULT)))
                VpnConfig(UUID.randomUUID().toString(), json.optString("ps", "سرور VMess"), "VMess", json.getString("add"), json.getInt("port"), uriString)
            } else {
                val uri = Uri.parse(uriString)
                val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") } ?: "سرور ${uri.scheme?.uppercase()}"
                val host = uri.host ?: return null
                val port = if (uri.port != -1) uri.port else 443
                VpnConfig(UUID.randomUUID().toString(), name, uri.scheme?.uppercase() ?: "VPN", host, port, uriString)
            }
        } catch (_: Exception) { null }
    }

    private fun findFreePort(): Int = try { ServerSocket(0).use { it.localPort } } catch (_: Exception) { 10809 }

    suspend fun measureRealProxyPing(config: VpnConfig): Int = withContext(Dispatchers.IO) {
        return@withContext try {
            val xrayConfig = buildXrayJsonFromUri(config.rawUri, findFreePort())
            val delay = Libv2ray.measureOutboundDelay(xrayConfig, "https://www.gstatic.com/generate_204")
            if (delay > 0) delay.toInt() else -2
        } catch (_: Exception) { -2 }
    }

    fun buildXrayJsonFromUri(uriStr: String, socksPort: Int = 10808): String {
        val outbound = JSONObject()
        try {
            when {
                uriStr.startsWith("vless://") -> {
                    val uri = Uri.parse(uriStr)
                    val host = uri.host ?: ""
                    val security = uri.getQueryParameter("security") ?: "none"
                    val network = uri.getQueryParameter("type") ?: "tcp"
                    val sni = uri.getQueryParameter("sni") ?: host
                    val fp = uri.getQueryParameter("fp") ?: "chrome"
                    val allowInsecure = uri.getQueryParameter("allowInsecure") == "1"

                    outbound.put("protocol", "vless")
                    val user = JSONObject().put("id", uri.userInfo ?: "").put("encryption", "none").apply {
                        val flow = uri.getQueryParameter("flow") ?: ""
                        if (flow.isNotEmpty()) put("flow", flow)
                    }
                    outbound.put("settings", JSONObject().put("vnext", JSONArray().put(
                        JSONObject().put("address", host).put("port", if (uri.port != -1) uri.port else 443).put("users", JSONArray().put(user))
                    )))
                    outbound.put("streamSettings", JSONObject().apply {
                        put("network", network)
                        put("security", security)
                        if (security == "tls") {
                            put("tlsSettings", JSONObject().put("serverName", sni).put("fingerprint", fp).put("allowInsecure", allowInsecure))
                        } else if (security == "reality") {
                            put("realitySettings", JSONObject().put("serverName", sni).put("fingerprint", fp)
                                .put("publicKey", uri.getQueryParameter("pbk") ?: "")
                                .put("shortId", uri.getQueryParameter("sid") ?: ""))
                        }
                        if (network == "ws") {
                            put("wsSettings", JSONObject().put("path", uri.getQueryParameter("path") ?: "/")
                                .put("headers", JSONObject().put("Host", uri.getQueryParameter("host") ?: host)))
                        }
                    })
                }
                uriStr.startsWith("vmess://") -> {
                    val vmess = JSONObject(String(Base64.decode(uriStr.removePrefix("vmess://"), Base64.DEFAULT)))
                    outbound.put("protocol", "vmess")
                    val user = JSONObject().put("id", vmess.optString("id")).put("alterId", vmess.optInt("aid", 0)).put("security", vmess.optString("scy", "auto"))
                    outbound.put("settings", JSONObject().put("vnext", JSONArray().put(
                        JSONObject().put("address", vmess.optString("add")).put("port", vmess.optInt("port", 443)).put("users", JSONArray().put(user))
                    )))
                    val net = vmess.optString("net", "tcp")
                    outbound.put("streamSettings", JSONObject().apply {
                        put("network", net)
                        if (vmess.optString("tls") == "tls") {
                            put("security", "tls")
                            put("tlsSettings", JSONObject().put("serverName", vmess.optString("sni", vmess.optString("host")))
                                .put("allowInsecure", vmess.optInt("allowInsecure", 0) == 1))
                        }
                        if (net == "ws") {
                            put("wsSettings", JSONObject().put("path", vmess.optString("path", "/"))
                                .put("headers", JSONObject().put("Host", vmess.optString("host", ""))))
                        }
                    })
                }
                uriStr.startsWith("trojan://") -> {
                    val uri = Uri.parse(uriStr)
                    outbound.put("protocol", "trojan")
                    outbound.put("settings", JSONObject().put("servers", JSONArray().put(
                        JSONObject().put("address", uri.host ?: "").put("port", if (uri.port != -1) uri.port else 443).put("password", uri.userInfo ?: "")
                    )))
                    outbound.put("streamSettings", JSONObject().put("network", uri.getQueryParameter("type") ?: "tcp").put("security", "tls")
                        .put("tlsSettings", JSONObject().put("serverName", uri.getQueryParameter("sni") ?: uri.host)
                            .put("allowInsecure", uri.getQueryParameter("allowInsecure") == "1")))
                }
                uriStr.startsWith("ss://") -> {
                    val uri = Uri.parse(uriStr)
                    val userInfoRaw = uri.userInfo ?: ""
                    val decodedAuth = if (!userInfoRaw.contains(":")) String(Base64.decode(userInfoRaw, Base64.DEFAULT)) else userInfoRaw
                    val parts = decodedAuth.split(":", limit = 2)
                    outbound.put("protocol", "shadowsocks")
                    outbound.put("settings", JSONObject().put("servers", JSONArray().put(
                        JSONObject().put("address", uri.host ?: "").put("port", if (uri.port != -1) uri.port else 443)
                            .put("method", parts.getOrElse(0) { "aes-256-gcm" }).put("password", parts.getOrElse(1) { "" })
                    )))
                }
                else -> outbound.put("protocol", "freedom")
            }
        } catch (_: Exception) { outbound.put("protocol", "freedom") }

        return JSONObject().apply {
            put("log", JSONObject().put("loglevel", "warning"))
            put("inbounds", JSONArray().put(JSONObject().put("port", socksPort).put("listen", "127.0.0.1").put("protocol", "socks").put("settings", JSONObject().put("udp", true))))
            put("outbounds", JSONArray().put(outbound))
        }.toString()
    }
}