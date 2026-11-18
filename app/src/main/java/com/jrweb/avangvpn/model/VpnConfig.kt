package com.jrweb.avangvpn.model

data class VpnConfig(
    val id: String,
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val rawUri: String,
    var ping: Int = -1
)

data class SubscriptionInfo(
    val title: String = "اشتراک آونگ",
    val url: String = "",
    val uploadBytes: Long = 0L,
    val downloadBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val expireTimestamp: Long = 0L
) {
    val usedBytes: Long get() = uploadBytes + downloadBytes
    val remainingRatio: Float
        get() = if (totalBytes > 0) ((totalBytes - usedBytes).coerceAtLeast(0L).toFloat() / totalBytes.toFloat()) else 1f
}