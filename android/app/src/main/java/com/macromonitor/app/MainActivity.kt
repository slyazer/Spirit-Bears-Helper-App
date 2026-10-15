package com.macromonitor.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.macromonitor.app.ui.MacroMonitorRoot
import com.macromonitor.app.ui.theme.MacroMonitorTheme

class MainActivity : ComponentActivity() {

    /** Latest `macromonitor://pair` deep link, consumed by the Compose tree. */
    private val pairUri = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pairUri.value = intent?.data?.toString()
        setContent {
            MacroMonitorTheme {
                MacroMonitorRoot(
                    pairUri = pairUri.value,
                    onPairConsumed = { pairUri.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pairUri.value = intent.data?.toString()
    }
}
