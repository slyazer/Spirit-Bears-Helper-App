package com.macromonitor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.macromonitor.app.ui.MacroMonitorRoot
import com.macromonitor.app.ui.theme.MacroMonitorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MacroMonitorTheme {
                MacroMonitorRoot()
            }
        }
    }
}