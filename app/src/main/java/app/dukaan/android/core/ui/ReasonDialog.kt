package app.dukaan.android.core.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dukaan.android.R
import app.dukaan.android.core.ui.theme.LocalDukaanColors

/**
 * "Why?" before any void (D-17): [onConfirm] gets the reason and a callback that receives null
 * on success (dialog closes) or the error to show.
 */
@Composable
fun ReasonDialog(title: String, body: String, action: String, onDismiss: () -> Unit, onConfirm: (String, (String?) -> Unit) -> Unit) {
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                OutlinedTextField(reason, { reason = it }, label = { Text(stringResource(R.string.adjust_reason)) }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) { msg -> if (msg == null) onDismiss() else error = msg } }) {
                Text(action, color = LocalDukaanColors.current.loss)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
