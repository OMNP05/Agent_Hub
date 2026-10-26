package dev.agenthub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.agenthub.app.core.ui.theme.AgentHubTheme
import dev.agenthub.app.core.ui.theme.ThemeMode

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AgentHubTheme(mode = ThemeMode.System) {
                AppRoot()
            }
        }
    }
}
