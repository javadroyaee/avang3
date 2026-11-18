package com.jrweb.avangvpn.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.format.Formatter
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.jrweb.avangvpn.data.StorageManager
import com.jrweb.avangvpn.model.VpnConfig
import com.jrweb.avangvpn.network.SubParser
import com.jrweb.avangvpn.service.AvangVpnService
import com.jrweb.avangvpn.ui.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

private fun isSystemVpnActive(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val activeNetwork = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

@Composable
fun MainScreen(onConnectRequest: (VpnConfig) -> Unit, onDisconnectRequest: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val storage = remember { StorageManager(context) }

    val serviceConnected by AvangVpnService.isRunningFlow.collectAsState()
    val serviceError by AvangVpnService.lastErrorFlow.collectAsState()

    var subInfo by remember { mutableStateOf(storage.loadSubscription()) }
    var configs by remember { mutableStateOf(storage.loadConfigs()) }
    var activeConfigId by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("آماده اتصال") }
    var isBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (isSystemVpnActive(context) && !serviceConnected) {
            AvangVpnService.isRunningFlow.value = true
            statusText = "متصل"
        }
    }

    LaunchedEffect(serviceError) {
        serviceError?.let { statusText = it }
    }

    fun importNewSubscription(rawInput: String) {
        scope.launch {
            isBusy = true
            statusText = "در حال دریافت و جایگزینی کانفیگ‌ها..."
            try {
                if (serviceConnected || isSystemVpnActive(context)) {
                    onDisconnectRequest()
                    activeConfigId = null
                    delay(300)
                }
                val (info, newConfigs) = SubParser.fetchSubscription(rawInput)
                subInfo = info
                configs = newConfigs
                storage.saveSubscription(info)
                storage.saveConfigs(newConfigs)
                statusText = "${newConfigs.size} کانفیگ جدید ذخیره شد"
            } catch (e: Exception) {
                statusText = "خطا: ${e.localizedMessage ?: "لینک نامعتبر"}"
            } finally { isBusy = false }
        }
    }

    fun runAllInOneConnect() {
        if (serviceConnected || isSystemVpnActive(context)) {
            onDisconnectRequest()
            activeConfigId = null
            statusText = "اتصال قطع شد"
            return
        }
        scope.launch {
            isBusy = true
            val currentUrl = subInfo?.url ?: ""
            if (currentUrl.isNotEmpty()) {
                statusText = "۱/۳ در حال بروزرسانی ساب‌لینک..."
                try {
                    val (updatedInfo, updatedConfigs) = SubParser.fetchSubscription(currentUrl)
                    subInfo = updatedInfo
                    configs = updatedConfigs
                    storage.saveSubscription(updatedInfo)
                    storage.saveConfigs(updatedConfigs)
                } catch (_: Exception) { statusText = "خطا در آپدیت، استفاده از لیست ذخیره‌شده..." }
            }
            if (configs.isEmpty()) {
                statusText = "هیچ کانفیگی وجود ندارد! لینک را اضافه کنید."
                isBusy = false
                return@launch
            }
            statusText = "۲/۳ در حال تست پینگ واقعی سرورها..."
            val pingedList = configs.map { cfg ->
                async { cfg.copy(ping = SubParser.measureRealProxyPing(cfg)) }
            }.awaitAll().sortedBy { if (it.ping > 0) it.ping else Int.MAX_VALUE }

            configs = pingedList
            storage.saveConfigs(pingedList)

            val bestConfig = pingedList.firstOrNull { it.ping > 0 }
            if (bestConfig != null) {
                statusText = "۳/۳ اتصال به ${bestConfig.name} (${bestConfig.ping}ms)"
                activeConfigId = bestConfig.id
                onConnectRequest(bestConfig)
            } else {
                statusText = "هیچ سروری به تست پروکسی پاسخ نداد!"
            }
            isBusy = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(DarkBg).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "آونگ وی‌پی‌ان", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = {
                        val clip = clipboardManager.getText()?.text
                        if (!clip.isNullOrBlank()) importNewSubscription(clip) else statusText = "کلیپ‌بورد خالی است!"
                    },
                    modifier = Modifier.background(CardBg, CircleShape)
                ) { Icon(Icons.Rounded.ContentPaste, contentDescription = "کلیپ‌بورد", tint = AccentCyan) }

                IconButton(
                    onClick = {
                        GmsBarcodeScanning.getClient(context).startScan().addOnSuccessListener { barcode ->
                            barcode.rawValue?.let { importNewSubscription(it) }
                        }
                    },
                    modifier = Modifier.background(CardBg, CircleShape)
                ) { Icon(Icons.Rounded.QrCodeScanner, contentDescription = "اسکن QR", tint = AccentCyan) }

                if (configs.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            if (serviceConnected || isSystemVpnActive(context)) onDisconnectRequest()
                            activeConfigId = null; configs = emptyList(); subInfo = null
                            storage.clearAll()
                            statusText = "تمام کانفیگ‌ها پاک شدند"
                        },
                        modifier = Modifier.background(CardBg, CircleShape)
                    ) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "پاک کردن", tint = DangerRed) }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        subInfo?.let { info ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = info.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        val daysLeft = if (info.expireTimestamp > 0) {
                            val diff = info.expireTimestamp - (System.currentTimeMillis() / 1000)
                            if (diff > 0) "${TimeUnit.SECONDS.toDays(diff)} روز باقی‌مانده" else "منقضی شده"
                        } else "نامحدود"
                        Text(text = daysLeft, color = AccentCyan, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val usedStr = Formatter.formatShortFileSize(context, info.usedBytes)
                    val totalStr = if (info.totalBytes > 0) Formatter.formatShortFileSize(context, info.totalBytes) else "∞"
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(text = "مصرف‌شده: $usedStr", color = TextSecondary, fontSize = 12.sp)
                        Text(text = "حجم کل: $totalStr", color = TextSecondary, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(DarkBg)) {
                        Box(modifier = Modifier.fillMaxWidth(info.remainingRatio).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                            .background(if (info.remainingRatio > 0.2f) AccentCyan else DangerRed))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        val buttonColor by animateColorAsState(
            targetValue = when { serviceConnected -> ConnectedGreen; isBusy -> Color(0xFFFFB300); else -> AccentCyan }, label = "btn"
        )
        Box(
            modifier = Modifier.size(135.dp).clip(CircleShape).background(buttonColor.copy(alpha = 0.15f))
                .border(3.dp, buttonColor, CircleShape).clickable(enabled = !isBusy) { runAllInOneConnect() },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (serviceConnected) Icons.Rounded.PowerSettingsNew else Icons.Rounded.Bolt, null, tint = buttonColor, modifier = Modifier.size(44.dp))
                Spacer(modifier = Modifier.height(4.dp))
                Text(if (serviceConnected) "متصل" else if (isBusy) "صبر کنید" else "اتصال خودکار", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(text = statusText, color = if (serviceError != null) DangerRed else TextSecondary, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(14.dp))

        if (configs.isNotEmpty()) {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(configs, key = { it.id }) { config ->
                    val isSelected = config.id == activeConfigId && serviceConnected
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) ConnectedGreen.copy(alpha = 0.15f) else CardBg)
                            .border(if (isSelected) 1.5.dp else 0.dp, if (isSelected) ConnectedGreen else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable {
                                activeConfigId = config.id; statusText = "متصل به ${config.name}"
                                onConnectRequest(config)
                            }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(config.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(config.protocol, color = TextSecondary, fontSize = 11.sp)
                        }
                        val pingText = when { config.ping > 0 -> "${config.ping} ms"; config.ping == -2 -> "تایم‌اوت"; else -> "---" }
                        val pingColor = when { config.ping in 1..600 -> ConnectedGreen; config.ping > 600 -> Color(0xFFFFB300); config.ping == -2 -> DangerRed; else -> TextSecondary }
                        Text(pingText, color = pingColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}