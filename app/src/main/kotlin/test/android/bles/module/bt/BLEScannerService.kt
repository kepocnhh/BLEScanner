package test.android.bles.module.bt

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import test.android.bles.App
import test.android.bles.BuildConfig
import test.android.bles.entity.BTDevice
import java.util.Date
import kotlin.math.absoluteValue

internal class BLEScannerService : Service() {
    sealed interface Event {
        class OnError(val error: Throwable) : Event
        class OnBTDevice(val device: BTDevice) : Event
    }

    enum class State {
        Started,
        Stopped,
    }

    enum class Action {
        Start,
        Stop,
    }

    private val job = SupervisorJob()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + job)
    private val logger = App.loggers.create("[BLE|Scanner|Service]")
    private val receivers = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    logger.debug("Bluetooth adapter state: $state")
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> {
                            onScanStop()
                        }
                        else -> {
                            // noop
                        }
                    }
                }
                LocationManager.PROVIDERS_CHANGED_ACTION -> {
                    val locationManager = getSystemService(LocationManager::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val name = intent.getStringExtra(LocationManager.EXTRA_PROVIDER_NAME)
                        if (name != LocationManager.GPS_PROVIDER) return
                    }
                    val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    logger.debug("isLocationEnabled: $isLocationEnabled")
                    if (!isLocationEnabled) {
                        if (states.value == State.Started) {
                            onScanStop()
                        }
                    }
                }
                else -> {
                    // noop
                }
            }
        }
    }
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result == null) return
            val device = BTDevice(
                address = result.device.address ?: return,
                name = result.device.name ?: return,
            )
            val message = """
                device: $device
                tx: ${result.txPower}
                rssi: ${result.rssi}
            """.trimIndent()
            logger.debug(message)
            coroutineScope.launch {
                _events.emit(
                    Event.OnBTDevice(device = device),
                )
            }
        }
    }
    private val N_ID: Int = System.currentTimeMillis().plus(hashCode()).toInt().absoluteValue

    private fun onScanStart(scanSettings: ScanSettings) {
        val context: Context = this
        coroutineScope.launch {
            _states.value = null
            runCatching {
                withContext(Dispatchers.Default) {
                    val adapter = getSystemService(BluetoothManager::class.java).adapter
                    check(adapter.isEnabled) { "BT adapter is disabled!" }
                    val lm = getSystemService(LocationManager::class.java)
                    check(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "GPS provider is disabled!" }
                    val permissions = arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                    for (permission in permissions) {
                        check(checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                            "Permission \"$permission\" is not granted!"
                        }
                    }
                    val scanner = adapter.bluetoothLeScanner ?: error("No scanner!")
                    // https://stackoverflow.com/a/48079800
                    val filters = listOf(ScanFilter.Builder().build())
                    scanner.startScan(filters, scanSettings, scanCallback)
                }
            }.fold(
                onSuccess = {
                    val filter = IntentFilter().also {
                        it.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                        it.addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                    }
                    registerReceiver(receivers, filter)
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    val notification = buildNotification(context, "started")
                    nm.notify(N_ID, notification)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(N_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                    } else {
                        TODO("BLEScannerService:onScanStart($scanSettings):onSuccess")
                    }
                    _states.value = State.Started
                    logger.debug("started: ${Date()}")
                },
                onFailure = { error ->
                    logger.warning("on scan start error: $error")
                    _events.emit(Event.OnError(error))
                    _states.value = State.Stopped
                },
            )
        }
    }

    private fun onScanStop() {
        coroutineScope.launch {
            _states.value = null
            runCatching {
                withContext(Dispatchers.Default) {
                    val scanner = getSystemService(BluetoothManager::class.java)
                        .adapter
                        .bluetoothLeScanner
                        ?: error("No scanner!")
                    scanner.stopScan(scanCallback)
                }
            }.fold(
                onSuccess = {
                    _states.value = State.Stopped
                },
                onFailure = { error ->
                    logger.warning("on scan stop error: $error")
                    _events.emit(Event.OnError(error))
                    _states.value = State.Stopped
                },
            )
            unregisterReceiver(receivers)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            stopForeground(STOP_FOREGROUND_REMOVE)
            nm.cancel(N_ID)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) TODO()
        val action = Action.entries.firstOrNull { it.name == intent.action } ?: error("No action!")
        when (action) {
            Action.Start -> {
                val scanSettings = intent.getParcelableExtra<ScanSettings>("scanSettings") ?: TODO()
                onScanStart(scanSettings = scanSettings)
            }
            Action.Stop -> onScanStop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NC_ID) == null) {
            val channel = NotificationChannel(NC_ID, "${BuildConfig.APPLICATION_ID}:notifications", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }
}

    override fun onDestroy() {
        super.onDestroy()
        logger.debug("on destroy...")
    }

    companion object {
        private val _states = MutableStateFlow<State?>(State.Stopped)
        val states = _states.asStateFlow()
        private val _events = MutableSharedFlow<Event>()
        val events = _events.asSharedFlow()
        private val NC_ID = "28c4441c-5e1e-4e14-ab74-cd01fc2d4962"

        fun start(context: Context, scanSettings: ScanSettings) {
            val intent = Intent(context, BLEScannerService::class.java)
            intent.action = Action.Start.name
            intent.putExtra("scanSettings", scanSettings)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BLEScannerService::class.java)
            intent.action = Action.Stop.name
            context.startService(intent)
        }

        private fun buildNotification(
            context: Context,
            text: CharSequence,
        ): Notification {
            val intent = Intent(context, BLEScannerService::class.java)
            intent.action = Action.Stop.name
            val stopIntent = PendingIntent.getService(context, 1, intent, PendingIntent.FLAG_IMMUTABLE)
            val action = NotificationCompat.Action.Builder(-1, "stop", stopIntent)
                .build()
            return NotificationCompat.Builder(context, NC_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentText(text)
                .setAutoCancel(false)
                .setOngoing(true)
//                .setDeleteIntent(deleteIntent)
                .addAction(action)
                .build()
        }
    }
}
