package io.github.yggstack.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yggstack.android.BuildConfig
import io.github.yggstack.android.R
import io.github.yggstack.android.data.ConfigRepository
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { ConfigRepository(context) }
    val selectedTheme by repository.themeFlow.collectAsState(initial = "system")
    val coroutineScope = rememberCoroutineScope()

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

                Column {
                    ThemeOption(
                        label = stringResource(R.string.theme_light),
                        selected = selectedTheme == "light",
                        onClick = {
                            coroutineScope.launch {
                                repository.saveTheme("light")
                            }
                        }
                    )
                    ThemeOption(
                        label = stringResource(R.string.theme_dark),
                        selected = selectedTheme == "dark",
                        onClick = {
                            coroutineScope.launch {
                                repository.saveTheme("dark")
                            }
                        }
                    )
                    ThemeOption(
                        label = stringResource(R.string.theme_system),
                        selected = selectedTheme == "system",
                        onClick = {
                            coroutineScope.launch {
                                repository.saveTheme("system")
                            }
                        }
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
                    value = BuildConfig.VERSION_NAME
                )

                SettingItem(
                    label = "Commit",
                    value = BuildConfig.COMMIT_HASH
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

