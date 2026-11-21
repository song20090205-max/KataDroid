package io.github.zhzy0077.katadroid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import android.content.res.Resources
import io.github.zhzy0077.katadroid.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.zhzy0077.katadroid.NpuProbe
import io.github.zhzy0077.katadroid.engine.*
import io.github.zhzy0077.katadroid.ui.theme.GoColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EngineScreen(
    config: EngineConfig,
    benchmark: BenchmarkUiState,
    onConfig: (EngineConfig) -> Unit,
    onCloudConfig: (ComputeMode, String, String?) -> Boolean,
    onClearCloudToken: () -> Unit,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
    onDiagnostics: () -> Unit,
    onBack: () -> Unit,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    var visits by rememberSaveable { mutableIntStateOf(500) }
    var computeMode by rememberSaveable(config.computeMode) { mutableStateOf(config.computeMode) }
    var cloudEndpoint by rememberSaveable(config.cloudEndpoint) { mutableStateOf(config.cloudEndpoint) }
    var cloudToken by remember { mutableStateOf("") }
    var cloudTokenConfigured by remember { mutableStateOf(CloudTokenStore.isConfigured(context)) }
    var catalogRevision by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    val models = remember(catalogRevision) { KataGoModel.entries }
    val scope = rememberCoroutineScope()
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            importStatus = null
            try {
                val imported = checkNotNull(context.contentResolver.openInputStream(uri)).use { ModelPackages.install(context, it) }
                catalogRevision++
                importStatus = resources.getString(R.string.model_import_complete, imported.sourceName)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { importStatus = resources.getString(R.string.model_import_failed) }
            catch (error: LinkageError) { importStatus = resources.getString(R.string.model_import_failed) }
            finally { importing = false }
        }
    }
    val selectedResult = benchmark.resultFor(config)
    val supportsNpu = NpuProbe.hasRuntimeLibraries(context)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).testTag("engine-screen")) {
        PageHeader(resources.getString(R.string.engines_models), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).testTag("engine-scroll"),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsCard {
                SectionTitle(resources.getString(R.string.katago_models))
                Column(Modifier.selectableGroup()) {
                    models.forEach { model ->
                        val result = benchmark.resultFor(config.copy(model = model))
                        Row(Modifier.fillMaxWidth().testTag("model-${model.id}")
                            .selectable(config.model == model, role = Role.RadioButton, onClick = {
                                if (config.model != model) onConfig(config.copy(model = model))
                            })
                            .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = config.model == model, onClick = null)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(model.title(resources), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = GoColors.Ink)
                                Hint(resources.getString(model.descriptionRes))
                                Hint(result?.let { resources.getString(R.string.previous_speed, decimal(it.visitsPerSecond), it.samples.size, it.visitsPerPosition, it.config.backend.name) }
                                    ?: resources.getString(R.string.model_untested))
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { importModel.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = !importing && !benchmark.running,
                    modifier = Modifier.fillMaxWidth().testTag("import-model")) {
                    Text(resources.getString(if (importing) R.string.model_import_checking else R.string.model_import))
                }
                importStatus?.let { Hint(it) }
                Hint(resources.getString(R.string.model_import_hint))
                HorizontalDivider(color = GoColors.Outline)
                Hint(resources.getString(R.string.model_strength_hint))
                Hint(resources.getString(R.string.model_weights, config.model.sourceName))
            }
            SettingsCard {
                SectionTitle(resources.getString(R.string.compute_mode))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ComputeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = computeMode == mode,
                            onClick = { computeMode = mode },
                            label = { Text(resources.getString(when (mode) {
                                ComputeMode.LOCAL -> R.string.compute_local
                                ComputeMode.CLOUD -> R.string.compute_cloud
                                ComputeMode.HYBRID -> R.string.compute_hybrid
                            })) },
                            modifier = Modifier.testTag("compute-mode-${mode.name.lowercase()}")
                        )
                    }
                }
                OutlinedTextField(
                    value = cloudEndpoint,
                    onValueChange = { cloudEndpoint = it },
                    singleLine = true,
                    label = { Text(resources.getString(R.string.cloud_endpoint)) },
                    placeholder = { Text("https://your-server.example") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("cloud-endpoint")
                )
                OutlinedTextField(
                    value = cloudToken,
                    onValueChange = { cloudToken = it },
                    singleLine = true,
                    label = { Text(resources.getString(R.string.cloud_token)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("cloud-token")
                )
                Hint(resources.getString(if (cloudTokenConfigured) R.string.cloud_token_saved else R.string.cloud_token_missing))
                Hint(resources.getString(R.string.cloud_model_note))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val saved = onCloudConfig(computeMode, cloudEndpoint.trim(), cloudToken.takeIf { it.isNotBlank() })
                            if (saved && cloudToken.isNotBlank()) {
                                cloudTokenConfigured = true
                                cloudToken = ""
                            }
                        },
                        enabled = (computeMode == ComputeMode.LOCAL ||
                            (cloudEndpoint.startsWith("https://", ignoreCase = true) &&
                                (cloudToken.length >= 16 || cloudTokenConfigured))) &&
                            (cloudToken.isBlank() || cloudToken.length >= 16),
                        modifier = Modifier.weight(1f).testTag("save-cloud-settings")
                    ) { Text(resources.getString(R.string.cloud_save)) }
                    if (cloudTokenConfigured) {
                        OutlinedButton(onClick = {
                            onClearCloudToken()
                            cloudTokenConfigured = false
                            cloudToken = ""
                        }, modifier = Modifier.testTag("clear-cloud-token")) {
                            Text(resources.getString(R.string.cloud_clear_token))
                        }
                    }
                }
                Hint(resources.getString(R.string.cloud_https_hint))
            }
            SettingsCard {
                SectionTitle(resources.getString(R.string.compute_device))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineBackend.entries.forEach { backend ->
                        FilterChip(selected = config.backend == backend,
                            enabled = backend != EngineBackend.NPU || supportsNpu,
                            onClick = { if (config.backend != backend) onConfig(config.copy(backend = backend)) },
                            label = { Text(resources.getString(backend.labelRes)) }, modifier = Modifier.testTag("backend-${backend.name}"))
                    }
                }
                Hint(resources.getString(R.string.backend_hint))
                Hint(resources.getString(R.string.backend_local_fallback_hint))
            }
            SettingsCard {
                SectionTitle(resources.getString(R.string.search_benchmark))
                Text(config.displayLabel(resources), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GoColors.Ink)
                Hint(resources.getString(R.string.benchmark_cases))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 500, 1000).forEach { count ->
                        FilterChip(selected = visits == count, enabled = !benchmark.running, onClick = { visits = count },
                            label = { Text(count.toString(), maxLines = 1) }, modifier = Modifier.testTag("benchmark-limit-$count"))
                    }
                }
                Hint(resources.getString(R.string.benchmark_visits_hint, visits, visits * SearchBenchmark.positions.size))
                if (benchmark.running) {
                    LinearProgressIndicator(progress = { benchmark.completedVisits.toFloat() / benchmark.totalVisits },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("benchmark-progress"))
                    Text(when (benchmark.phase) {
                        BenchmarkPhase.STARTING -> resources.getString(R.string.benchmark_loading, benchmark.config.model.id)
                        BenchmarkPhase.WARMING -> resources.getString(R.string.benchmark_warming, SearchBenchmark.WARMUP_VISITS)
                        else -> resources.getString(R.string.benchmark_progress, benchmarkCaseLabel(resources, benchmark.caseLabel), benchmark.completedVisits, benchmark.totalVisits)
                    }, fontSize = 12.sp, color = GoColors.Primary, modifier = Modifier.testTag("benchmark-status"))
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("cancel-benchmark")) { Text(resources.getString(R.string.cancel_benchmark)) }
                } else {
                    Button(onClick = { onStart(visits) }, enabled = config.backend != EngineBackend.NPU || supportsNpu,
                        modifier = Modifier.fillMaxWidth().testTag("start-benchmark")) { Text(if (selectedResult == null) resources.getString(R.string.run_benchmark) else resources.getString(R.string.rerun_benchmark)) }
                    if (benchmark.phase == BenchmarkPhase.CANCELLED) Hint(resources.getString(R.string.benchmark_cancelled))
                    if (benchmark.phase == BenchmarkPhase.ERROR) Text(resources.getString(R.string.benchmark_failed, benchmark.error ?: ""), color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp, modifier = Modifier.testTag("benchmark-error"))
                }
                selectedResult?.let { result ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = GoColors.Outline)
                    Text(resources.getString(R.string.benchmark_rate, decimal(result.visitsPerSecond)), fontSize = 28.sp, color = GoColors.Primary,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("benchmark-rate"))
                    Hint(resources.getString(R.string.benchmark_completed, SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(result.timestamp)), result.config.displayLabel(resources)))
                    Hint(resources.getString(R.string.benchmark_search_time, result.visits, decimal(result.searchNanos / 1e9, 2)))
                    Hint(resources.getString(R.string.benchmark_setup_time, decimal(result.initializationNanos / 1e6), decimal(result.warmupNanos / 1e6)))
                    result.samples.forEach { sample ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(benchmarkCaseLabel(resources, sample.label), fontSize = 12.sp, color = GoColors.Muted)
                            Text(resources.getString(R.string.benchmark_sample_rate, decimal(sample.visitsPerSecond)), fontSize = 12.sp, color = GoColors.Ink)
                        }
                    }
                    Hint(result.device)
                }
                Hint(resources.getString(R.string.benchmark_method))
                Hint(resources.getString(R.string.benchmark_caveat))
            }
            TextButton(onClick = onDiagnostics, enabled = !benchmark.running, modifier = Modifier.fillMaxWidth().testTag("engine-diagnostics")) {
                Text(resources.getString(R.string.model_validation))
            }
        }
    }
}

private fun decimal(value: Double, digits: Int = 1) = String.format(Locale.ROOT, "%.${digits}f", value)

internal fun EngineConfig.displayLabel(resources: Resources): String {
    val source = when (computeMode) {
        ComputeMode.LOCAL -> resources.getString(backend.labelRes)
        ComputeMode.CLOUD -> resources.getString(R.string.compute_cloud)
        ComputeMode.HYBRID -> resources.getString(R.string.compute_hybrid)
    }
    val modelLabel = if (computeMode == ComputeMode.CLOUD) resources.getString(R.string.cloud_server_model)
        else if (model.directory == null) model.id else model.sourceName
    return "$modelLabel · $source"
}

internal fun benchmarkCaseLabel(resources: Resources, label: String) = resources.getString(when (label) {
    "empty" -> R.string.case_empty
    "opening" -> R.string.case_opening
    "capture" -> R.string.case_capture
    else -> R.string.applying
})
