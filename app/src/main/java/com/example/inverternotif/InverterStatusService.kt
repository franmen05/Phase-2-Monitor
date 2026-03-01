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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.inverternotif.api.DessMonitorRepository
import com.example.inverternotif.ui.SessionManager
import kotlinx.coroutines.*

class InverterStatusService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var sessionManager: SessionManager
    private var repository: DessMonitorRepository? = null
    
    private var lastInputVoltage: Float? = null
    private var fetchDataDelay = 60000L
    private var powerOnMessage = "Llegó la luz"
    private var powerOffMessage = "Adiós luz"
    private var inverterIsOffline = false

    companion object {
        const val FOREGROUND_CHANNEL_ID = "inverter_foreground_channel"
        const val NOTIFICATION_CHANNEL_ID = "inverter_channel"
        const val ACTION_STATUS_UPDATE = "com.example.inverternotif.STATUS_UPDATE"
        
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_DELAY = "extra_delay"
        const val EXTRA_POWER_ON_MESSAGE = "extra_power_on_message"
        const val EXTRA_POWER_OFF_MESSAGE = "extra_power_off_message"
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("InverterNotifPrefs", Context.MODE_PRIVATE)
        sessionManager = SessionManager(this)
        
        val apiClient = sessionManager.createApiClient()
        if (apiClient != null) {
            repository = DessMonitorRepository(apiClient)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        fetchDataDelay = intent?.getLongExtra(EXTRA_DELAY, 0L)?.takeIf { it > 0 }
            ?: sharedPreferences.getString("pollingInterval", "60")?.toLongOrNull()?.times(1000) 
            ?: 60000L
            
        powerOnMessage = intent?.getStringExtra(EXTRA_POWER_ON_MESSAGE)
            ?: sharedPreferences.getString("powerOnMessage", "Llegó la luz") 
            ?: "Llegó la luz"
            
        powerOffMessage = intent?.getStringExtra(EXTRA_POWER_OFF_MESSAGE)
            ?: sharedPreferences.getString("powerOffMessage", "Adiós luz") 
            ?: "Adiós luz"

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

    private suspend fun fetchData() {
        val repo = repository ?: return
        
        val realtimeResult = repo.getRealtimeData()

        realtimeResult.onSuccess { response ->
            if (response.isSuccess) {
                val statusBuilder = StringBuilder()
                var inputVoltageValue: Float? = null
                var batteryVoltage: Float? = null
                var allValuesZero = true

                response.dat?.pars?.allParams()?.forEach { param ->
                    val name = param.name
                    val value = param.value
                    val unit = param.unit
                    val floatVal = value.toFloatOrNull()

                    if (name.contains("Battery Voltage", ignoreCase = true) || 
                        name.contains("Vbatt", ignoreCase = true) ||
                        name.contains("Battery volt", ignoreCase = true)) {
                        batteryVoltage = floatVal
                    }
                    if (name == "Input Voltage") {
                        inputVoltageValue = floatVal
                    }

                    val displayName = if (param.id == "bc_load_voltage") "Output Voltage" else name
                    statusBuilder.append("$displayName: $value $unit\n")
                    
                    if (floatVal != null && floatVal != 0f) allValuesZero = false
                }

                batteryVoltage?.let {
                    val percentage = calculateBatteryPercentage(it)
                    statusBuilder.append("\nBatería: $percentage% (Estimado LiFePO4)\n")
                }

                if (allValuesZero) {
                    if (!inverterIsOffline) {
                        sendStateChangeNotification("Inverter Status", "Inversor offline")
                        inverterIsOffline = true
                    }
                } else {
                    inverterIsOffline = false
                    inputVoltageValue?.let { currentVoltage ->
                        val hadPower = lastInputVoltage?.let { it > 0f }
                        val hasPower = currentVoltage > 0f

                        if (hadPower == null || hadPower != hasPower) {
                            sendStateChangeNotification("Inverter Status", if (hasPower) powerOnMessage else powerOffMessage)
                        }
                        lastInputVoltage = currentVoltage
                    }
                }
                broadcastStatus(statusBuilder.toString())
            } else {
                broadcastStatus("Error API: ${response.desc}")
            }
        }.onFailure {
            if (!inverterIsOffline) {
                sendStateChangeNotification("Inverter Status", "Inversor offline")
                inverterIsOffline = true
            }
            broadcastStatus("Error de conexión.")
        }
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
