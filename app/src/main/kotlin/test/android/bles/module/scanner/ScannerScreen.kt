package test.android.bles.module.scanner

import android.bluetooth.le.ScanSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import test.android.bles.entity.BTDevice
import test.android.bles.module.bt.BLEScannerService
import test.android.bles.util.showToast

@Composable
internal fun ScannerScreen(
    onSelect: (BTDevice) -> Unit,
) {
    val context = LocalContext.current
    val insets = WindowInsets.systemBars.asPaddingValues()
    val devicesState = remember { mutableStateOf(listOf<BTDevice>()) }
    val scanState = BLEScannerService.states.collectAsState().value
    val scanSettings = remember {
        ScanSettings
            .Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .setReportDelay(0L)
            .build()
    }
    LaunchedEffect(Unit) {
        BLEScannerService.events.collect { event ->
            when (event) {
                is BLEScannerService.Event.OnBTDevice -> {
                    if (devicesState.value.none { it.address == event.device.address }) {
                        devicesState.value += event.device
                    }
                }
                is BLEScannerService.Event.OnError -> {
                    context.showToast("ble scanner error: ${event.error}")
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(devicesState.value.size) { index ->
                    val device = devicesState.value[index]
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clickable {
                                when (BLEScannerService.states.value) {
                                    BLEScannerService.State.Started -> {
                                        BLEScannerService.stop(context)
                                    }
                                    else -> {
                                        // noop
                                    }
                                }
                                onSelect(device)
                            }
                            .padding(horizontal = 16.dp),
                    ) {
                        BasicText(
                            modifier = Modifier
                                .align(Alignment.CenterStart),
                            text = device.name,
                            style = TextStyle(
                                color = Color.Black,
                                fontSize = 14.sp,
                            ),
                        )
                        BasicText(
                            modifier = Modifier
                                .align(Alignment.CenterEnd),
                            text = device.address,
                            style = TextStyle(
                                color = Color.Black,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            }
            val text = when (scanState) {
                null -> "..."
                BLEScannerService.State.Started -> "stop"
                BLEScannerService.State.Stopped -> "start"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                BasicText(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .clickable(enabled = scanState != null) {
                            when (BLEScannerService.states.value) {
                                BLEScannerService.State.Started -> {
                                    BLEScannerService.stop(context)
                                }
                                BLEScannerService.State.Stopped -> {
                                    BLEScannerService.start(context, scanSettings = scanSettings)
                                }
                                else -> {
                                    // noop
                                }
                            }
                        }
                        .wrapContentSize(),
                    text = text,
                    style = TextStyle(
                        color = Color.Black,
                        fontSize = 16.sp,
                    ),
                )
                if (devicesState.value.isNotEmpty()) {
                    BasicText(
                        modifier = Modifier
                            .fillMaxHeight()
                            .weight(1f)
                            .clickable {
                                devicesState.value = emptyList()
                            }
                            .wrapContentSize(),
                        text = "clear",
                        style = TextStyle(
                            color = Color.Black,
                            fontSize = 16.sp,
                        ),
                    )
                }
            }
        }
    }
}
