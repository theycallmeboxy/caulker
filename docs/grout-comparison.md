# Caulker ↔ Grout Comparison

Grout is a Go binary for handheld CFW (TrimUI Brick/Smart Pro, MuOS, etc.) that
syncs ROMs, saves, BIOS, and metadata with a RomM server. Caulker is the
Android-app analogue. Both speak the same RomM HTTP API; the mechanisms below
are largely the same.

This doc maps each feature area to where it lives in both codebases and calls
out where the two diverge. See [save-sync-design.md](save-sync-design.md) for
the deep dive on save sync specifically.

---

## 1. Architectural shape

| | Grout | Caulker |
|---|---|---|
| Language / form | Go binary on CFW handhelds | Android app (Kotlin + Compose) |
| UI framework | `gabagool` (immediate-mode TUI) | Jetpack Compose + Material3 |
| State | Cache files on disk + `internal/config.go` | Room DB + DataStore prefs + Hilt-injected repositories |
| Concurrency | goroutines + channels | coroutines + Flows |
| ROM/save filesystem | CFW-tailored (`cfw.BaseSavePath()`, per-CFW emulator dirs) | User-configurable base paths + per-platform overrides ([PrefsStore.kt](../app/src/main/java/com/theycallmeboxy/caulker/data/prefs/PrefsStore.kt)) |

The biggest structural difference: grout knows which CFW it's running on and
hard-codes that CFW's save/ROM layout. Caulker has to ask the user where saves
and ROMs live, since Android emulators (RetroArch, Dolphin, Citra, PPSSPP) all
pick their own conventions.

---

## 2. HTTP client

**Grout** — [romm/client.go](../../grout/romm/client.go)
- Hand-rolled `Client` wrapping `*http.Client` with a base URL and auth header
- Four request shapes: `doRequest` (JSON in/out), `doRequestRaw` (bytes out),
  `doRequestRawWithQuery` (bytes out + query params), `doMultipartRequest`
  (multipart upload with conflict-error parsing)
- Query params encoded via `sonh/qs` from struct tags (`qs:"slot,omitempty"`)
- TLS skip-verify supported via `WithInsecureSkipVerify`
- 30s default timeout

**Caulker** — [RommApiService.kt](../app/src/main/java/com/theycallmeboxy/caulker/data/api/RommApiService.kt)
- Retrofit interface with Moshi adapters for JSON
- Streaming responses for downloads (`@Streaming`)
- Multipart for save uploads (`@Multipart`, `@Part`)
- Auth/timeout/TLS handled by an OkHttp interceptor chain (in the DI module)

**Divergence**:
- Both clients now parse `409` conflict responses into structured exceptions:
  grout's `ConflictError` ([romm/errors.go](../../grout/romm/errors.go)) and
  Caulker's `SaveConflictException`
  ([SaveConflictException.kt](../app/src/main/java/com/theycallmeboxy/caulker/data/api/SaveConflictException.kt)).
  Caulker's parser accepts both the FastAPI-style `{detail: {...}}` wrapper
  and the bare object.

---

## 3. Endpoints

Both clients hit the same RomM endpoints. The canonical list is in
[grout/romm/endpoints.go](../../grout/romm/endpoints.go); Caulker inlines them
as Retrofit annotations in [RommApiService.kt](../app/src/main/java/com/theycallmeboxy/caulker/data/api/RommApiService.kt).

