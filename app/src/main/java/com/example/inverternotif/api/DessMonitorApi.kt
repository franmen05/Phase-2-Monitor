package com.example.inverternotif.api

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.TreeMap
import java.util.concurrent.TimeUnit

/**
 * Cliente de la API de DessMonitor / Shinemonitor.
 * sign = SHA1(salt + secret + token + "&" + params_ordenados_alfabeticamente)
 */
class DessMonitorApi(
    private val token: String,
    private val secret: String,
    private val deviceSn: String   = "F60000220811312013099E01",
    private val devicePn: String   = "F60000220811312013",
    private val deviceAddr: String = "1",
    private val devcode: String    = "2462"
) {
    private val baseUrl = "https://web.shinemonitor.com/public/"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun sha1(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun buildSign(params: Map<String, String>, salt: Long): String {
        val sorted = TreeMap(params).entries.joinToString("&") { "${it.key}=${it.value}" }
        return sha1("$salt$secret$token&$sorted")
    }

    /**
     * Realiza una llamada a la API y devuelve el cuerpo de la respuesta como String.
     */
    suspend fun apiCall(action: String, extra: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val salt = System.currentTimeMillis()
            val params = buildMap {
                put("action", action)
                put("source", "1")
                put("i18n", "en_US")
                putAll(extra)
            }
            val sign = buildSign(params, salt)

            val url = baseUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("sign",  sign)
                addQueryParameter("salt",  salt.toString())
                addQueryParameter("token", token)
                params.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "InverterNotif/1.0 Android")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string() ?: "{}"
                    
                    if (!response.isSuccessful) {
                        Log.e("DessMonitorApi", "Error HTTP ${response.code} for action: $action")
                        throw Exception("HTTP Error: ${response.code}")
                    }
                    
                    Log.d("DessMonitorApi", "Success action: $action")
                    bodyString
                }
            } catch (e: Exception) {
                Log.e("DessMonitorApi", "Failed to execute $action", e)
                throw e
            }
        }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    suspend fun getRealtimeData() : String {

        val apiCall = apiCall("querySPDeviceLastData", deviceParams())

        return apiCall
    }

    suspend fun getEnergyFlow() = apiCall("webQueryDeviceEnergyFlowEs", deviceParams())

    suspend fun getDeviceInfo() = apiCall("queryDeviceParsEs", deviceParams())

    suspend fun getPlantInfo() = apiCall("queryPlantsInfoEs")

    suspend fun getDayData(date: String) = apiCall(
        "queryDeviceDataOneDayPaging",
        deviceParams() + mapOf("date" to date, "pageIndex" to "1", "pageSize" to "100")
    )

    private fun deviceParams() = mapOf(
        "pn"      to devicePn,
        "devcode" to devcode,
        "devaddr" to deviceAddr,
        "sn"      to deviceSn
    )
}
