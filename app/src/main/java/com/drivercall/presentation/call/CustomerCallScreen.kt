package com.drivercall.presentation.call

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.drivercall.domain.call.CallStage
import com.drivercall.presentation.components.ScreenScaffold

@Composable
fun CustomerCallScreen(onBack: () -> Unit, viewModel: CallViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var phoneNumber by remember { mutableStateOf("") }
    var customerName by remember { mutableStateOf("") }
    var deliveryReference by remember { mutableStateOf("") }
    var showDirectCallPermissionDialog by remember { mutableStateOf(false) }

    fun launchCall(direct: Boolean) {
        val clean = phoneNumber.trim()
        if (clean.isBlank()) return
        viewModel.prepareCustomerCall(customerName)
        val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
        context.startActivity(Intent(action, Uri.parse("tel:${Uri.encode(clean)}")))
    }

    val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        launchCall(direct = granted)
    }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) phoneNumber = cursor.getString(0).orEmpty()
        }
    }
    fun openContacts() {
        contactPicker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
    }
    ScreenScaffold(title = "Call customer", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                if (state.canTalk) "Your friend call will be paused and securely preserved while the cellular call is active."
                else "Enter a customer number to use your normal phone service.",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = customerName,
                onValueChange = { customerName = it },
                label = { Text("Customer name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                label = { Text("Customer phone number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = deliveryReference,
                onValueChange = { deliveryReference = it },
                label = { Text("Delivery reference (optional)") },
                supportingText = { Text("Used on this screen only; not saved in call history") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = ::openContacts,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Filled.ContactPhone, contentDescription = null)
                Text("  Choose from contacts")
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CALL_PHONE
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) launchCall(true)
                    else showDirectCallPermissionDialog = true
                },
                enabled = phoneNumber.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(64.dp)
            ) {
                Icon(Icons.Filled.Phone, contentDescription = null)
                Text("  Call customer")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "You can deny direct-call access and continue in your phone's dialler instead.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.stage in setOf(CallStage.CELLULAR_CALL_STARTED, CallStage.CELLULAR_CALL_ACTIVE, CallStage.CELLULAR_CALL_ENDED)) {
                Spacer(Modifier.height(28.dp))
                Button(onClick = viewModel::restoreFriendCall, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                    Text("Return to friend call")
                }
            }
        }
    }

    if (showDirectCallPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showDirectCallPermissionDialog = false },
            title = { Text("Call directly") },
            text = {
                Text(
                    "Phone access lets Calling App start the customer call immediately. You can use the dialler without granting it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDirectCallPermissionDialog = false
                    callPermission.launch(Manifest.permission.CALL_PHONE)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDirectCallPermissionDialog = false
                    launchCall(false)
                }) { Text("Use dialler") }
            }
        )
    }
}
