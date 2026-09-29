package com.theycallmeboxy.caulker.ui.screens.unassignedsaves

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.repository.PlatformRepository
import com.theycallmeboxy.caulker.data.repository.RomRepository
import com.theycallmeboxy.caulker.data.repository.SaveLocationRepository
import com.theycallmeboxy.caulker.data.repository.gameSaveIdentityFor
import com.theycallmeboxy.caulker.data.saves.LocalSaveEntry
import com.theycallmeboxy.caulker.data.saves.SaveSyncMode
import com.theycallmeboxy.caulker.data.sync.SaveSyncLock
import com.theycallmeboxy.caulker.data.sync.SaveSyncOrchestrator
import com.theycallmeboxy.caulker.data.sync.SaveSyncOverallState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// v1 save-location UI (save-sync design doc, Part 2 §3 rule 3 / §12 phase
// 3B, item 3): the Unassigned-files screen serves two modes from one
// ViewModel/route --
//  - BROWSE (forRomId == null, reached from platform settings' setup
//    warnings "Review" link): lists everything unmatched, tapping an entry
//    opens a ROM picker.
//  - ASSIGN-FOR-ROM (forRomId != null, reached from SaveSyncScreen's
//    "Choose save file..."): tapping an entry assigns it to that ROM
//    directly and the screen pops itself back.
// `unassigned` is already narrowed to entries ELIGIBLE for this preset's
// shape by resolveSaveLocations (SaveLocationResolver.kt's
// eligibleUnassignedEntries) -- this ViewModel doesn't need its own
// filtering pass (Phase 3B fixes, should-fix item 5).
@HiltViewModel
class UnassignedFilesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val platformRepository: PlatformRepository,
    private val romRepository: RomRepository,
    private val saveLocationRepository: SaveLocationRepository,
    // Item 4: assign/unassign must respect the same app-wide save-mutation
    // lock every other write to a configured platform's folder does, and be
    // disabled while a bulk sync is running.
    private val saveSyncLock: SaveSyncLock,
    orchestrator: SaveSyncOrchestrator
) : ViewModel() {

    private val platformId: Int = checkNotNull(savedStateHandle["platformId"])

    // Screen.UnassignedSaves' route default (-1) means "no target ROM" --
    // NavType.IntType can't express a nullable query arg directly, so -1
    // (never a real ROM id) is the sentinel BROWSE mode uses.
    val forRomId: Int? = (savedStateHandle.get<Int>("forRomId") ?: -1).takeIf { it >= 0 }

    private var platformFsSlug: String? = null

    private val _platformName = MutableStateFlow<String?>(null)
    val platformName = _platformName.asStateFlow()

    private val _unassigned = MutableStateFlow<List<LocalSaveEntry>>(emptyList())
    val unassigned = _unassigned.asStateFlow()

    private val _platformRoms = MutableStateFlow<List<RomEntity>>(emptyList())
    val platformRoms = _platformRoms.asStateFlow()

    // Owner decision (2026-09-29): manual assignment is EXCHANGE-only. When
    // false (a DIRECT preset, or the platform isn't configured), the screen
    // becomes read-only diagnostics -- no assign action, no ROM picker.
    private val _isExchangeMode = MutableStateFlow(false)
    val isExchangeMode = _isExchangeMode.asStateFlow()

    // Item 5: "the ROM picker hides, or disables with a reason, ROMs that
    // already have a rule-matched unit" -- a ROM in this set already syncs
    // its own save automatically and doesn't need (and would be confusing
    // to offer) a manual assignment. Derived from the same scan as
    // `unassigned`: a ROM with a unit that ISN'T one of its own current
    // assignments got that unit from rule-based matching (§3 rules 1/2) --
    // resolveSaveLocations never lets an assignment coexist with a rule
    // match for the same ROM (rule matches always win), so this is exact,
    // not a heuristic.
    private val _ruleMatchedRomIds = MutableStateFlow<Set<Int>>(emptySet())
    val ruleMatchedRomIds = _ruleMatchedRomIds.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    val isBulkSyncing = orchestrator.state
        .map { it is SaveSyncOverallState.Syncing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // BROWSE mode: the entry the user tapped, now picking which game to
    // assign it to. Null closes the picker.
    private val _entryPendingAssignment = MutableStateFlow<LocalSaveEntry?>(null)
    val entryPendingAssignment = _entryPendingAssignment.asStateFlow()

    // Round-2 fixes, should-fix 5: a rejection from assignToRom must show
    // INSIDE the still-open RomPickerDialog -- the main screen's `error`
    // Text sits behind the modal Dialog and was invisible until the user
    // dismissed the picker. Separate from `error` (which assignForTarget --
    // no picker involved -- still uses) so the two never fight over the
    // same slot.
    private val _pickerError = MutableStateFlow<String?>(null)
    val pickerError = _pickerError.asStateFlow()

    // ASSIGN-FOR-ROM mode: true right after a successful direct assignment
    // -- the screen observes this to pop itself back to SaveSyncScreen.
    private val _assignedForTarget = MutableStateFlow(false)
    val assignedForTarget = _assignedForTarget.asStateFlow()

    init { load() }

    // TODO(phase-3B round 3+): like PlatformSettingsViewModel.computeWarnings,
    // this scans (and hashes) EVERY ROM's unit on the platform each call,
    // including on-resume refreshes -- left as a known perf cost (round-2
    // fixes nit) rather than fixed here.
    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val platform = platformRepository.getById(platformId)
                _platformName.value = platform?.name
                val slug = platform?.fsSlug ?: platform?.slug
                platformFsSlug = slug
                val configured = saveLocationRepository.configuredPlatform(slug)
                _isExchangeMode.value = configured?.preset?.mode == SaveSyncMode.EXCHANGE
                val roms = romRepository.observeByPlatform(platformId).first()
                _platformRoms.value = roms
                val identities = roms.mapNotNull { gameSaveIdentityFor(it) }
                val scan = saveLocationRepository.scan(slug, identities)
                val assignedRomIds = if (slug != null) saveLocationRepository.getAssignments(slug).keys else emptySet()
                if (scan == null) {
                    _unassigned.value = emptyList()
                    _ruleMatchedRomIds.value = emptySet()
                    _error.value = "This platform isn't configured for save sync."
                } else {
                    _unassigned.value = scan.result.unassigned
                    _ruleMatchedRomIds.value = scan.result.units.map { it.romId }.filterNot { it in assignedRomIds }.toSet()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    // BROWSE mode. Owner decision (2026-09-29): a no-op when this platform
    // isn't EXCHANGE -- defense in depth, since UnassignedFilesScreen
    // already doesn't wire this action up for a DIRECT platform's rows.
    fun beginAssign(entry: LocalSaveEntry) {
        if (!_isExchangeMode.value) return
        _pickerError.value = null
        _entryPendingAssignment.value = entry
    }
    fun cancelAssign() {
        _entryPendingAssignment.value = null
        _pickerError.value = null
    }

    fun assignToRom(entry: LocalSaveEntry, romId: Int) {
        if (!_isExchangeMode.value) return
        val slug = platformFsSlug ?: return
        viewModelScope.launch {
            if (!saveSyncLock.mutex.tryLock()) {
                _pickerError.value = "Save sync in progress — try again when it finishes"
                return@launch
            }
            try {
                when (val result = saveLocationRepository.assignUnassignedFile(platformId, slug, romId, entry.relativePath)) {
                    is SaveLocationRepository.AssignmentResult.Success -> _entryPendingAssignment.value = null
                    // Round-2 fixes, should-fix 5: shown INSIDE the picker
                    // (still open -- the user can immediately try a
                    // different game) rather than behind it.
                    is SaveLocationRepository.AssignmentResult.Rejected -> _pickerError.value = result.reason
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _pickerError.value = e.message ?: "Failed to assign this file"
            } finally {
                saveSyncLock.mutex.unlock()
            }
            load()
        }
    }

    // ASSIGN-FOR-ROM mode. Owner decision (2026-09-29): defense in depth,
    // same reasoning as beginAssign above -- SaveSyncScreen already only
    // offers "Choose save file..." (the only way to reach this mode) for an
    // EXCHANGE preset.
    fun assignForTarget(entry: LocalSaveEntry) {
        if (!_isExchangeMode.value) return
        val slug = platformFsSlug ?: return
        val romId = forRomId ?: return
        viewModelScope.launch {
            if (!saveSyncLock.mutex.tryLock()) {
                _error.value = "Save sync in progress — try again when it finishes"
                return@launch
            }
            try {
                when (val result = saveLocationRepository.assignUnassignedFile(platformId, slug, romId, entry.relativePath)) {
                    is SaveLocationRepository.AssignmentResult.Success -> _assignedForTarget.value = true
                    is SaveLocationRepository.AssignmentResult.Rejected -> _error.value = result.reason
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to assign this file"
            } finally {
                saveSyncLock.mutex.unlock()
            }
        }
    }
}
