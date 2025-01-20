package test.android.bles.module.router

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import test.android.bles.entity.BTDevice
import test.android.bles.module.scanner.ScannerScreen

@Composable
internal fun RouterScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        val deviceState = remember { mutableStateOf<BTDevice?>(null) }
        val device = deviceState.value
        if (device == null) {
            ScannerScreen(
                onSelect = {
                    deviceState.value = it
                }
            )
        } else {
            // todo
        }
    }
}