| Endpoint | Grout | Caulker |
|---|---|---|
| `GET /api/heartbeat` | ✓ | ✓ |
| `POST /api/login` (basic auth) | ✓ | ✗ — only token-exchange |
| `POST /api/client-tokens/exchange` | ✓ | ✓ |
| `GET /api/users/me` | ✓ | ✓ |
| `GET /api/platforms` | ✓ | ✓ |
| `GET /api/platforms/{id}` | ✓ | ✓ |
| `GET /api/platforms/identifiers` | ✓ | ✗ |
| `GET /api/roms` (paginated) | ✓ | ✓ |
| `GET /api/roms/{id}` | ✓ | ✓ |
| `GET /api/roms/by-hash` | ✓ | ✗ |
| `GET /api/roms/identifiers` | ✓ | ✗ |
| `GET /api/collections` | ✓ | ✓ |
| `GET /api/collections/smart` | ✓ | ✓ |
| `GET /api/collections/virtual` | ✓ | ✗ |
| `GET /api/firmware` | ✓ | ✓ |
| `GET /api/saves` | ✓ | ✓ |
| `GET /api/saves/summary` | ✓ | ✓ (declared, unused) |
| `GET /api/saves/{id}/content` | ✓ | ✓ |
| `POST /api/saves` (upload) | ✓ | ✓ |
| `PUT /api/saves/{id}` (update) | ✓ | ✓ |
| `POST /api/saves/{id}/downloaded` | ✓ | ✓ |
| `GET/POST/PUT/DELETE /api/devices[/{id}]` | ✓ | partial (get + register only) |

Caulker is missing the `*/identifiers` endpoints — these return just the list
of IDs, used by grout for fast delta-sync (compare client cache against server
identifiers to detect deletions). Caulker currently does an `updated_after`
fetch and never reconciles deletions.

---

## 4. Auth & connection

**Grout** — [romm/auth.go](../../grout/romm/auth.go)
- `ValidateConnection()` — pings `/api/heartbeat`, returns typed errors for
  500/connect/timeout
- `Login(user, pass)` — basic auth, returns typed `AuthError` for 401/403/500
- `ExchangeToken(baseURL, code, insecureSkipVerify)` — exchanges a UI-paired
  code for a long-lived token
- `ValidateToken()` — pokes `/api/platforms`
- `GetCurrentUser()` — `/api/users/me`

**Caulker** — auth flow lives in [AuthRepository.kt](../app/src/main/java/com/theycallmeboxy/caulker/data/repository/) (read individually if needed):
- Uses the same `client-tokens/exchange` flow
- Stores token via `PrefsStore.setAuthToken`
- No basic-auth login path — server pairing happens only via code exchange

**Divergence**:
- Grout supports both login (basic auth) AND token exchange. Caulker is
  token-exchange only.
- Grout's `ClassifyError` produces typed errors for DNS / connection refused /
  timeout. Caulker surfaces the raw OkHttp exception.

---

## 5. Device registration

**Grout** — [romm/devices.go](../../grout/romm/devices.go), [sync/flow.go:RegisterDevice](../../grout/sync/flow.go)
```go
RegisterDevice(name, platform=CFW, client="grout", clientVersion)
```
Returns a `Device` with `id` set. The device ID is the foreign key everywhere
the API tracks per-device sync state (see `DeviceSaveSync` below).

