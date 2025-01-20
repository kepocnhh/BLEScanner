package test.android.bles.module.bt

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
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
import test.android.bles.entity.BTDevice

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
            logger.debug("on scan result: $result")
            val device = BTDevice(
                address = result.device.address ?: return,
                name = result.device.name ?: return,
            )
            coroutineScope.launch {
                _events.emit(
                    Event.OnBTDevice(device = device),
                )
            }
        }
    }

    private fun onScanStart(scanSettings: ScanSettings) {
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
                    scanner.startScan(null, scanSettings, scanCallback)
                }
            }.fold(
                onSuccess = {
                    val filter = IntentFilter().also {
                        it.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                        it.addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                    }
                    registerReceiver(receivers, filter)
                    _states.value = State.Started
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

    companion object {
        private val _states = MutableStateFlow<State?>(State.Stopped)
        val states = _states.asStateFlow()
        private val _events = MutableSharedFlow<Event>()
        val events = _events.asSharedFlow()

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
    }
}
