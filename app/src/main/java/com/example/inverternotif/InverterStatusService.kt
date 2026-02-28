package com.example.inverternotif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.android.volley.DefaultRetryPolicy
import com.android.volley.Request
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import kotlinx.coroutines.*
import org.json.JSONObject

class InverterStatusService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var sharedPreferences: SharedPreferences
    private var lastInputVoltage: Float? = null
    private var fetchDataDelay = 60000L
    private var powerOnMessage = "Llegó la luz"
    private var powerOffMessage = "Adiós luz"
    private var inverterIsOffline = false

    companion object {
        const val FOREGROUND_CHANNEL_ID = "inverter_foreground_channel"
        const val NOTIFICATION_CHANNEL_ID = "inverter_channel" // Same as MainActivity for state change notifs
        const val ACTION_STATUS_UPDATE = "com.example.inverternotif.STATUS_UPDATE"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_DELAY = "extra_delay"
        const val EXTRA_POWER_ON_MESSAGE = "extra_power_on_message"
        const val EXTRA_POWER_OFF_MESSAGE = "extra_power_off_message"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sharedPreferences = getSharedPreferences("InverterNotifPrefs", Context.MODE_PRIVATE)
        fetchDataDelay = sharedPreferences.getString("pollingInterval", "60")?.toLongOrNull()?.times(1000) ?: 60000L
        powerOnMessage = sharedPreferences.getString("powerOnMessage", "Llegó la luz") ?: "Llegó la luz"
        powerOffMessage = sharedPreferences.getString("powerOffMessage", "Adiós luz") ?: "Adiós luz"

        startForegroundService()

        serviceScope.launch {
            while (isActive) {
                fetchData()
                delay(fetchDataDelay)
            }
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        createForegroundNotificationChannel()
        val notification = createForegroundNotification("Checking inverter status...")
        startForeground(1, notification)
    }

    private fun createForegroundNotification(text: String): Notification {
        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("Inverter Notif")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Low priority for foreground service notification
            .build()
    }

    private fun createForegroundNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Inverter Background Service"
            val descriptionText = "Shows that the app is checking the inverter status in the background"
            val importance = NotificationManager.IMPORTANCE_LOW // Low importance to be less intrusive
            val channel = NotificationChannel(FOREGROUND_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun fetchData() {
        val queue = Volley.newRequestQueue(this)
        val url = "https://web.shinemonitor.com/public/?sign=c5ee741261a8a63079c9880fc6b9133cfdabf5de&salt=1771556026330&token=CNd0589ca8-a92f-4841-8bfc-28ecee9a314e&action=queryDeviceParsEs&source=1&devcode=2462&pn=F60000220811312013&devaddr=1&sn=F60000220811312013099E01&i18n=en_US"

        val stringRequest = StringRequest(
            Request.Method.GET, url,
            { response ->
                val jsonObject = JSONObject(response)
                var fullStatus: String
                if (jsonObject.has("dat")) {
                    val data = jsonObject.getJSONObject("dat")
                    val parameterArray = data.getJSONArray("parameter")
                    val statusBuilder = StringBuilder()
                    var allValuesZero = parameterArray.length() > 0
                    var inputVoltageValue: Float? = null

                    for (i in 0 until parameterArray.length()) {
                        val item = parameterArray.getJSONObject(i)
                        val name = item.getString("name")
                        val value = item.getString("val")
                        val unit = item.getString("unit")
                        statusBuilder.append("$name: $value $unit\n")

                        val floatVal = value.toFloatOrNull()
                        if (floatVal != null && floatVal != 0f) {
                            allValuesZero = false
                        }

                        if (name == "Input Voltage") {
                            inputVoltageValue = floatVal
                        }
                    }

                    if (allValuesZero) {
                        if (!inverterIsOffline) {
                            sendStateChangeNotification("Inverter Status", "Inversor offline")
                            inverterIsOffline = true
                        }
                    } else {
                        inverterIsOffline = false
                        // Only check for power on/off if the inverter is online
                        if (inputVoltageValue != null) {
                            val previousVoltage = lastInputVoltage
                            val hadPower = previousVoltage?.let { it > 0f }
                            val hasPower = inputVoltageValue > 0f

                            if (hadPower == null || hadPower != hasPower) {
                                if (hasPower) {
                                    sendStateChangeNotification("Inverter Status", powerOnMessage)
                                } else {
                                    sendStateChangeNotification("Inverter Status", powerOffMessage)
                                }
                            }
                            lastInputVoltage = inputVoltageValue
                        }
                    }

                    fullStatus = if (statusBuilder.isNotBlank()) statusBuilder.toString() else "No parameters found in response."
                } else {
                    fullStatus = "Error: Invalid response from server."
                    Log.e("JsonError", "Response does not contain 'dat' key. Response: $response")
                }
                broadcastStatus(fullStatus)
            },
            { error ->
                if (!inverterIsOffline) {
                    sendStateChangeNotification("Inverter Status", "Inversor offline")
                    inverterIsOffline = true
                }
                val errorMsg = "Error fetching data. Please try again."
                Log.e("VolleyError", "Error: ${error.message}")
                broadcastStatus(errorMsg)
            })

        stringRequest.retryPolicy = DefaultRetryPolicy(
            60000,
            DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
            DefaultRetryPolicy.DEFAULT_BACKOFF_MULT
        )
        queue.add(stringRequest)
    }

    private fun broadcastStatus(status: String) {
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS, status)
        }
        sendBroadcast(intent)
    }

    private fun sendStateChangeNotification(title: String, message: String) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            // This uses the main notification channel from MainActivity to show state changes
            val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            with(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager) {
                notify(2, builder.build()) // Use a different ID than the foreground service notification
            }
        }
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
