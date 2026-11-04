package com.example.ftpget.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import com.example.ftpget.PositionErrorCalculator
import com.example.ftpget.R
import com.example.ftpget.DmsFields
import com.example.ftpget.DmsReferenceError
import com.example.ftpget.ReferenceDmsInput
import com.example.ftpget.ReferencePosition
import java.util.Locale

@Composable
internal fun ReferencePositionSection(state: MainUiState) {
    val reference = state.referencePosition
    var editing by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.md)) {
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.reference_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { editing = true }) {
                Text(stringResource(if (reference == null) R.string.reference_set else R.string.reference_edit))
            }
        }
        if (reference == null) {
            DetailText(stringResource(R.string.reference_empty))
        } else {
            val lat = ReferenceDmsInput.fromDecimal(reference.latitude)
            val lon = ReferenceDmsInput.fromDecimal(reference.longitude)
            Text(stringResource(R.string.reference_dms_summary,
                stringResource(if (reference.latitude < 0) R.string.reference_south else R.string.reference_north),
                lat.degrees, lat.minutes, lat.seconds,
                stringResource(if (reference.longitude < 0) R.string.reference_west else R.string.reference_east),
                lon.degrees, lon.minutes, lon.seconds),
                style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.reference_geodetic_height_summary, reference.heightMeters),
                style = MaterialTheme.typography.bodyMedium)
        }
        DetailText(stringResource(R.string.reference_evaluation_only))
    }
    if (editing) ReferenceEditor(reference, onDismiss = { editing = false }, onSave = {
        state.onReferenceChanged(it)
        editing = false
    })
}

@Composable
private fun ReferenceEditor(reference: ReferencePosition?, onDismiss: () -> Unit,
                            onSave: (ReferencePosition?) -> Unit) {
    val key = reference?.encode()
    val originalLat = reference?.let { ReferenceDmsInput.fromDecimal(it.latitude) }
    val originalLon = reference?.let { ReferenceDmsInput.fromDecimal(it.longitude) }
    var latDegrees by rememberSaveable(key) { mutableStateOf(originalLat?.degrees ?: "") }
    var latMinutes by rememberSaveable(key) { mutableStateOf(originalLat?.minutes ?: "") }
    var latSeconds by rememberSaveable(key) { mutableStateOf(originalLat?.seconds ?: "") }
    var lonDegrees by rememberSaveable(key) { mutableStateOf(originalLon?.degrees ?: "") }
    var lonMinutes by rememberSaveable(key) { mutableStateOf(originalLon?.minutes ?: "") }
    var lonSeconds by rememberSaveable(key) { mutableStateOf(originalLon?.seconds ?: "") }
    var south by rememberSaveable(key) { mutableStateOf((reference?.latitude ?: 0.0) < 0) }
    var west by rememberSaveable(key) { mutableStateOf((reference?.longitude ?: 0.0) < 0) }
    var height by rememberSaveable(key) { mutableStateOf(reference?.heightMeters?.toString() ?: "") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val parsed = ReferenceDmsInput.parse(DmsFields(latDegrees, latMinutes, latSeconds), south,
        DmsFields(lonDegrees, lonMinutes, lonSeconds), west, height)
    val error = if (submitted) parsed.error else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reference_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(GnssSpacing.sm)) {
                DetailText(stringResource(R.string.reference_input_note))
                CoordinateDmsInput(true, DmsFields(latDegrees, latMinutes, latSeconds),
                    { latDegrees = it }, { latMinutes = it }, { latSeconds = it }, south, { south = it }, error)
                CoordinateDmsInput(false, DmsFields(lonDegrees, lonMinutes, lonSeconds),
                    { lonDegrees = it }, { lonMinutes = it }, { lonSeconds = it }, west, { west = it }, error)
                ReferenceInput(height, { height = it }, R.string.reference_height,
                    error == DmsReferenceError.HEIGHT, R.string.reference_height_error)
            }
        },
        confirmButton = {
            Button(onClick = {
                submitted = true
                parsed.reference?.let(onSave)
            }) { Text(stringResource(R.string.reference_save)) }
        },
        dismissButton = {
            Row {
                if (reference != null) TextButton(onClick = { onSave(null) }) {
                    Text(stringResource(R.string.reference_clear))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.reference_cancel)) }
            }
        },
    )
}

