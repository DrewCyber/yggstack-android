package io.github.yggstack.android.ui.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yggstack.android.R

@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.tab_config),
        stringResource(R.string.tab_peers),
        stringResource(R.string.tab_logs)
    )

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> ConfigViewer()
            1 -> PeerStatus()
            2 -> LogsViewer()
        }
    }
}

@Composable
fun ConfigViewer() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Configuration Viewer\n(To be implemented in Phase 2)",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun PeerStatus() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Peer Status\n(To be implemented in Phase 2)",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
fun LogsViewer() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Logs Viewer\n(To be implemented in Phase 2)",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

