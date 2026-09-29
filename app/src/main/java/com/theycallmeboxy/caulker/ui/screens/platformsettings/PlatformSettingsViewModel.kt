package com.theycallmeboxy.caulker.ui.screens.platformsettings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.db.entity.PlatformEntity
import com.theycallmeboxy.caulker.data.prefs.PlatformOverride
import com.theycallmeboxy.caulker.data.prefs.PlatformOverrideMode
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.repository.PlatformRepository
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.data.repository.SaveLocationRepository
import com.theycallmeboxy.caulker.data.repository.gameSaveIdentityFor
import com.theycallmeboxy.caulker.data.saves.PresetKey
import com.theycallmeboxy.caulker.data.saves.RetroArchPresetInfo
import com.theycallmeboxy.caulker.data.saves.SavePlatformConfig
import com.theycallmeboxy.caulker.data.saves.SavePresetRegistry
import com.theycallmeboxy.caulker.data.saves.SaveSetupWarnings
import com.theycallmeboxy.caulker.data.saves.computeSaveSetupWarnings
import com.theycallmeboxy.caulker.data.saves.computeSaveSetupWarningsForFailure
import com.theycallmeboxy.caulker.data.sync.SaveSyncLock
import com.theycallmeboxy.caulker.data.sync.SaveSyncOrchestrator
import com.theycallmeboxy.caulker.data.sync.SaveSyncOverallState
import com.theycallmeboxy.caulker.data.util.RootFileHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlatformSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val platformRepository: PlatformRepository,
    private val romRepository: RomRepository,
    private val prefsStore: PrefsStore,
    // v1 save-location UI (save-sync design doc, Part 2 §12 phase 3B).
    private val saveLocationRepository: SaveLocationRepository,
    private val rootHelper: RootFileHelper,
    // Round-2 fixes, should-fix 2: Save/Clear must take the same app-wide
    // save-mutation lock every other configured-platform write does, and be
    // disabled while a bulk sync is running -- otherwise a config change
    // mid-sync mixes the OLD folder/preset with the NEW one inside
    // SaveSyncOrchestrator's still-running executeDownload/removeStaleMembers.
    private val saveSyncLock: SaveSyncLock,
    orchestrator: SaveSyncOrchestrator
) : ViewModel() {

    val isBulkSyncing: StateFlow<Boolean> = orchestrator.state
        .map { it is SaveSyncOverallState.Syncing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val platformId: Int = checkNotNull(savedStateHandle["platformId"])

    private val _platform = MutableStateFlow<PlatformEntity?>(null)
    val platform = _platform.asStateFlow()

    val romBasePath = prefsStore.romBasePath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val saveBasePath = prefsStore.saveBasePath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val biosBasePath = prefsStore.biosBasePath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val savedOverride: StateFlow<PlatformOverride?> = combine(
        _platform, prefsStore.platformOverrides
    ) { platform, overrides ->
        val key = platform?.fsSlug ?: platform?.slug ?: return@combine null
        overrides[key]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // --- Save sync setup (item 1) ------------------------------------------

    // Every preset v1 offers for this platform (empty if none -- §6 fallback,
    // the "Save sync setup" section then only offers Default).
    val availablePresets: StateFlow<List<RetroArchPresetInfo>> = _platform
        .map { p -> SavePresetRegistry.presetsForPlatform(p?.fsSlug ?: p?.slug) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // The saved v1 config for this platform, or null for "Default (current
    // behavior)" (§6). Same combine-on-platform-load pattern as savedOverride.
    val savedSaveConfig: StateFlow<SavePlatformConfig?> = combine(
        _platform, prefsStore.savePlatformConfigs
    ) { platform, configs ->
        val key = platform?.fsSlug ?: platform?.slug ?: return@combine null
        configs[key]
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // --- Setup warnings (item 2) --------------------------------------------

    private val _saveSetupWarnings = MutableStateFlow<SaveSetupWarnings?>(null)
    val saveSetupWarnings: StateFlow<SaveSetupWarnings?> = _saveSetupWarnings.asStateFlow()

    private val _isCheckingSaveSetup = MutableStateFlow(false)
    val isCheckingSaveSetup: StateFlow<Boolean> = _isCheckingSaveSetup.asStateFlow()

    private var warningsJob: Job? = null

    init {
        viewModelScope.launch {
            _platform.value = platformRepository.getById(platformId)
        }
        // Recompute warnings any time the saved config actually changes
        // (initial load, Save, Clear) -- not on every recomposition, since
        // this does real folder I/O (SaveLocationRepository.scan, off the
        // main thread already).
        viewModelScope.launch {
            savedSaveConfig.collect { config ->
                warningsJob?.cancel()
                if (config == null) {
                    _saveSetupWarnings.value = null
                    return@collect
                }
                warningsJob = launch { computeWarnings() }
            }
        }
    }

    // TODO(phase-3B round 3+): this scans (and hashes) EVERY ROM's unit on
    // the platform each time it runs, including every OnResumeEffect-driven
    // refresh from PlatformSettingsScreen -- left as a known perf cost
    // (round-2 fixes nit) rather than fixed here; computeSaveSetupWarnings
    // only actually needs unit COUNTS/presence, not their hashes.
    private suspend fun computeWarnings() {
        val slug = _platform.value?.fsSlug ?: _platform.value?.slug ?: return
        _isCheckingSaveSetup.value = true
        try {
            val roms = romRepository.observeByPlatform(platformId).first()
            val identities = roms.mapNotNull { gameSaveIdentityFor(it) }
            val scan = saveLocationRepository.scan(slug, identities)
            _saveSetupWarnings.value = scan?.let { computeSaveSetupWarnings(it.result) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _saveSetupWarnings.value = computeSaveSetupWarningsForFailure(e.message)
        } finally {
            _isCheckingSaveSetup.value = false
        }
    }

    fun refreshSaveSetupWarnings() {
        warningsJob?.cancel()
        warningsJob = viewModelScope.launch { computeWarnings() }
    }

    // Surfaced when saveSaveLocationConfig's folder validation (item 7)
    // rejects a path, or the save-sync lock is busy -- the screen shows
    // this and saves nothing.
    private val _saveConfigError = MutableStateFlow<String?>(null)
    val saveConfigError: StateFlow<String?> = _saveConfigError.asStateFlow()

    fun dismissSaveConfigError() { _saveConfigError.value = null }

    private val lockedMessage = "Save sync in progress — try again when it finishes"

    // Saves (or replaces) this platform's v1 config. The screen is
    // responsible for confirming the change with the user first (item 1 --
    // "changing preset or folder must be explicit") and for explaining the
    // §5 baseline behavior (and, per item 3B fixes, that this clears any
    // existing Unassigned-file assignments) in that confirmation. Before
    // persisting anything, the folder is validated (item 7) -- an absolute
    // path that exists, is a directory, and is readable via RootFileHelper;
    // treeUriToPath's own fallback for an unrecognized document tree, or a
    // manually typed path, can otherwise produce something that looks like
    // a path but isn't one Caulker can actually use. A rejected path saves
    // nothing and surfaces saveConfigError instead.
    //
    // Round-2 fixes, should-fix 2: takes saveSyncLock BEFORE persisting
    // anything, same busy message every other locked action uses -- a
    // config change while the orchestrator's bulk sync is mid-flight would
    // otherwise let its still-running executeDownload/removeStaleMembers
    // read the OLD folder/preset for calls it already started and the NEW
    // one for calls it hasn't gotten to yet, mixing state from both
    // configs against the same run.
    fun saveSaveLocationConfig(presetKey: PresetKey, folderPath: String) = viewModelScope.launch {
        val slug = _platform.value?.fsSlug ?: _platform.value?.slug ?: return@launch
        val path = folderPath.trim()
        if (!rootHelper.isReadableDirectory(path)) {
            _saveConfigError.value = "\"$path\" isn't a folder Caulker can read. Pick it again using Browse, " +
                "or check the typed path."
            return@launch
        }
        if (!saveSyncLock.mutex.tryLock()) {
            _saveConfigError.value = lockedMessage
            return@launch
        }
        try {
            saveLocationRepository.setSaveLocationConfig(slug, SavePlatformConfig(presetKey, path))
        } finally {
            saveSyncLock.mutex.unlock()
        }
    }

    // Clear/reset back to "Default (current behavior)" (§6). Also clears
    // assignments (SaveLocationRepository.clearSaveLocationConfig). Same
    // lock as saveSaveLocationConfig above, same reasoning.
    fun clearSaveLocationConfig() = viewModelScope.launch {
        val slug = _platform.value?.fsSlug ?: _platform.value?.slug ?: return@launch
        if (!saveSyncLock.mutex.tryLock()) {
            _saveConfigError.value = lockedMessage
            return@launch
        }
        try {
            saveLocationRepository.clearSaveLocationConfig(slug)
        } finally {
            saveSyncLock.mutex.unlock()
        }
    }

    fun saveOverride(override: PlatformOverride) = viewModelScope.launch {
        val slug = _platform.value?.fsSlug ?: _platform.value?.slug ?: return@launch
        prefsStore.setPlatformOverride(slug, override)
    }
}
