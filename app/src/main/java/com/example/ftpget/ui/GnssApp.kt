package com.example.ftpget.ui

import android.webkit.WebView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.example.ftpget.GnssDataTableView
import com.example.ftpget.R
import com.example.ftpget.SignalBarView
import com.example.ftpget.SkyPlotView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Destination(@StringRes val title: Int, @DrawableRes val icon: Int)
private val destinations = listOf(
    Destination(R.string.nav_log, R.drawable.ic_list_alt_24),
    Destination(R.string.nav_view, R.drawable.ic_gps_fixed_24),
    Destination(R.string.nav_pos, R.drawable.ic_place_24),
    Destination(R.string.nav_map, R.drawable.ic_map_24),
)

@Composable
fun GnssApp(
    state: MainUiState,
    skyPlot: SkyPlotView,
    signalBars: SignalBarView,
    satellites: GnssDataTableView,
    measurements: GnssDataTableView,
    map: WebView,
    onPageSelected: (Int) -> Unit,
) {
    val pageState = rememberSaveableStateHolder()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader(state) },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding().height(72.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    destinations.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = state.selectedPage == index,
                            onClick = { if (state.selectedPage != index) onPageSelected(index) },
                            icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                            label = { Text(stringResource(destination.title),
                                style = MaterialTheme.typography.labelSmall) },
                            alwaysShowLabel = true,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        pageState.SaveableStateProvider(state.selectedPage) {
            when (state.selectedPage) {
                0 -> LogPage(state, measurements, Modifier.padding(padding))
                1 -> ViewPage(state, skyPlot, signalBars, satellites, Modifier.padding(padding))
                2 -> PosPage(state, Modifier.padding(padding))
                else -> MapPage(state, map, Modifier.padding(padding))
            }
        }
    }
    state.dialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = { if (dialog.cancelable) state.dialog = null },
            title = { Text(dialog.title) },
            text = { Text(dialog.message, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { state.dialog = null; dialog.onConfirm() }) { Text(dialog.confirmText) }
            },
            dismissButton = if (dialog.cancelable) {{
                TextButton(onClick = { state.dialog = null }) { Text(stringResource(R.string.action_cancel)) }
            }} else null,
        )
    }
}

@Composable
private fun AppHeader(state: MainUiState) {
    val today = remember { SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(Date()) }
    val pageDetail = when (state.selectedPage) {
        0 -> today
        1 -> stringResource(R.string.header_live_update)
        else -> stringResource(R.string.header_device_position)
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal = GnssSpacing.screen, vertical = GnssSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(GnssSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GnssSpacing.xs)) {
                Text(stringResource(R.string.brand_wordmark),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary)
                Text(stringResource(destinations[state.selectedPage].title),
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(GnssSpacing.xs)) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small) {
                    Row(Modifier.padding(horizontal = GnssSpacing.sm, vertical = GnssSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(GnssSpacing.sm)) {
                        Box(Modifier.size(6.dp).background(
                            if (state.satelliteCount > 0) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.outline, CircleShape))
                        Text(if (state.satelliteCount > 0)
                            stringResource(R.string.header_satellite_count, state.satelliteCount)
                            else stringResource(R.string.wait_signal),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(pageDetail, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
