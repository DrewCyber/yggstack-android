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
 * One entry of the suggestion dropdown: [value] is what gets inserted into
 * the field, [label] is extra display-only context — the network interface
 * name the address belongs to (e.g. "10.0.0.5 (wlan0)").
 */
data class IpSuggestion(val value: String, val label: String? = null)

/**
 * Local IPv4 addresses to offer as suggestions: "127.0.0.1" first, then every
 * non-loopback IPv4 address on the device (annotated with its interface
 * name), deduplicated, "0.0.0.0" (all interfaces) last.
 */
internal fun localIpSuggestions(): List<IpSuggestion> {
    val seen = LinkedHashSet<String>()
    val ifaceIps = mutableListOf<IpSuggestion>()
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        for (networkInterface in interfaces) {
            val name = networkInterface.name?.takeIf { it.isNotBlank() }
            for (interfaceAddress in networkInterface.interfaceAddresses) {
                val address = interfaceAddress.address ?: continue
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    address.hostAddress?.let { ip ->
                        if (seen.add(ip)) ifaceIps += IpSuggestion(ip, name)
                    }
                }
            }
        }
    }
    return listOf(IpSuggestion("127.0.0.1", "lo")) + ifaceIps + IpSuggestion("0.0.0.0", "all")
}

/**
 * Well-known public DNS servers inside the Yggdrasil network, offered as
 * suggestions for the DNS server field (the default :53 port is appended on
 * save by ConfigRepository.normalizeDnsServer).
 */
internal fun dnsServerSuggestions(): List<IpSuggestion> = listOf(
    "308:62:45:62::",
    "308:25:40:bd::",
    "308:84:68:55::",
    "308:c8:48:45::"
).map { IpSuggestion(it) }

/**
 * [OutlinedTextField] with a suggestion dropdown that opens only on the
 * first focus of each focus session. Typing, moving the cursor, or dismissing
 * the dropdown closes it; it does not reopen until focus leaves the field and
 * returns. Suggested values come from [suggestionsProvider] — local IPv4
 * addresses (with interface-name labels) by default.
 *
 * A suggestion's [IpSuggestion.label] is display-only; only [IpSuggestion.value]
 * is ever inserted into the field, transformed by [onPick] (e.g. to keep the
 * port of an ip:port field); default is the bare address.
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
    suggestionsProvider: () -> List<IpSuggestion> = ::localIpSuggestions,
    trailingIcon: (@Composable () -> Unit)? = null,
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
    var suggestions by remember { mutableStateOf<List<IpSuggestion>>(emptyList()) }

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
                            suggestions = suggestionsProvider()
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
            supportingText = supportingText,
            trailingIcon = trailingIcon
        )
        // focusable = false keeps focus (and the keyboard) on the text field —
        // a focusable popup would blur it and re-arm the first-tap logic.
        DropdownMenu(
            expanded = suggestionsExpanded,
            onDismissRequest = { suggestionsExpanded = false },
            properties = PopupProperties(focusable = false)
        ) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = {
                        Text(suggestion.label?.let { "${suggestion.value} ($it)" } ?: suggestion.value)
                    },
                    onClick = {
                        val picked = onPick(suggestion.value, textFieldValue.text)
                        textFieldValue = TextFieldValue(picked)
                        onValueChange(picked)
                        suggestionsExpanded = false
                    }
                )
            }
        }
    }
}
