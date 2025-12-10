package io.github.yggstack.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yggstack.android.R

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Theme Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.theme_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                var selectedTheme by remember { mutableStateOf("system") }

                Column {
                    ThemeOption(
                        label = stringResource(R.string.theme_light),
                        selected = selectedTheme == "light",
                        onClick = { selectedTheme = "light" }
                    )
                    ThemeOption(
                        label = stringResource(R.string.theme_dark),
                        selected = selectedTheme == "dark",
                        onClick = { selectedTheme = "dark" }
                    )
                    ThemeOption(
                        label = stringResource(R.string.theme_system),
                        selected = selectedTheme == "system",
                        onClick = { selectedTheme = "system" }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // About Section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.about_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                SettingItem(
                    label = stringResource(R.string.version),
                    value = "1.0.0"
                )

                SettingItem(
                    label = stringResource(R.string.library_version),
                    value = "yggstack 0.1.0"
                )
            }
        }
    }
}

@Composable
fun ThemeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
fun SettingItem(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

