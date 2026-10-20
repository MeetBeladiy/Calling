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
fun HelpScreen(onBack: () -> Unit) {
    ScreenScaffold(title = "Help & FAQ", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            HelpItem(
                question = "What is Calling App?",
                answer = "Calling App gives delivery drivers their own internet calling system. " +
                    "Call a friend over the internet, and when you need to phone a customer " +
                    "on the normal network, Calling App manages both calls and restores your " +
                    "friend conversation automatically afterwards."
            )
            HelpItem(
                question = "Does it work with WhatsApp or Instagram calls?",
                answer = "No — and no app can. WhatsApp and Instagram control their own calls " +
                    "and do not allow other apps to keep them alive. Calling App provides its " +
                    "own calling system instead, which is designed to survive network changes " +
                    "and customer-call interruptions."
            )
            HelpItem(
                question = "How do I add a friend?",
                answer = "Open Friends, search for the person by name or email, and send an " +
                    "invitation. Once they accept, you can call each other. They need a " +
                    "Calling App account too."
            )
            HelpItem(
                question = "What happens when I call a customer mid-conversation?",
                answer = "Calling App saves your friend-call session, lets the cellular call " +
                    "take the audio (Android only allows one call's audio at a time), then " +
                    "automatically reconnects your friend call when you hang up."
            )
            HelpItem(
                question = "Does the call survive losing internet briefly?",
                answer = "Yes. If the network drops or switches between Wi-Fi and mobile data, " +
                    "Calling App keeps the session and reconnects automatically, showing " +
                    "\"Reconnecting…\" until the audio is back."
            )
            HelpItem(
                question = "Is Calling App free?",
                answer = "Yes. The core calling features are free. Internet calls use your " +
                    "existing data connection."
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Still stuck? Email support@drivercall.app",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HelpItem(question: String, answer: String) {
    Text(
        text = question,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
        text = answer,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 20.dp)
    )
}