**Caulker** — [SaveRepository.getOrRegisterDeviceId()](../app/src/main/java/com/theycallmeboxy/caulker/data/repository/SaveRepository.kt#L38-L52)
```kotlin
RegisterDeviceRequest(name=deviceName ?: android.os.Build.MODEL,
                      platform="android", client="caulker", clientVersion=BuildConfig.VERSION_NAME)
```
Lazily registers on first save-sync op; ID is cached in DataStore.

**Divergence**: grout sets `platform = string(cfw.GetCFW())` (e.g.,
`"trimui_brick"`); Caulker always sends `"android"`. Both serve the same
purpose — letting the server distinguish device classes.

---

## 6. Heartbeat

Both clients hit `/api/heartbeat` for connection validation. Grout uses it as a
pre-flight in `ValidateConnection()`; Caulker uses it for the same purpose in
its settings/server-URL flow.

The response shape differs slightly:
- Grout decodes `{SYSTEM: {VERSION: ...}}` ([heartbeat.go](../../grout/romm/heartbeat.go))
- Caulker decodes `{version: ..., any_source_supported: ...}` ([ApiModels.kt:HeartbeatResponse](../app/src/main/java/com/theycallmeboxy/caulker/data/api/model/ApiModels.kt))

The server presumably returns both nested shapes; each client picks the fields
it needs. Worth a one-line look at the server payload before relying on either
shape for new features.

---

## 7. Platforms

**Grout** — [romm/platforms.go](../../grout/romm/platforms.go)
- `Platform` struct includes `FSSlug`, `Name`, `CustomName`, `Manufacturer`,
  `Generation`, `Type`, `HasBIOS`, `SupportedExtensions`, embedded `Firmware[]`
- `DisambiguatePlatformNames(platforms)` — when two platforms share a display
  name (e.g., multiple "Arcade" entries), appends the FS slug:
  `"Arcade (fbneo)"`. Sets `ApiName` to the original.

**Caulker** — [Entities.kt:PlatformEntity](../app/src/main/java/com/theycallmeboxy/caulker/data/db/entity/Entities.kt), [ApiModels.kt:PlatformResponse](../app/src/main/java/com/theycallmeboxy/caulker/data/api/model/ApiModels.kt), [PlatformRepository.kt](../app/src/main/java/com/theycallmeboxy/caulker/data/repository/PlatformRepository.kt)
- `PlatformEntity` has `id`, `name`, `slug`, `fsSlug`, `romCount`,
  `firmwareCount`, `logoPath`, `updatedAt`
- Disambiguation happens in `sync()` — duplicate names get the fs_slug suffix
  appended before insert

**Divergence**: Caulker drops `manufacturer`, `generation`, `type`, `hasBIOS`,
`supportedExtensions`, and `Firmware[]`. If we ever want to show
manufacturer/generation grouping or pre-filter by `hasBIOS`, those fields would
need to be added to the model and the DB.

---

## 8. ROMs

**Grout** — [romm/roms.go](../../grout/romm/roms.go), [sync/roms.go](../../grout/sync/roms.go)
- `Rom` is a big struct with full metadata: hashes (CRC/MD5/SHA1/RA),
  ScreenScraper metadata (Box2D/Box3D/MixImage/Marquee/etc.), `Files[]` for
  multi-file games, `HasMultipleFiles`, `Regions`, `Languages`, `Tags`, etc.
- `IsDownloaded(resolver)` — checks the resolved local path; for multi-file
  ROMs checks the `.m3u`; for single-file checks the `Files[0].FileName`
- ROM scanning (`cfw.ScanRoms`) walks CFW-known platform directories, then
  `ResolveLocalRoms` does an `fs_slug + nameNoExt` cache lookup to match
  local files to server ROM IDs
- Multi-file games: grout creates an `.m3u` referencing all files

**Caulker** — [RomRepository.kt](../app/src/main/java/com/theycallmeboxy/caulker/data/repository/RomRepository.kt), [Entities.kt:RomEntity](../app/src/main/java/com/theycallmeboxy/caulker/data/db/entity/Entities.kt)
- `RomEntity` has the basics: id, name, fileName, fileSize, platform info,
  slug, summary, rating, dates, genres/regions/languages (as comma strings),
  coverPath, hasSaves
- `getInstalledRomIds(roms)` checks each ROM's resolved path via
  `File.exists()`
- `downloadRom(romId, fileName, platformFsSlug)` streams to the configured
  ROM directory (with optional per-platform override)
- **No multi-file support** — assumes one file per ROM

**Divergence**:
- Caulker doesn't store hashes — can't sync by hash, can't do
  RetroAchievements lookups
- Caulker doesn't model ScreenScraper artwork — only the `coverPath` field
- Caulker doesn't handle multi-file games (multi-disc PS1, multi-cart N64
  collections, etc.)
- Caulker doesn't sync the `Files[]` list — if a server-side ROM is a
  multi-file game, Caulker downloads `fileName` as a single file, which is
  likely wrong

---

## 9. Saves (the big one)

See [save-sync-design.md](save-sync-design.md) for the full breakdown. Quick
mapping:

| Concept | Grout | Caulker |
|---|---|---|
| Local save model | `LocalSave` ([sync/models.go](../../grout/sync/models.go)) | `SlotUiState` ([SaveSyncViewModel.kt](../app/src/main/java/com/theycallmeboxy/caulker/ui/screens/savesync/SaveSyncViewModel.kt)) |
| Server save | `romm.Save` ([romm/saves.go](../../grout/romm/saves.go)) | `SaveResponse` ([ApiModels.kt](../app/src/main/java/com/theycallmeboxy/caulker/data/api/model/ApiModels.kt)) |
| Per-device sync state | `DeviceSaveSync` | `DeviceSaveSync` (same shape) |
| Sync state machine | `determineAction` in [sync/flow.go:387](../../grout/sync/flow.go#L387) | `determineSyncAction` in [SaveSyncViewModel.kt:324](../app/src/main/java/com/theycallmeboxy/caulker/ui/screens/savesync/SaveSyncViewModel.kt#L324) |
| Sync orchestration | `ResolveSaveSync` → `ExecuteSaveSync` | Per-ROM: `SaveSyncViewModel.loadSummary`; all ROMs: `SaveSyncAllViewModel.refresh` + `syncAll` |
| Local filename resolution | `LocalSave.FilePath` (existing) → ROM-name-derived fallback | `SaveRepository.resolveLocalSaveFileName` |
| Backup before overwrite | `.backup/` sibling dir, timestamp-formatted | `.caulker_backup/` sibling dir, timestamp-formatted, max 5 |
| Multi-slot support | Yes (slot pref, fallback for first download) | Yes (slot pref, visible slot set, synthesized slots) |
| Directory saves (PSP) | Yes — `IsDirectorySavePlatform`, zip on upload, unzip on download | **Not implemented** |
| PSP Game ID resolution | `extractPSPGameID` + `pspdb.Titles` lookup | **Not implemented** |
| Conflict detection | Per-device `lastSyncedAt` + `isCurrent` + mtime, 1s precision | Same logic, 2s tolerance band |
| Optimistic download flag | Passes `optimistic=true` query param | Passes `optimistic=true` (Retrofit default) |
| Save extension whitelist | `ValidSaveExtensions` ([sync/saves.go](../../grout/sync/saves.go)) | None — Caulker drives off the server's filename rather than scanning |

### 9a. Sync action determination

Both use the same decision tree:
1. No remote → UPLOAD
2. No local → DOWNLOAD
3. Has device-sync record for this device:
   - Both changed since `lastSyncedAt` → CONFLICT
   - Device is current and local newer → UPLOAD; else SKIP (UP_TO_DATE)
   - Device not current → DOWNLOAD
4. No device-sync record → fall back to raw mtime comparison

Caulker uses a `2_000ms` tolerance window in the raw-mtime branch
([SaveSyncViewModel.kt:339-354](../app/src/main/java/com/theycallmeboxy/caulker/ui/screens/savesync/SaveSyncViewModel.kt#L339-L354)).
Grout uses second-truncation via `time.Time.Truncate(time.Second)`
([flow.go:402-403](../../grout/sync/flow.go#L402-L403)) — same intent, slightly
different mechanism.

### 9b. Upload mtime

Both clients set the local file's mtime to the server's `updatedAt` after a
successful upload, so the next comparison won't immediately want to upload
again from clock skew. See [SaveRepository.kt:147-161](../app/src/main/java/com/theycallmeboxy/caulker/data/repository/SaveRepository.kt#L147-L161) and
[flow.go:597-600](../../grout/sync/flow.go#L597-L600).

### 9c. Local filename derivation

This is the area we just landed a fix in. Both clients use a stable local
filename derived from `romBase + "." + saveExtension` rather than the server's
timestamped `file_name` (e.g., `[2026-05-13_00-19-56].srm`). Caulker
additionally falls back to scanning for legacy timestamped files to migrate
older Caulker installs gracefully — grout doesn't need this because grout's
local filenames were always stable.

### 9d. Things grout does that Caulker doesn't

- **`DiscoverRemoteSaves`**: grout actively scans local ROMs that *don't yet
  have a local save* and checks if the server has one to download. Caulker
  only surfaces ROMs the user has explicitly enrolled.
- **Cache-recorded sync history**: grout writes `cache.SaveSyncRecord`
  entries for the UI's "recent activity" view. Caulker doesn't track sync
  history beyond the most recent message string per slot.
- **`AvailableSlots` on first-time downloads**: grout surfaces a slot-picker
  UI when a server has multiple slots for a ROM with no local save. Caulker
  picks the preferred slot (or falls back to most-recent) automatically.

### 9e. Concurrent fan-out (both)

Both clients parallelize per-ROM `GetSaves` calls with a semaphore of 8:
- Grout: [flow.go:253-307](../../grout/sync/flow.go#L253-L307) — goroutine
  pool inside `FetchRemoteSaves`/`DiscoverRemoteSaves`
- Caulker: [SaveSyncAllViewModel.kt:54-117](../app/src/main/java/com/theycallmeboxy/caulker/ui/screens/savesync/SaveSyncAllViewModel.kt#L54-L117)
  — `coroutineScope { async { semaphore.withPermit { … } } }` over the
  enrolled-ROM list

The per-ROM `SaveSyncViewModel` fetches a single ROM so doesn't need fan-out.

---

## 10. Firmware (BIOS)

**Grout** — [romm/firmware.go](../../grout/romm/firmware.go)
- `Firmware` struct has hashes, sizes, `IsVerified`
- `GetFirmware(platformID)` returns the list, populates a download URL on each
  entry: `/api/firmware/{id}/content/{filename}`

**Caulker** — [RomRepository.kt:downloadFirmware](../app/src/main/java/com/theycallmeboxy/caulker/data/repository/RomRepository.kt#L200-L256), [ApiModels.kt:FirmwareResponse](../app/src/main/java/com/theycallmeboxy/caulker/data/api/model/ApiModels.kt)
- `FirmwareResponse` has the basics; no `IsVerified`, no `FullPath`
- Resolves a BIOS dir per platform with override fallback chain:
  per-platform `biosPath` → global `biosBasePath` → `$romBase/bios`
- Streams to disk with progress

**Divergence**: grout exposes `IsVerified` (server-side hash check passed);
Caulker doesn't. Useful if we want to show a "BIOS verified" indicator.

---

## 11. Delta sync (`updated_after`)

Both clients support paged sync with the `updated_after` query parameter to
fetch only records modified since a stored timestamp.

- Grout: `GetPlatformsQuery.UpdatedAfter`, `GetRomsQuery.UpdatedAfter`,
  `GetCollectionsQuery.UpdatedAfter`
- Caulker: same params, stored per-platform in
  `PrefsStore.PLATFORM_SYNC_TIMES` ([PrefsStore.kt:175-189](../app/src/main/java/com/theycallmeboxy/caulker/data/prefs/PrefsStore.kt#L175-L189));
  see `RomRepository.syncPlatform`

**Divergence**: grout uses `*/identifiers` endpoints to detect server-side
deletions (compare server's ID list to local cache, remove the diff). Caulker
doesn't reconcile deletions — orphaned ROM rows can linger in the DB if the
server deletes them.

---

## 12. Configuration & overrides

**Grout** — `internal/config.go` (CFW config file, plus user JSON for overrides)
- `SaveDirectoryMappings` — per-fsSlug override of the save dir
- `ResolveFSSlug(fsSlug)` — lets the user map RomM slugs to local CFW slugs
- `GetSlotPreference(romID)` — per-ROM preferred save slot
- `SaveBackupLimit` — max kept backups before pruning

**Caulker** — [PrefsStore.kt](../app/src/main/java/com/theycallmeboxy/caulker/data/prefs/PrefsStore.kt)
- `PlatformOverride` with `mode` (DEFAULT / SLUG_OVERRIDE / MANUAL),
  per-platform `romPath`, `savePath`, `biosPath` — same idea, more flexible
- `saveSyncSlotPref(romId)` — per-ROM slot preference (matches grout)
- `visibleSaveSlots(romId)` — Caulker-only; lets users surface multiple slots
  in the UI
- `saveSyncEnrolled` — Caulker-only; users opt in per-ROM (grout auto-syncs
  everything)
- No explicit backup-limit setting; `RootFileHelper.MAX_BACKUPS = 5`
  hard-coded

**Divergence**: Caulker treats save sync as opt-in per ROM and exposes a
multi-slot UI. Grout syncs everything it can scan locally and exposes slot
preferences as a config knob.

---

## 13. Filesystem access

This is the biggest practical divergence:

**Grout** is a CFW binary — it reads/writes files with plain `os.ReadFile` /
`os.WriteFile`, owns the filesystem, no permissions to worry about.

**Caulker** is an Android app — saves can live anywhere the user pointed it
at, often `/storage/emulated/0/...` which requires `MANAGE_EXTERNAL_STORAGE`
permission. For paths Android won't grant access to, Caulker falls back to a
root shell via libsu — see [RootFileHelper.kt](../app/src/main/java/com/theycallmeboxy/caulker/data/util/RootFileHelper.kt).

Every IO call in Caulker has two implementations (direct + root); grout has
one. When porting grout logic, expect to add this branching in Caulker.

---

## 14. Things grout has that Caulker doesn't (and probably should)

Rough priority order for things worth porting:

1. **Directory-based saves (PSP)** — zip/unzip pipeline + game-ID matching
2. **`DiscoverRemoteSaves`** — proactive "your ROM has a save on the server,
   want to download?" flow

Recently shipped:
- Concurrent per-ROM save fetches in the sync-all view (see §9e)
- Platform name disambiguation in `PlatformRepository.sync`
- `SaveConflictException` for structured 409 handling on uploads
- `optimistic=true` default on save downloads (matches grout)
- **TLS skip-verify** — `TlsConfig` singleton + dynamic OkHttp `X509TrustManager`
  and `HostnameVerifier` ([AppModule.kt](../app/src/main/java/com/theycallmeboxy/caulker/di/AppModule.kt));
  user toggle on [LoginScreen.kt](../app/src/main/java/com/theycallmeboxy/caulker/ui/screens/login/LoginScreen.kt);
  changes take effect on the next connection (no app restart)
- **Identifiers + deletion reconciliation** — `*/identifiers` endpoints wired
  up; `PlatformRepository.sync` and `RomRepository.syncPlatform` drop rows
  whose IDs vanished server-side. CollectionRepository DAO ready for when
  collections sync is implemented.
- **ROM hashes (CRC/MD5/SHA1)** — added to `RomEntity` + `RomResponse` via
  Room migration v3→v4; `/api/roms/by-hash` endpoint registered
- **Multi-file ROM support** — `hasMultipleFiles` + `filesJson` on RomEntity;
  `downloadRom(rom)` iterates `files[]`, downloads each, writes an `.m3u`
  playlist; `localFile`/`getInstalledRomIds`/`deleteLocalRom` all use the m3u
  as the primary local path for multi-file ROMs (matches grout's
  `Rom.GetLocalPath` / `IsDownloaded`)

## 15. Things Caulker has that grout doesn't

- **Per-platform path overrides UI** — grout has a config file; Caulker has a
  Compose settings screen
- **Multi-slot visibility toggles** — Caulker shows users multiple slots side
  by side; grout has one preferred slot per ROM
- **Opt-in per-ROM enrollment** — grout syncs everything; Caulker only syncs
  ROMs the user explicitly enrolled
- **Backup history UI** — `BackupInfo` count + latest timestamp surfaced in
  the save-sync screen
