package com.jrweb.avangvpn.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jrweb.avangvpn.model.SubscriptionInfo
import com.jrweb.avangvpn.model.VpnConfig

class StorageManager(context: Context) {
    private val prefs = context.getSharedPreferences("avang_vpn_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveConfigs(configs: List<VpnConfig>) {
        prefs.edit().putString("configs_json", gson.toJson(configs)).apply()
    }

    fun loadConfigs(): List<VpnConfig> {
        val json = prefs.getString("configs_json", null) ?: return emptyList()
        val type = object : TypeToken<List<VpnConfig>>() {}.type
        return try { gson.fromJson(json, type) ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    fun saveSubscription(info: SubscriptionInfo?) {
        if (info == null) prefs.edit().remove("sub_info_json").apply()
        else prefs.edit().putString("sub_info_json", gson.toJson(info)).apply()
    }

    fun loadSubscription(): SubscriptionInfo? {
        val json = prefs.getString("sub_info_json", null) ?: return null
        return try { gson.fromJson(json, SubscriptionInfo::class.java) } catch (_: Exception) { null }
    }

    fun clearAll() { prefs.edit().clear().apply() }
}