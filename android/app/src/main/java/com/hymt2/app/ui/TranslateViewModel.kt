package com.hymt2.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hymt2.app.HyMtApp
import com.hymt2.app.device.RuntimePolicy
import com.hymt2.app.engine.EngineOptions
import com.hymt2.app.engine.GenerationStats
import com.hymt2.app.model.DownloadPhase
import com.hymt2.app.model.DownloadProgress
import com.hymt2.app.model.KnownModels
import com.hymt2.app.model.ModelDownloader
import com.hymt2.app.model.ModelEntry
import com.hymt2.app.model.ModelManager
import com.hymt2.app.model.ModelSpec
import com.hymt2.app.translate.Language
import com.hymt2.app.translate.Languages
import com.hymt2.app.translate.PromptBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one in-flight model download; at most one runs at a time. */
data class DownloadUi(val specId: String, val progress: DownloadProgress? = null)

data class UiState(
    val models: List<ModelEntry> = emptyList(),
    val selected: ModelEntry? = null,
    val loadedModelId: String? = null,
    val loading: Boolean = false,
    val source: Language? = null,
    val target: Language = Languages.byCode("en")!!,
    val input: String = "",
    val output: String = "",
    val running: Boolean = false,
    val stats: GenerationStats? = null,
    val status: String = "",
    val deviceLine: String = "",
    val download: DownloadUi? = null,
    val recommendedModelId: String = "stq1_0",
)

class TranslateViewModel(app: Application) : AndroidViewModel(app) {
    private val hy = app as HyMtApp
    private val models = ModelManager(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var job: Job? = null
    private var downloadJob: Job? = null
    private val downloader = ModelDownloader()

    init {
        viewModelScope.launch {
            val line = withContext(Dispatchers.Default) { "${hy.soc.label} · backends ${hy.backends.joinToString("+")}" }
            val recommended = RuntimePolicy.recommendedModelId(hy.soc, hy.backends)
            _state.update { it.copy(deviceLine = line, recommendedModelId = recommended) }
            refreshModels(autoLoad = true)
        }
    }

    fun refreshModels(autoLoad: Boolean = false) {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { models.available() }
            _state.update { it.copy(models = found, selected = it.selected ?: found.firstOrNull(),
                status = if (found.isEmpty()) "No model yet. Download one below, tap Import, or adb push a .gguf to ${models.searchDirs().first()}" else it.status) }
            if (autoLoad) found.firstOrNull()?.let { selectModel(it) }
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(status = "Importing model…") }
            val result = withContext(Dispatchers.IO) { models.import(uri) }
            result.onFailure { e -> _state.update { it.copy(status = "Import failed: ${e.message}") } }
            result.onSuccess { refreshModels() }
        }
    }

    /** Downloads [spec] into the app's files dir, then loads it if nothing is loaded yet. */
    fun downloadModel(spec: ModelSpec) {
        if (_state.value.download != null) return
        _state.update { it.copy(download = DownloadUi(spec.id), status = "Downloading ${spec.label}…") }
        downloadJob = viewModelScope.launch {
            val result = downloader.download(spec, getApplication<Application>().filesDir) { p ->
                _state.update { st -> st.copy(download = DownloadUi(spec.id, p)) }
            }
            _state.update { it.copy(download = null) }
            result.onFailure { e -> _state.update { it.copy(status = "Download failed: ${e.message}") } }
            result.onSuccess {
                _state.update { it.copy(status = "Downloaded ${spec.label}") }
                val found = withContext(Dispatchers.IO) { models.available() }
                _state.update { it.copy(models = found) }
                val entry = found.firstOrNull { it.spec.id == spec.id }
                if (entry != null && _state.value.loadedModelId == null) selectModel(entry)
            }
        }
    }

    /** Stops the download; the partial file is kept so the next attempt resumes. */
    fun cancelDownload() {
        downloadJob?.cancel()
        _state.update { it.copy(download = null, status = "Download paused. Tap Download to resume.") }
    }

    fun selectModel(entry: ModelEntry) {
        if (_state.value.loading) return
        job?.cancel()
        hy.engine.cancel()
        _state.update { it.copy(selected = entry, loading = true, status = "Loading ${entry.spec.label}…") }
        viewModelScope.launch {
            val plan = withContext(Dispatchers.Default) { RuntimePolicy.plan(hy.soc, entry.spec, hy.backends) }
            val t0 = System.nanoTime()
            val result = hy.engine.load(
                EngineOptions(
                    entry.file.absolutePath, nThreads = plan.threads, cpus = plan.cpus,
                    nThreadsBatch = plan.batchThreads, batchCpus = plan.batchCpus, offload = plan.offload,
                ),
            )
            val ms = (System.nanoTime() - t0) / 1_000_000
            _state.update {
                result.fold(
                    onSuccess = { s -> it.copy(loading = false, loadedModelId = entry.spec.id, status = "Loaded in $ms ms · ${plan.reason} · ${plan.threads} threads") },
                    onFailure = { e -> it.copy(loading = false, loadedModelId = null, status = "Load failed: ${e.message}") },
                )
            }
        }
    }

    fun setInput(text: String) = _state.update { it.copy(input = text) }
    fun setSource(lang: Language?) = _state.update { it.copy(source = lang) }
    fun setTarget(lang: Language) = _state.update { it.copy(target = lang) }

    fun swapLanguages() = _state.update {
        val newTarget = it.source ?: return@update it
        it.copy(source = it.target, target = newTarget, input = it.output.ifBlank { it.input }, output = "")
    }

    fun translate() {
        val s = _state.value
        if (s.running || s.input.isBlank() || s.loadedModelId == null) return
        val prompt = PromptBuilder.build(s.input.trim(), s.source, s.target)
        _state.update { it.copy(running = true, output = "", stats = null, status = "Translating…") }
        job = viewModelScope.launch {
            val out = StringBuilder()
            val stats = runCatching {
                hy.engine.generate(prompt) { piece ->
                    out.append(piece)
                    val text = out.toString()
                    _state.update { it.copy(output = text) }
                }
            }
            _state.update {
                stats.fold(
                    onSuccess = { st -> it.copy(running = false, stats = st, status = "Done") },
                    onFailure = { e -> it.copy(running = false, status = "Error: ${e.message}") },
                )
            }
        }
    }

    fun stop() = hy.engine.cancel()
}
