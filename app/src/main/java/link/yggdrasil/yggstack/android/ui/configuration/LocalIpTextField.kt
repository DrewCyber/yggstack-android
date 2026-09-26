package link.yggdrasil.yggstack.android.ui.configuration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.PopupProperties
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Local IPv4 addresses to offer as suggestions: "127.0.0.1" first, then every
 * non-loopback IPv4 address on the device, deduplicated, "0.0.0.0" last.
 */
internal fun localIpSuggestions(): List<String> {
    val ipv4 = LinkedHashSet<String>()
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return listOf("127.0.0.1", "0.0.0.0")
        for (networkInterface in interfaces) {
            for (interfaceAddress in networkInterface.interfaceAddresses) {
                val address = interfaceAddress.address ?: continue
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    address.hostAddress?.let { ipv4.add(it) }
                }
            }
        }
    }
    return listOf("127.0.0.1") + ipv4 + "0.0.0.0"
}

/**
 * [OutlinedTextField] with a local-IP suggestion dropdown that opens only on the
 * first focus of each focus session. Typing, moving the cursor, or dismissing the
 * dropdown closes it; it does not reopen until focus leaves the field and returns.
 *
 * [onPick] transforms a picked suggestion into the new field value (e.g. to keep
 * the port of an ip:port field); default is the bare address.
 */
@Composable
fun LocalIpTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    placeholder: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
    onPick: (suggestedIp: String, currentText: String) -> String = { ip, _ -> ip }
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    var isFocused by remember { mutableStateOf(false) }
    var suggestionsExpanded by remember { mutableStateOf(false) }
    var shownThisFocus by remember { mutableStateOf(false) }
    // The tap that focuses the field also places the cursor, which arrives as a
    // selection-only change after focus — swallow exactly one so the just-opened
    // dropdown does not close immediately.
    var swallowSelectionEvent by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(value, isFocused) {
        if (!isFocused && value != textFieldValue.text) {
            textFieldValue = TextFieldValue(value)
        }
    }

    Box(modifier = modifier) {
        OutlinedTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                if (newValue.text != textFieldValue.text) {
                    suggestionsExpanded = false
                } else if (newValue.selection != textFieldValue.selection) {
                    if (swallowSelectionEvent) {
                        swallowSelectionEvent = false
                    } else {
                        suggestionsExpanded = false
                    }
                }
                textFieldValue = newValue
                onValueChange(newValue.text)
            },
            label = label,
            placeholder = placeholder,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    isFocused = focusState.isFocused
                    if (focusState.isFocused) {
                        if (!shownThisFocus) {
                            suggestions = localIpSuggestions()
                            suggestionsExpanded = true
                            shownThisFocus = true
                            swallowSelectionEvent = true
                        }
                    } else {
                        shownThisFocus = false
                        suggestionsExpanded = false
                        swallowSelectionEvent = false
                    }
                },
            enabled = enabled,
            isError = isError,
            supportingText = supportingText
        )
        // focusable = false keeps focus (and the keyboard) on the text field —
        // a focusable popup would blur it and re-arm the first-tap logic.
        DropdownMenu(
            expanded = suggestionsExpanded,
            onDismissRequest = { suggestionsExpanded = false },
            properties = PopupProperties(focusable = false)
        ) {
            suggestions.forEach { ip ->
                DropdownMenuItem(
                    text = { Text(ip) },
                    onClick = {
                        val picked = onPick(ip, textFieldValue.text)
                        textFieldValue = TextFieldValue(picked)
                        onValueChange(picked)
                        suggestionsExpanded = false
                    }
                )
            }
        }
    }
}