@Composable
private fun CoordinateDmsInput(latitude: Boolean, value: DmsFields,
                               degreeChanged: (String) -> Unit, minuteChanged: (String) -> Unit,
                               secondChanged: (String) -> Unit, negative: Boolean,
                               negativeChanged: (Boolean) -> Unit, error: DmsReferenceError?) {
    val start = if (latitude) 0 else 3
    val component = error?.ordinal?.minus(start)?.takeIf { it in 0..2 }
    val message = when (component) {
        0 -> if (latitude) R.string.reference_latitude_error else R.string.reference_longitude_error
        1 -> R.string.reference_minutes_error
        else -> R.string.reference_seconds_error
    }
    Column(verticalArrangement = Arrangement.spacedBy(GnssSpacing.xs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GnssSpacing.xs)) {
            Text(stringResource(if (latitude) R.string.label_latitude else R.string.label_longitude),
                style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            FilterChip(selected = !negative, onClick = { negativeChanged(false) }, label = {
                Text(stringResource(if (latitude) R.string.reference_north else R.string.reference_east))
            })
            FilterChip(selected = negative, onClick = { negativeChanged(true) }, label = {
                Text(stringResource(if (latitude) R.string.reference_south else R.string.reference_west))
            })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GnssSpacing.sm)) {
            ReferenceInput(value.degrees, degreeChanged, R.string.reference_degrees, component == 0,
                message, Modifier.weight(1f), KeyboardType.Number, false)
            ReferenceInput(value.minutes, minuteChanged, R.string.reference_minutes, component == 1,
                message, Modifier.weight(1f), KeyboardType.Number, false)
            ReferenceInput(value.seconds, secondChanged, R.string.reference_seconds, component == 2,
                message, Modifier.weight(1.5f), KeyboardType.Decimal, false)
        }
        if (component != null) Text(stringResource(message), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ReferenceInput(value: String, onChange: (String) -> Unit, label: Int,
                           error: Boolean, errorMessage: Int, modifier: Modifier = Modifier.fillMaxWidth(),
                           keyboardType: KeyboardType = KeyboardType.Ascii, showSupportingError: Boolean = true) {
    OutlinedTextField(value, onChange, modifier = modifier,
        label = { Text(stringResource(label)) }, singleLine = true, isError = error,
        // ASCII keyboard keeps '-' available for southern/western coordinates
        // and signed height anomalies; Decimal keyboards often omit the sign.
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = if (error && showSupportingError) ({ Text(stringResource(errorMessage)) }) else null)
}

@Composable
internal fun ReferenceErrorSection(state: MainUiState) {
    val reference = state.referencePosition
    val solution = state.comparisonSolution
    val error = remember(reference, solution) { PositionErrorCalculator.compare(reference, solution) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GnssSpacing.md)) {
        Text(stringResource(R.string.reference_error_title), style = MaterialTheme.typography.titleMedium)
        when {
            reference == null -> DetailText(stringResource(R.string.reference_error_no_reference))
            solution == null -> DetailText(stringResource(R.string.reference_error_wait))
            error == null -> DetailText(stringResource(R.string.reference_error_invalid))
            else -> {
                DetailText(stringResource(if (state.comparisonIsPostprocessed)
                    R.string.reference_epoch_post else R.string.reference_epoch_live,
                    solution.week, solution.tow, solution.q))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GnssSpacing.md)) {
                    ErrorMetric(R.string.reference_horizontal_error, error.horizontalMeters, Modifier.weight(1f), true)
                    ErrorMetric(R.string.reference_spatial_error, error.spatialMeters, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GnssSpacing.sm)) {
                    ErrorMetric(R.string.reference_east_error, error.eastMeters, Modifier.weight(1f))
                    ErrorMetric(R.string.reference_north_error, error.northMeters, Modifier.weight(1f))
                    ErrorMetric(R.string.reference_up_error, error.upMeters, Modifier.weight(1f))
                }
                if (error.upMeters == null) DetailText(stringResource(R.string.reference_horizontal_only))
                if (solution.q == 5) DetailText(stringResource(R.string.reference_spp_status))
            }
        }
    }
}

@Composable
private fun ErrorMetric(label: Int, value: Double?, modifier: Modifier, prominent: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GnssSpacing.xs)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.let { String.format(Locale.US, "%.3f m", it) } ?: "—",
            style = if (prominent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace)
    }
}
