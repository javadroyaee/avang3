package com.jrweb.avangvpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.jrweb.avangvpn.model.VpnConfig
import com.jrweb.avangvpn.service.AvangVpnService
import com.jrweb.avangvpn.ui.MainScreen
import com.jrweb.avangvpn.ui.theme.AvangVpnTheme

class MainActivity : ComponentActivity() {

    private var pendingConfig: VpnConfig? = null

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) pendingConfig?.let { startVpnService(it) }
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            AvangVpnTheme {
                MainScreen(
                    onConnectRequest = { config -> prepareAndConnect(config) },
                    onDisconnectRequest = {
                        startService(Intent(this, AvangVpnService::class.java).apply { action = AvangVpnService.ACTION_DISCONNECT })
                    }
                )
            }
        }
    }

    private fun prepareAndConnect(config: VpnConfig) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingConfig = config
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService(config)
        }
    }

    private fun startVpnService(config: VpnConfig) {
        val intent = Intent(this, AvangVpnService::class.java).apply {
            action = AvangVpnService.ACTION_CONNECT
            putExtra(AvangVpnService.EXTRA_CONFIG_URI, config.rawUri)
        }
        startForegroundService(intent)
    }
}