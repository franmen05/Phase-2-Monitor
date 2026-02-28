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

    // Credenciales por defecto
    private var currentSign = "c5ee741261a8a63079c9880fc6b9133cfdabf5de"
    private var currentSalt = "1771556026330"
    private var currentToken = "CNd0589ca8-a92f-4841-8bfc-28ecee9a314e"

    companion object {
        const val FOREGROUND_CHANNEL_ID = "inverter_foreground_channel"
        const val NOTIFICATION_CHANNEL_ID = "inverter_channel"
        const val ACTION_STATUS_UPDATE = "com.example.inverternotif.STATUS_UPDATE"
        const val ACTION_UPDATE_CREDENTIALS = "com.example.inverternotif.UPDATE_CREDENTIALS"
        
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_SIGN = "extra_sign"
        const val EXTRA_SALT = "extra_salt"
        const val EXTRA_TOKEN = "extra_token"

        const val EXTRA_DELAY = "extra_delay"
        const val EXTRA_POWER_ON_MESSAGE = "extra_power_on_message"
        const val EXTRA_POWER_OFF_MESSAGE = "extra_power_off_message"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sharedPreferences = getSharedPreferences("InverterNotifPrefs", Context.MODE_PRIVATE)
        
        fetchDataDelay = intent?.getLongExtra(EXTRA_DELAY, 0L)?.takeIf { it > 0 }
            ?: sharedPreferences.getString("pollingInterval", "60")?.toLongOrNull()?.times(1000) 
            ?: 60000L
            
        powerOnMessage = intent?.getStringExtra(EXTRA_POWER_ON_MESSAGE)
            ?: sharedPreferences.getString("powerOnMessage", "Llegó la luz") 
            ?: "Llegó la luz"
            
        powerOffMessage = intent?.getStringExtra(EXTRA_POWER_OFF_MESSAGE)
            ?: sharedPreferences.getString("powerOffMessage", "Adiós luz") 
            ?: "Adiós luz"

        currentSign = sharedPreferences.getString("api_sign", currentSign) ?: currentSign
        currentSalt = sharedPreferences.getString("api_salt", currentSalt) ?: currentSalt
        currentToken = sharedPreferences.getString("api_token", currentToken) ?: currentToken

        if (intent?.action == ACTION_UPDATE_CREDENTIALS) {
            val newSign = intent.getStringExtra(EXTRA_SIGN)
            val newSalt = intent.getStringExtra(EXTRA_SALT)
            val newToken = intent.getStringExtra(EXTRA_TOKEN)

            val editor = sharedPreferences.edit()
            if (newSign != null) {
                currentSign = newSign
                editor.putString("api_sign", newSign)
            }
            if (newSalt != null) {
                currentSalt = newSalt
                editor.putString("api_salt", newSalt)
            }
            if (newToken != null) {
                currentToken = newToken
                editor.putString("api_token", newToken)
            }
            editor.apply()
        }

        startForegroundService()

        serviceScope.coroutineContext.cancelChildren()
        serviceScope.launch {
            while (isActive) {
                fetchData()
                delay(fetchDataDelay)
            }
        }

        return START_STICKY
    }

    private fun getBatteryPercentage(voltage: Float): Int {
        // Detectar si el sistema es de 12V, 24V o 48V
        val multiplier = when {
            voltage > 40f -> 4f
            voltage > 20f -> 2f
            else -> 1f
        }
        
        // Voltaje normalizado a escala de 12V (4 celdas LiFePO4)
        val v = voltage / multiplier
        
        // Tabla de referencia LiFePO4 ajustada para CARGA MODERADA (aprox 0.1C - 0.2C)
        return when {
            v >= 13.4f -> 100
            v >= 13.2f -> 95
            v >= 13.1f -> 90
            v >= 13.05f -> 80
            v >= 13.0f -> 70
            v >= 12.95f -> 60
            v >= 12.9f -> 50
            v >= 12.85f -> 40
            v >= 12.8f -> 30
            v >= 12.7f -> 20
            v >= 12.4f -> 10
            v >= 12.0f -> 5
            else -> 0
        }
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createForegroundNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Inverter Background Service"
            val descriptionText = "Shows that the app is checking the inverter status in the background"
            val importance = NotificationManager.IMPORTANCE_LOW
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
        val url = "https://web.shinemonitor.com/public/?sign=$currentSign&salt=$currentSalt&token=$currentToken&action=queryDeviceParsEs&source=1&devcode=2462&pn=F60000220811312013&devaddr=1&sn=F60000220811312013099E01&i18n=en_US"

        val stringRequest = StringRequest(
            Request.Method.GET, url,
            { response ->
                val jsonObject = JSONObject(response)
                val errCode = jsonObject.optInt("err", -1)
                if (errCode != 0) {
                    val desc = jsonObject.optString("desc", "Error desconocido")
                    broadcastStatus("Error API: $desc")
                    return@StringRequest
                }

                var fullStatus: String
                if (jsonObject.has("dat")) {
                    val data = jsonObject.getJSONObject("dat")
                    val parameterArray = data.getJSONArray("parameter")
                    val statusBuilder = StringBuilder()
                    var allValuesZero = parameterArray.length() > 0
                    var inputVoltageValue: Float? = null
                    var batteryVoltage: Float? = null

                    for (i in 0 until parameterArray.length()) {
                        val item = parameterArray.getJSONObject(i)
                        val name = item.getString("name")
                        val value = item.getString("val")
                        val unit = item.getString("unit")
                        
                        val floatVal = value.toFloatOrNull()
                        
                        // Detectar Voltaje de Batería (buscando variantes comunes)
                        if (name.contains("Battery Voltage", ignoreCase = true) || 
                            name.contains("Vbatt", ignoreCase = true) ||
                            name.contains("Battery Volt", ignoreCase = true)) {
                            batteryVoltage = floatVal
                        }

                        if (name == "Input Voltage") {
                            inputVoltageValue = floatVal
                        }

                        statusBuilder.append("$name: $value $unit\n")

                        if (floatVal != null && floatVal != 0f) {
                            allValuesZero = false
                        }
                    }

                    // Calcular y añadir porcentaje de batería si se encontró el voltaje
                    batteryVoltage?.let {
                        val percentage = getBatteryPercentage(it)
                        statusBuilder.append("\nBatería: $percentage% (Estimado LiFePO4 bajo carga)\n")
                    }

                    if (allValuesZero) {
                        if (!inverterIsOffline) {
                            sendStateChangeNotification("Inverter Status", "Inversor offline")
                            inverterIsOffline = true
                        }
                    } else {
                        inverterIsOffline = false
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

                    fullStatus = if (statusBuilder.isNotBlank()) statusBuilder.toString() else "No parameters found."
                } else {
                    fullStatus = "Error: Invalid response structure."
                }
                broadcastStatus(fullStatus)
            },
            { error ->
                if (!inverterIsOffline) {
                    sendStateChangeNotification("Inverter Status", "Inversor offline")
                    inverterIsOffline = true
                }
                broadcastStatus("Error de conexión.")
            })

        stringRequest.retryPolicy = DefaultRetryPolicy(60000, 1, 1f)
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
            val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

            with(getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager) {
                notify(2, builder.build())
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
