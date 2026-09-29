package com.theycallmeboxy.caulker.data.repository

import com.theycallmeboxy.caulker.data.db.entity.RomEntity
import com.theycallmeboxy.caulker.data.prefs.PrefsStore
import com.theycallmeboxy.caulker.data.saves.DownloadGuardDecision
import com.theycallmeboxy.caulker.data.saves.DOWNLOAD_GUARD_ENABLED
import com.theycallmeboxy.caulker.data.saves.GameSaveIdentity
import com.theycallmeboxy.caulker.data.saves.LocalSaveEntry
import com.theycallmeboxy.caulker.data.saves.LocalSaveUnit
import com.theycallmeboxy.caulker.data.saves.MatchingKeyKind
import com.theycallmeboxy.caulker.data.saves.SaveFormatFamilies
import com.theycallmeboxy.caulker.data.saves.SaveLocationScanResult
import com.theycallmeboxy.caulker.data.saves.SavePreset
import com.theycallmeboxy.caulker.data.saves.SavePresetRegistry
import com.theycallmeboxy.caulker.data.saves.SaveShape
import com.theycallmeboxy.caulker.data.saves.SaveTargetLayout
import com.theycallmeboxy.caulker.data.saves.SaveUploadPlan
import com.theycallmeboxy.caulker.data.saves.downloadGuardDecision
import com.theycallmeboxy.caulker.data.saves.planSaveUpload
import com.theycallmeboxy.caulker.data.saves.resolveSaveLocations
import com.theycallmeboxy.caulker.data.util.RootFileHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// A platform's resolved v1 save-location config: the real SavePreset a
// stored SavePlatformConfig's PresetKey points at, plus the folder the user
// configured (save-sync design doc, Part 2 §12 phase 3). Every function in
// this class returns null for an unconfigured platform (§6) or a config
// whose PresetKey no longer resolves to anything in SavePresetRegistry (a
// registry entry removed/renamed after the user configured it) -- both
// degrade identically to "legacy," never a crash or a partial/best-effort
// configured path. configuredPlatform() itself is prefs/registry-only (no
// I/O that can meaningfully fail) -- it's scan()/upload()/download() that
// can throw on a real folder-access error, and callers must NOT catch those
// and fall back to legacy (independent review, phase 3A fixes item 6: "A
// config lookup failure must fail that ROM's sync, never fall back to
// legacy" / "A scan failure must be an error, never 'no local save'").
data class ConfiguredPlatform(val preset: SavePreset, val folderPath: String)

