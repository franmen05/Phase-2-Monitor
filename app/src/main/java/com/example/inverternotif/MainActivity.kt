package com.example.inverternotif

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.inverternotif.ui.theme.InverterNotifTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val CHANNEL_ID = "inverter_channel"
    private lateinit var sharedPreferences: SharedPreferences

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startInverterService()
        } else {
            // Handle the case where the user denies the permission
        }
    }

    private var statusUpdateReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        createNotificationChannel()

        sharedPreferences = getSharedPreferences("InverterNotifPrefs", Context.MODE_PRIVATE)

        askNotificationPermission()

        setContent {
            InverterNotifTheme {
                val savedPollingInterval = sharedPreferences.getString("pollingInterval", "60") ?: "60"
                val savedPowerOnMessage = sharedPreferences.getString("powerOnMessage", "LLego la luz") ?: "LLego la luz"
                val savedPowerOffMessage = sharedPreferences.getString("powerOffMessage", "Adios luz") ?: "Adios luz"

                var inverterStatus by remember { mutableStateOf("Loading status...") }
                var pollingInterval by remember { mutableStateOf(savedPollingInterval) }
                var powerOnMessage by remember { mutableStateOf(savedPowerOnMessage) }
                var powerOffMessage by remember { mutableStateOf(savedPowerOffMessage) }
                var isLoading by remember { mutableStateOf(true) }
                var isApplying by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            inverterStatus = intent?.getStringExtra(InverterStatusService.EXTRA_STATUS) ?: "Error receiving status"
                            isLoading = false
                            isApplying = false
                        }
                    }
                    statusUpdateReceiver = receiver
                    val filter = IntentFilter(InverterStatusService.ACTION_STATUS_UPDATE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        registerReceiver(receiver, filter, RECEIVER_EXPORTED)
                    } else {
                        registerReceiver(receiver, filter)
                    }

                    onDispose {
                        unregisterReceiver(receiver)
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize(),
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
                    Greeting(
                        status = inverterStatus,
                        modifier = Modifier.padding(innerPadding),
                        onApplyClick = {
                            scope.launch {
                                isApplying = true
                                isLoading = true
                                with(sharedPreferences.edit()) {
                                    putString("pollingInterval", pollingInterval)
                                    putString("powerOnMessage", powerOnMessage)
                                    putString("powerOffMessage", powerOffMessage)
                                    apply()
                                }
                                startInverterService()
                            }
                        },
                        onRefreshClick = {
                            isLoading = true
                            startInverterService()
                        },
                        pollingInterval = pollingInterval,
                        onPollingIntervalChange = { pollingInterval = it },
                        powerOnMessage = powerOnMessage,
                        onPowerOnMessageChange = { powerOnMessage = it },
                        powerOffMessage = powerOffMessage,
                        onPowerOffMessageChange = { powerOffMessage = it },
                        isLoading = isLoading,
                        isApplying = isApplying
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Inverter Status"
            val descriptionText = "Notifications for inverter status"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Greeting(
    status: String,
    modifier: Modifier = Modifier,
    onApplyClick: () -> Unit,
    onRefreshClick: () -> Unit,
    pollingInterval: String,
    onPollingIntervalChange: (String) -> Unit,
    powerOnMessage: String,
    onPowerOnMessageChange: (String) -> Unit,
    powerOffMessage: String,
    onPowerOffMessageChange: (String) -> Unit,
    isLoading: Boolean,
    isApplying: Boolean
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Inverter Status", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
                    Text("Loading status...")
                } else {
                    Text(
                        text = status,
                        modifier = Modifier.padding(bottom = 16.dp),
                        fontSize = 16.sp
                    )
                }
                Button(onClick = onRefreshClick, enabled = !isLoading) {
                    Text("Refresh")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onApplyClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isApplying
        ) {
            Text(if (isApplying) "Applying..." else "Apply")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    InverterNotifTheme {
        Greeting(
            status = "Input Voltage: 12.3 V",
            onApplyClick = {},
            onRefreshClick = {},
            pollingInterval = "60",
            onPollingIntervalChange = {},
            powerOnMessage = "LLego la luz",
            onPowerOnMessageChange = {},
            powerOffMessage = "Adios luz",
            onPowerOffMessageChange = {},
            isLoading = false,
            isApplying = false
        )
    }
}
