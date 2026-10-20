package com.drivercall.presentation.info

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drivercall.presentation.components.ScreenScaffold

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    ScreenScaffold(title = "Privacy Policy", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            PrivacySection(
                title = "What we collect",
                body = "Your name, email and optional phone number so friends can find you. " +
                    "Your friends list and call history (who you called, when, for how long). " +
                    "We never collect the content of your calls."
            )
            PrivacySection(
                title = "Call audio",
                body = "Voice audio is transmitted peer-to-peer using encrypted WebRTC and is " +
                    "never recorded or stored by Calling App. Call recordings are off by default " +
                    "and would only ever be added as an explicit, legally compliant feature."
            )
            PrivacySection(
                title = "How we protect it",
                body = "All traffic uses HTTPS/TLS. Your sign-in token is stored in Android's " +
                    "encrypted storage backed by the device Keystore. Passwords are hashed on " +
                    "the server and never stored in plain text."
            )
            PrivacySection(
                title = "What we never do",
                body = "We never access WhatsApp, Instagram or other apps' calls. We never sell " +
                    "your data. We never keep your customers' phone numbers longer than needed."
            )
            PrivacySection(
                title = "Your control",
                body = "You can block or remove friends at any time, clear your call history, " +
                    "and delete your account by contacting support@drivercall.app."
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Last updated: October 2026",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PrivacySection(title: String, body: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 20.dp)
    )
}