@Singleton
class SaveLocationRepository @Inject constructor(
    private val prefsStore: PrefsStore,
    private val rootHelper: RootFileHelper,
    private val saveRepository: SaveRepository,
    private val writer: ConfiguredSaveWriter
) {
    suspend fun configuredPlatform(platformFsSlug: String?): ConfiguredPlatform? {
        val slug = platformFsSlug?.takeIf { it.isNotBlank() } ?: return null
        val config = prefsStore.getSavePlatformConfig(slug) ?: return null
        val preset = SavePresetRegistry.presetFor(slug, config.presetKey) ?: return null
        return ConfiguredPlatform(preset, config.folderPath)
    }

    // Scans a configured platform's folder and matches it against `games`
    // (§3). Returns null when the platform isn't configured -- the caller
    // falls back to the legacy per-ROM resolution path unchanged. THROWS on
    // a genuine scan failure (an unreadable folder in
    // AndroidSaveFolderScanner.snapshot, or an unreadable member of a matched
    // unit when resolveSaveLocations hashes it) rather than degrading to an empty result;
    // callers must treat that as a real sync error, not "no local save"
    // (item 6).
    suspend fun scan(platformFsSlug: String?, games: List<GameSaveIdentity>): ConfiguredScan? {
        val configured = configuredPlatform(platformFsSlug) ?: return null
        // Independent review, phase 3A round 2 item 3: every caller here is a
        // ViewModel running on viewModelScope (Main), and neither the folder
        // listing nor the per-unit hashing this triggers switched dispatcher
        // on its own -- file reads, md5, and zip packing were all running on
        // Main before this.
        return withContext(Dispatchers.IO) {
            val scanner = AndroidSaveFolderScanner.snapshot(rootHelper, configured.folderPath)
            val result = resolveSaveLocations(configured.preset, configured.folderPath, games, scanner)
            ConfiguredScan(configured.preset, configured.folderPath, result)
        }
    }

    // Single-ROM convenience for SaveSyncViewModel's per-ROM screen: the
    // matched unit for just this ROM against its platform's configured
    // folder, or null when the platform isn't configured or this ROM has no
    // local save yet. Propagates scan()'s exception on a real read failure
    // (item 6) -- callers must not swallow it into "null" (that's
    // indistinguishable from "no local save" to everything downstream).
    suspend fun unitFor(rom: RomEntity): Pair<SavePreset, LocalSaveUnit>? = withContext(Dispatchers.IO) {
        val identity = gameIdentityFor(rom) ?: return@withContext null
        val configured = scan(rom.platformFsSlug, listOf(identity)) ?: return@withContext null
        val unit = configured.result.units.firstOrNull { it.romId == rom.id } ?: return@withContext null
        configured.preset to unit
    }

    // Batch form of unitFor: scans each DISTINCT configured platform among
    // `roms` exactly ONCE, covering every one of that platform's ROMs in the
    // same pass, instead of a caller looping unitFor() per ROM and re-reading
    // the same folder once per ROM on it (independent review, item 7 --
    // "scan each configured platform once per sync pass ... reuse the
    // result"). A ROM on an unconfigured platform, or with no local save,
    // simply has no entry in the result -- same "missing means legacy/no
    // save" contract as unitFor(). Still propagates a real scan failure
    // (item 6); a caller processing many ROMs per platform should wrap its
    // own per-platform iteration if it wants one bad platform not to abort
    // the others.
    suspend fun unitsFor(roms: List<RomEntity>): Map<Int, LocalSaveUnit> = withContext(Dispatchers.IO) {
        val result = HashMap<Int, LocalSaveUnit>()
        for ((platformFsSlug, romsForPlatform) in roms.groupBy { it.platformFsSlug }) {
            val identities = romsForPlatform.mapNotNull { gameIdentityFor(it) }
            if (identities.isEmpty()) continue
            val scanned = scan(platformFsSlug, identities) ?: continue
            for (unit in scanned.result.units) {
                result[unit.romId] = unit
            }
        }
        result
    }

    // The §3 matching-key string a download needs before any local unit
    // exists to read it off of (a fresh download has nothing local yet, so
    // LocalSaveUnit.matchingKeyName isn't available) -- preset-aware, unlike
    // a bare ROM-stem guess: SAVE_TARGET-matched presets (Dreamcast, PSP)
    // must use RomEntity.saveTarget, not the ROM filename, or the resolved
    // download path would never agree with what the resolver itself would
    // later match on a scan (SaveLocationResolver.kt's matchingKeyName).
    fun matchingKeyNameFor(preset: SavePreset, rom: RomEntity): String? = when (preset.matchingKey) {
        MatchingKeyKind.SAVE_TARGET -> rom.saveTarget
        MatchingKeyKind.ROM_STEM ->
            rom.fileNameNoExt?.takeIf { it.isNotBlank() }
                ?: rom.fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
    }

    // §5's "resolved local path" for a save whose member paths are known
    // without a second scan (e.g. right after download() writes them) --
    // callers should use data.saves.resolvedPathFor(folderPath, shape,
    // writtenRelativePaths) directly (same function buildUnit uses, so a
    // baseline written here can never disagree with what the next scan
    // computes -- independent review round 2, item 1).

    private fun gameIdentityFor(rom: RomEntity): GameSaveIdentity? {
        val stem = rom.fileNameNoExt?.takeIf { it.isNotBlank() }
            ?: rom.fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: return null
        return GameSaveIdentity(
            romId = rom.id,
            romStem = stem,
            saveTarget = rom.saveTarget,
            saveTargetLayout = SaveTargetLayout.fromWire(rom.saveTargetLayout)
        )
    }

    // Uploads a configured-platform save unit (raw or zipped, per
    // SaveTransferPlan.kt's planSaveUpload -- a lone-present FILE_SET member
    // travels raw, matching Argosy's own behavior per that file's citations)
    // and returns the same UploadResult shape the legacy path uses. Upload
    // never touches local files, so callers can reuse `unit.resolvedPath`
    // for the baseline afterward without a rescan.
    suspend fun upload(
        configured: ConfiguredPlatform,
        unit: LocalSaveUnit,
        romId: Int,
        slotKey: String,
        sessionId: Int? = null,
        overwrite: Boolean = false
    ): UploadResult {
        // Reading the local member(s) + packing a zip happen here on IO; the
        // network call inside uploadSaveBytes does its own dispatching, so
        // this is only wrapped as far as the local file work.
        val (fileName, bytes) = withContext(Dispatchers.IO) {
            val scanner = AndroidSaveFolderScanner.snapshot(rootHelper, configured.folderPath)
            val entries = unit.memberPaths.map { LocalSaveEntry(it, configured.preset.shape == SaveShape.FOLDER) }
            val plan = planSaveUpload(configured.preset.shape, entries, scanner, unit.matchingKeyName, configured.preset.patterns)
                ?: error("Nothing to upload for \"${unit.matchingKeyName}\" -- local save is no longer readable")
            when (plan) {
                is SaveUploadPlan.Raw -> plan.relativePath.substringAfterLast('/') to plan.bytes
                is SaveUploadPlan.Zip -> plan.fileName to plan.bytes
            }
        }
        return saveRepository.uploadSaveBytes(
            romId, slotKey, fileName, bytes, sessionId,
            overwrite = overwrite, emulatorId = configured.preset.emulatorId
        )
    }

    // Downloads and writes a configured-platform save. The download guard
    // (§7, DOWNLOAD_GUARD_ENABLED) is checked here, before any network call,
    // since it's a pure decision over the incoming/configured emulator ids;
    // everything after fetching the bytes (backup, write, stale-member
    // cleanup, partial-write rollback) is ConfiguredSaveWriter's job --
    // see its doc comment for why that split exists (item 6/8 testability).
    suspend fun download(
        configured: ConfiguredPlatform,
        matchingKeyName: String,
        saveId: Int,
        serverFileName: String?,
        incomingEmulatorId: String? = null,
        sessionId: Int? = null,
        previousUnit: LocalSaveUnit? = null
    ): ConfiguredSaveWriter.DownloadOutcome {
        if (DOWNLOAD_GUARD_ENABLED) {
            val decision = downloadGuardDecision(incomingEmulatorId, configured.preset.emulatorId, SaveFormatFamilies.build())
            if (decision == DownloadGuardDecision.WARN_INCOMPATIBLE) {
                return ConfiguredSaveWriter.DownloadOutcome(
                    success = false,
                    reason = "download guard: incoming emulator id \"$incomingEmulatorId\" is a known-incompatible " +
                        "format for the configured \"${configured.preset.emulatorId}\"",
                    guardWarning = true
                )
            }
        }
        val bytes = saveRepository.downloadSaveBytes(saveId, sessionId)
        // The actual disk work (backup, write, stale-member cleanup, hashing
        // what was written) belongs on IO -- see scan()'s doc comment for the
        // same reasoning (item 3, independent review round 2).
        return withContext(Dispatchers.IO) {
            writer.apply(configured, matchingKeyName, bytes, serverFileName, previousUnit)
        }
    }
}

data class ConfiguredScan(val preset: SavePreset, val folderPath: String, val result: SaveLocationScanResult)
