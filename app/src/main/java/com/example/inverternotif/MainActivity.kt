package com.example.inverternotif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.inverternotif.ui.InverterViewModel
import com.example.inverternotif.ui.InverterViewModelFactory
import com.example.inverternotif.api.RealtimeResponse
import com.example.inverternotif.ui.SessionManager
import com.example.inverternotif.ui.theme.InverterNotifTheme
import kotlinx.coroutines.launch
import androidx.core.content.edit

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var sessionManager: SessionManager

    private val viewModel: InverterViewModel by viewModels {
        InverterViewModelFactory(sessionManager)
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startInverterService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationChannel()

        sharedPreferences = getSharedPreferences("InverterNotifPrefs", Context.MODE_PRIVATE)
        sessionManager = SessionManager(this)

        if (!sessionManager.hasSession()) {
            sessionManager.saveSession(
                token = "-8bfc-28ecee9a314e",
                secret = ""
            )
        }

        askNotificationPermission()

        setContent {
            InverterNotifTheme {
                val realtimeDataResult by viewModel.realtimeData.observeAsState()
                val isLoading by viewModel.isLoading.observeAsState(false)

                val savedPollingInterval = sharedPreferences.getString("pollingInterval", "60") ?: "60"
                val savedPowerOnMessage = sharedPreferences.getString("powerOnMessage", "LLego la luz") ?: "LLego la luz"
                val savedPowerOffMessage = sharedPreferences.getString("powerOffMessage", "Adios luz") ?: "Adios luz"

                var pollingInterval by remember { mutableStateOf(savedPollingInterval) }
                var powerOnMessage by remember { mutableStateOf(savedPowerOnMessage) }
                var powerOffMessage by remember { mutableStateOf(savedPowerOffMessage) }
                var isApplying by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("Inverter Notif") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                titleContentColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        InverterStatusCard(
                            realtimeDataResult = realtimeDataResult,
                            isLoading = isLoading,
                            onRefreshClick = { viewModel.refresh() }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        SettingsCard(
                            pollingInterval = pollingInterval,
                            onPollingIntervalChange = { pollingInterval = it },
                            powerOnMessage = powerOnMessage,
                            onPowerOnMessageChange = { powerOnMessage = it },
                            powerOffMessage = powerOffMessage,
                            onPowerOffMessageChange = { powerOffMessage = it }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    isApplying = true
                                    sharedPreferences.edit {
                                        putString("pollingInterval", pollingInterval)
                                        putString("powerOnMessage", powerOnMessage)
                                        putString("powerOffMessage", powerOffMessage)
                                    }
                                    startInverterService()
                                    isApplying = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isApplying
                        ) {
                            Text(if (isApplying) "Applying..." else "Save Settings & Restart Service")
                        }
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Inverter Status"
            val descriptionText = "Notifications for inverter status"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(InverterStatusService.NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startInverterService() {
        val savedPollingInterval = sharedPreferences.getString("pollingInterval", "60") ?: "60"
        val savedPowerOnMessage = sharedPreferences.getString("powerOnMessage", "LLego la luz") ?: "LLego la luz"
        val savedPowerOffMessage = sharedPreferences.getString("powerOffMessage", "Adios luz") ?: "Adios luz"
        val delay = savedPollingInterval.toLongOrNull()?.times(1000) ?: 60000L

        val intent = Intent(this, InverterStatusService::class.java).apply {
            putExtra(InverterStatusService.EXTRA_DELAY, delay)
            putExtra(InverterStatusService.EXTRA_POWER_ON_MESSAGE, savedPowerOnMessage)
            putExtra(InverterStatusService.EXTRA_POWER_OFF_MESSAGE, savedPowerOffMessage)
        }
        startForegroundService(intent)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startInverterService()
            }
        } else {
            startInverterService()
        }
    }
}

@Composable
fun InverterStatusCard(
    realtimeDataResult: Result<RealtimeResponse>?,
    isLoading: Boolean,
    onRefreshClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EAF6))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Inverter Status",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF5C627A)
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (isLoading) {
                CircularProgressIndicator(color = Color(0xFF5C627A))
            } else {
                var outputVoltage = "---"
                var batteryVoltage = "---"
                var inputVoltage = "---"
                var batteryPercentageInv = ""
                var batteryPercentage = ""

                realtimeDataResult?.onSuccess { response ->
                    response.dat?.pars?.inputVoltage?.forEach { param ->

                        when(param.id) {
                            "gd_grid_voltage" -> {
                                inputVoltage = "${param.value} ${param.unit}"
                            }
                        }
                    }

                    response.dat?.pars?.outputVoltage?.forEach { param ->
                        when(param.id) {
                            "bc_load_voltage" -> {
                                outputVoltage = "${param.value} ${param.unit}"
                            }
                        }
                    }

                    response.dat?.pars?.battery?.forEach { param ->
                        when (param.id) {
                            "bt_voltage" -> {

                                batteryVoltage = "${param.value} ${param.unit}"
                                param.value.toFloatOrNull()?.let { v ->
                                    val pct = calculateBatteryPercentage(v)
                                    batteryPercentage = "Batería: $pct% (Estimado LiFePO4 )"
                                }
                            }
                            "bt_battery_capacity" -> {
                                param.value.toFloatOrNull()?.let { v ->
                                    batteryPercentageInv = "${param.value} ${param.unit}"
                                }
                            }
                        }
                    }

                    response.dat?.pars?.status?.forEach { param ->
                        when (param.id) {
                            "status" -> {
                                StatusRow("Status : ", param.value)
                            }
                            "sy_status" -> {
                                StatusRow("Mode  : ", param.value)
                            }
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    StatusRow("Input Voltage:", inputVoltage)
                    StatusRow("Output Voltage:", outputVoltage)
                    StatusRow("Battery voltage:", batteryVoltage)
                    StatusRow("Battery % Inv:", batteryPercentageInv)

                    if (batteryPercentage.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            batteryPercentage,
                            fontSize = 16.sp,
                            color = Color(0xFF5C627A)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onRefreshClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C627A)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.width(140.dp)
            ) {
                Text("Refresh", color = Color.White)
            }
        }
    }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            "$label ",
            fontSize = 18.sp,
            color = Color(0xFF5C627A)
        )
        Text(
            value,
            fontSize = 18.sp,
            color = Color(0xFF5C627A)
        )
    }
}

@Composable
fun SettingsCard(
    pollingInterval: String,
    onPollingIntervalChange: (String) -> Unit,
    powerOnMessage: String,
    onPowerOnMessageChange: (String) -> Unit,
    powerOffMessage: String,
    onPowerOffMessageChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            TextField(
                value = pollingInterval,
                onValueChange = onPollingIntervalChange,
                label = { Text("Polling interval (s)") },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = powerOnMessage,
                onValueChange = onPowerOnMessageChange,
                label = { Text("Power On Message") },
                leadingIcon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = powerOffMessage,
                onValueChange = onPowerOffMessageChange,
                label = { Text("Power Off Message") },
                leadingIcon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewInverterStatus() {
    InverterNotifTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            InverterStatusCard(
                realtimeDataResult = null,
                isLoading = false,
                onRefreshClick = {}
            )
        }
    }
}
