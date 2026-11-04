package com.example.ftpget.ui

import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ftpget.R
import kotlinx.coroutines.delay

@Composable
fun MapPage(state: MainUiState, map: WebView? = null, modifier: Modifier = Modifier) {
    var slowLoad by remember { mutableStateOf(false) }
    LaunchedEffect(state.mapLoading) {
        slowLoad = false
        if (state.mapLoading) {
            delay(12_000)
            slowLoad = true
        }
    }
    Box(modifier.fillMaxSize()) {
        when {
            state.mapLoadError -> MapMessage(
                title = stringResource(R.string.map_load_error),
                detail = stringResource(R.string.map_load_hint),
                modifier = Modifier.align(Alignment.Center),
            )
            map != null -> AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
            else -> MapMessage(
                title = stringResource(R.string.section_map),
                detail = stringResource(R.string.preview_map),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (state.mapLoading) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxSize().padding(GnssSpacing.xl),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!slowLoad) CircularProgressIndicator()
                    Text(
                        if (slowLoad) stringResource(R.string.map_load_error)
                        else stringResource(R.string.map_loading),
                        modifier = Modifier.padding(top = GnssSpacing.md),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (slowLoad) Text(
                        stringResource(R.string.map_load_hint),
                        modifier = Modifier.padding(top = GnssSpacing.sm),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = GnssSpacing.screen, vertical = GnssSpacing.sm),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(horizontal = GnssSpacing.md, vertical = GnssSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(GnssSpacing.xs)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.header_device_position),
                        style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(GnssSpacing.xs)) {
                        Box(Modifier.size(6.dp).background(
                            if (state.positionFix != null) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.outline, CircleShape))
                        Text(if (state.positionFix != null) stringResource(R.string.map_fix_ready)
                             else stringResource(R.string.map_waiting_fix),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val position = state.positionFix
                Text(if (position != null) stringResource(R.string.map_coordinates,
                        position.latitude, position.longitude, position.accuracyMeters)
                     else state.tvMapStatus.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(GnssSpacing.screen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GnssSpacing.sm),
        ) {
            FilledTonalButton(onClick = { state.btnOpenMap.click() },
                enabled = state.btnOpenMap.isEnabled,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.small) {
                Text(state.btnOpenMap.text)
            }
            SmallFloatingActionButton(
                onClick = { state.btnRefreshMap.click() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(painterResource(R.drawable.ic_gps_fixed_24),
                    contentDescription = state.btnRefreshMap.text)
            }
        }
    }
}

@Composable
private fun MapMessage(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(GnssSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(GnssSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface)
        Text(detail, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(showBackground = true, name = "MAP · Light")
@Composable private fun MapLightPreview() {
    GnssTheme { MapPage(remember { MainUiState().apply {
        positionFix = PositionFix(39.9042, 116.4074, 43.0, 2.4f)
    } }) }
}

@Preview(showBackground = true, name = "MAP · Dark")
@Composable private fun MapDarkPreview() {
    GnssTheme(darkTheme = true) { MapPage(remember { MainUiState().apply { mapLoadError = true } }) }
}
