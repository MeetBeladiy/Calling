package com.drivercall.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.drivercall.data.preferences.UserPreferences
import com.drivercall.presentation.navigation.DriverCallNavGraph
import com.drivercall.presentation.permissions.CallPermissionGate
import com.drivercall.presentation.theme.DriverCallTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val darkTheme by userPreferences.darkTheme.collectAsState(initial = false)
            DriverCallTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CallPermissionGate {
                        DriverCallNavGraph()
                    }
                }
            }
        }
    }
}
