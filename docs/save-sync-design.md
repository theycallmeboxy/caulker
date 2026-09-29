# Save Sync Design

This is the design of record for Caulker's save sync. Part 1 documents what's shipped
today (byte-for-byte compatible behavior that must not regress). Part 2 is the
approved v1 design for per-platform folders/presets, multi-file/folder save shapes, and
game matching — none of it shipped yet; see the phased plan (§9) for build order.

---

## Part 1 — Current behavior (shipped)

### RomM save filename behavior

RomM appends a `[YYYY-MM-DD_HH-MM-SS]` timestamp to every save filename when storing it
server-side. A file uploaded as `game.srm` is stored and returned as `[2026-05-13_00-19-56].srm`
(RomM strips the original base name in some versions — the exact server `file_name` in API
responses may be just `[timestamp].ext` without any game name prefix).

Each new upload (from any device) creates a new `file_name` on the server. The previous
`file_name` is superseded — `getSaves()` returns the latest per-slot.

### Local filename resolution

`SaveRepository.resolveLocalSaveFileName(serverFileName, romFileName, platformFsSlug)`:
returns the local filename to use for a save. Preference order:
1. **Stable name** `$romBase.$ext` if it exists on disk.
2. **Legacy timestamped name** `$romBase [*].$ext` (newest by mtime) — migration fallback
   for users who downloaded saves before the stable-filename fix landed.
3. **Stable name as a target** (file doesn't exist yet but is where downloads will go).
4. If no `serverFileName` is provided (synthesized slot, no server saves), scans the save
   dir for any file starting with `$romBase.` or `$romBase [` and returns the newest match.

This filename is used for all local operations: `hasLocalSave` / `localSaveModifiedMs`
(check/read the resolved path), `downloadSave` (writes to the stable path, backing up the
existing file first), `uploadSaveFromDisk` (reads from the resolved path, uploads with that
filename, and sets local mtime to the **server's** `updatedAt` to avoid sync drift from
client/server clock skew).

### Timestamp sync (mtime)

Caulker sets the local file's mtime to the server's `updatedAt` after download and after
upload. `determineSyncAction` (`data/sync/SyncAction.kt`) uses this, alongside content
hashes, to decide the sync direction — see "Sync engine" below.

### Backup files

`RootFileHelper.backupFile` writes to `.caulker_backup/` in the same directory as the save,
named `{baseName}_{yyyyMMdd_HHmmss}.{ext}`. Max 5 backups kept per file. These are separate
from RomM's server-side timestamped filenames.

### Sync engine (RomM 5.3.1's `/api/sync/negotiate`)

Caulker sends the client's local save state (content hash, size, mtime, slot) for its
enrolled ROMs to `POST /api/sync/negotiate` and executes the returned per-slot
upload/download/conflict operations (`SaveSyncOrchestrator.kt`). Pairing is on
`(rom_id, slot)`; content hash is the source of truth for "is this the same save," not
filename or mtime. `determineSyncAction` (used by the per-ROM UI path) implements the same
3-way-merge logic locally against a client-persisted baseline hash
(`SyncBaseline` in `PrefsStore`) — see its doc comment for the full decision tree. A
`SaveSyncLock` mutex (`data/sync/SaveSyncLock.kt`) serializes the orchestrator's bulk
negotiate→execute→close run against any manual per-ROM action, so nothing races a file
underneath a sync.

### Effective save directory

`SaveRepository.effectiveSaveDir(platformFsSlug)` resolves per platform: a per-platform
`PlatformOverride.savePath` if set, else `saveBasePath + "/" + effectiveSlug` (the override's
`slug` under `SLUG_OVERRIDE` mode, else the platform's own `fsSlug`). This base-path +
per-platform-override model is what Part 2 below builds on, not replaces.

### Key files

| File | Role |
|------|------|
| `ui/screens/savesync/SaveSyncViewModel.kt` | Per-ROM sync UI logic, slot state |
| `data/sync/SaveSyncOrchestrator.kt` | Bulk "sync all enrolled" driver (negotiate → execute → close) |
| `data/sync/SyncAction.kt` | `determineSyncAction` — client-side 3-way merge by content hash |
| `data/sync/SaveSyncLock.kt` | App-wide mutex serializing bulk sync against manual per-ROM actions |
| `data/repository/SaveRepository.kt` | Download/upload/local file ops, `effectiveSaveDir`, filename resolution |
| `data/prefs/PrefsStore.kt` | `PlatformOverride`, `SyncBaseline`, enrollment, slot prefs |
| `data/util/RootFileHelper.kt` | Root-aware file I/O + backup |

See [grout-comparison.md](grout-comparison.md) for how this compares to Caulker's sibling
CFW client.

---

## Part 2 — v1 design: per-platform folders, save shapes, matching

### 1. Design principles

- **One model for every system.** Per platform, the user picks a folder (system file
  picker) plus an emulator preset. Two modes:
  - **Direct** — the folder *is* the emulator's real save directory. Caulker reads/writes
    it directly.
  - **Exchange** (semi-manual) — the folder is an arbitrary exchange directory the user
    chooses. Caulker syncs it exactly like Direct (same sync engine, same conflict
    detection, same lock). The user is responsible for moving saves between the exchange
    folder and the emulator, using the emulator's own import/export feature. Exchange
    exists for emulators whose real save storage Caulker can't reach at all (see "Why
    Direct isn't always possible" below) but that do offer some import/export path. The UI
    surfaces a notice on Exchange-mode systems when a newly downloaded save needs
    importing into the emulator.
- **Caulker is a sync tool, not a launcher.** It never edits emulator settings — users
  configure their own emulators. Presets are editable convenience defaults, not
  requirements. Setup is lightweight warnings ("folder not readable", "no saves found"),
  not a wizard. v1 adds no root-tier expansion, no fork dependencies, no
  symlink/`Android/data` workarounds, and no DocumentsProvider/SAF grants — the existing
  root fallback (`RootFileHelper`) stays exactly as-is, unchanged and not expanded.
- **Why Direct isn't always possible.** Since Android 11, no app — Caulker included, even
  with "All files access" — can read another app's `Android/data/<package>/files/...`
  folder; this is a filesystem restriction, not a grantable permission. An emulator whose
  saves only live there is unreachable in Direct mode unless it offers its own setting to
  relocate saves to shared storage (in which case that's the Direct folder to point at) or
  a manual export/import path (Exchange). Caulker's existing opt-in root mode remains the
  only way to reach an app's `Android/data` folder directly, and is unchanged by this
  design.

### 2. Save shapes

Three shapes, declared per preset:

- **SINGLE_FILE** — one file per game (e.g. RetroArch's `.srm`).
- **FILE_SET** — a fixed list of relative path patterns per game, each containing a
  `{name}` token substituted with the resolved base name. Examples: `{name}.srm` +
  `{name}.rtc` (RTC-equipped SNES/GB carts); `{name}.srm` + `{name}.smpc` (Beetle Saturn's
  RTC file); `nvram/{name}.nv` + `hi/{name}.hi` (arcade NVRAM + hiscore, stretch
  goal). A FILE_SET's members are matched and transferred together as one save unit.
- **FOLDER** — a directory per game (e.g. PSP `SAVEDATA/<id>`, GameCube GCI-folder cards
  addressed by matched `.gci` files inside, Dreamcast per-game VMU files, PS2
  folder-memcard subfolders).

FILE_SET and FOLDER upload as a **zip**, in the same format other RomM clients already use
for folder/multi-file saves, so a save zipped by Caulker is readable by them and vice
versa, and Caulker can read zips they produced:

- Standard DEFLATE zip (Java's built-in zip writer produces a compatible file).
- **FILE_SET archives are flat**: every member is stored under its bare filename, with no
  directory prefix, even when the members live in different subfolders on disk (e.g. MAME
  2003-Plus `nvram/{name}.nv` + `hi/{name}.hi` zip as `{name}.nv` + `{name}.hi`). This is
  what Argosy writes (`SaveArchiver.zipFiles`). On unpack, each member is placed at the
  preset pattern whose resolved basename matches it; a member matching no pattern is
  skipped and reported (another client's bundle may carry files the local core doesn't
  use); if nothing is placed, the unpack is refused. Two patterns resolving to the same
  basename is a preset error, rejected at both pack and unpack.
- **FOLDER archives, single root** (one folder save): the top-level directory name is the
  save's folder name; it has no explicit directory zip entry of its own — it exists only
  as the path prefix on every child entry. Nested subdirectories below it do get explicit
  entries, as Argosy's recursive folder zip writes them.
- **FOLDER archives, multi-root** (a folder save bundled with sibling matched folders —
  e.g. PSP's prefix-matched siblings): each root **does** get its own explicit top-level
  directory entry, written before its contents.
- **No manifest file** — no sidecar JSON/text entry, ever. An empty result (zero files
  written) aborts the operation rather than uploading an empty or root-only zip.
- **Content hash**: RomM stores a zip's `content_hash` as the md5 of its sorted
  `name:md5-of-entry` lines, not of the zip bytes (`hash_zip_contents`; Argosy's
  `calculateZipHash` is identical). Caulker computes the same value for a local
  FILE_SET/FOLDER save directly from the files on disk, using the entry names it would
  pack, so zipped saves compare correctly in the sync decision without packing first.
  `tools/romm_zip_hash_kat.py` reproduces the known-answer test values.
- On download of a FOLDER archive, before unpacking, Caulker verifies the archive's root
  name(s) against the expected save id, in tiers: **exact match → prefix match →
  contains match**; no match at any tier refuses the unpack rather than guessing. Every
  root of a multi-root archive must match (stricter than Argosy, which accepts any one
  matching root, because Caulker's multi-root archives are always one save's sibling
  folders). Comparison is case- and punctuation-sensitive, unlike Argosy's normalized
  compare, since Caulker's save id can be an arbitrary ROM stem; revisit with phase 4
  preset data. This is the same safety property applied to save *content* that §5 applies
  to save *location*: never write into the wrong game's save on ambiguous evidence.
- Path-traversal and size guards: reject the whole archive if any entry's extracted path
  would escape the target directory, or if it exceeds 20,000 entries or 512 MiB
  decompressed (RomM's own per-entry cap is also 512 MiB).

### 3. Matching files to games

Per preset, in this order:

1. **ROM filename stem** — the existing convention (`resolveLocalSaveFileName`'s
   `romBase`), used for SINGLE_FILE and most FILE_SET presets.
2. **Game ID / RomM `save_target`**, applied per its `save_target_layout` — used for
   FOLDER and prefix-style FILE_SET presets where the on-disk save isn't named after the
   ROM file at all (PSP disc IDs, PS2 region-prefixed serials, GameCube maker+game-code
   pairs baked into `.gci` filenames). Layouts: `file-exact`, `file-prefix`,
   `folder-exact`, `folder-prefix`, `folder-split` (the last for saves split across more
   than one on-disk location for a single game, e.g. a dual-tree layout — not exercised by
   any v1 system, reserved for later platforms).
3. **Unassigned files** — anything in the configured folder that doesn't match any
   enrolled ROM by either rule above is listed in an "Unassigned files" UI, where the user
   assigns it to a game once; that assignment is remembered (same durability tier as a
   `PlatformOverride`) so it isn't re-prompted.

### 4. RomM data: `title_id`, `save_target`, `save_target_layout`

RomM 5.3+'s `GET /api/roms` list schema includes `title_id`, `save_target`, and
`save_target_layout`, server-computed via RomM's own scan pipeline (not client-supplied).
v1 adds these to `RomResponse`/`RomEntity` (Room migration; DB is currently at version 5)
and plumbs them through matching (§3, rule 2). Older servers omit these fields entirely —
they arrive as `null` and Caulker falls back to ROM-stem matching / the Unassigned list, so
nothing regresses against a pre-5.3 server.

### 5. Safety: baseline + path

The existing per-(rom, slot) content-hash `SyncBaseline` (`data/sync/SyncAction.kt`,
persisted in `PrefsStore`) gains a **resolved local path** field alongside its hash. A
baseline whose recorded path doesn't match the currently-resolved local path means "no
history for this path" — treated the same as no baseline at all, which forces a conflict
prompt when local and remote content differ, never a silent overwrite. This is what stops
a preset/folder change (or first-time Unassigned-file assignment) from quietly clobbering
an unrelated file that happens to already sit at the newly-resolved path. Baselines
persisted before this field existed have no path recorded and are treated as matching
(no forced re-prompt on upgrade) — this is the one exception to "no path = no history,"
scoped specifically to the pre-v1 → v1 migration, and legacy-platform-only: a pathless
baseline on a platform that has a v1 config counts as no history, since configuring a
platform is itself the location change this section guards against.

Everything that writes a save file — Direct or Exchange, any shape — goes through the
existing `SaveSyncLock` mutex, exactly as today.

### 6. Backward compatibility

A user who configures nothing keeps today's exact behavior: base save folder +
`PlatformOverride` + stable/legacy-timestamp filename resolution + `.caulker_backup`
(Part 1, unchanged). Per-platform folder/preset configuration is additive and opt-in; a
platform with no preset configured falls back to the existing `effectiveSaveDir` +
`resolveLocalSaveFileName` path exactly as it does today.

### 7. Upload `emulator` field

**Decision: follow the convention RomM's own web player and Argosy (the RomM-ecosystem
Android launcher) already use, instead of a Caulker-specific constant.** A standalone
emulator uploads its own app id (`duckstation`, `ppsspp`, `drastic`, ...); a RetroArch save
uploads the **libretro core slug**, not `"retroarch"` (`snes9x`, `mupen64plus_next`, ...).
Where RomM's web player and Argosy disagree on a string, Argosy wins, because Argosy's
sync logic actually reads the tag and the web player's doesn't: Argosy's conflict
auto-resolver looks up its local three-way-merge anchor by (game, emulator, slot)
(argosy-launcher `ConflictAutoResolver.kt`). If Caulker was the last client to write a
slot and used Argosy's string, Argosy finds its anchor; with a different string it misses
it and falls back to a cruder hash/timestamp comparison. The web player only uses the tag
to filter save *states* by core and to group saves in its list. Argosy also covers the
same ground as Caulker (Android, standalone emulators plus every RetroArch core), while
the web player supports only the subset of cores playable in a browser. Rationale for the
convention as a whole: it's what the rest of the RomM ecosystem already sends, so matching
it minimizes friction — v2's UI groups saves by this field (below), and a value only
Caulker recognizes fights that grouping. The server itself never uses the tag for
pairing, pruning or dedup (verified facts below).

**Verified facts about the field, unchanged by this decision:**

- **Not a pairing/matching key.** Pairing (`/api/sync/negotiate`), slot pruning/autocleanup,
  and content-hash dedup are all keyed on `(user_id, rom_id, slot)` only — `emulator` never
  participates. Devices are tracked separately by `device_id`.
- **Storage path + UI grouping only.** The value becomes a subfolder segment in the
  server's save path (`.../saves/{platform}/{rom_id}/{emulator}/`) and the group-by label
  in RomM v2's save/state list — nothing else about the path or filename depends on it.
- **The web player filters save *states* by core, not saves.** A state whose `emulator`
  doesn't match the currently-loaded core is hidden as incompatible; ordinary saves are
  never filtered this way — any save loads regardless of its tag. States are out of scope
  for v1 (§8) but this is why the convention exists in the first place.

**Id table.** Caulker preset → the id it uploads. "Source" is where the id comes from:
**Argosy** (confirmed in its source), **RomM web** (confirmed in the EmulatorJS player's
core list — a subset of Argosy's, since the web player's cores are a fixed per-platform
allowlist for in-browser playback rather than an open set), or **PROPOSED** (no existing
client sends this id yet; follows the same naming convention — app id for a standalone
emulator, libretro core slug for a RetroArch core — clearly marked as our own choice pending
verification against a real client or upstream libretro core name).

*Standalone emulators:*

| Caulker preset | Emulator id | Source |
|---|---|---|
| DuckStation | `duckstation` | Argosy |
| PPSSPP | `ppsspp` | Argosy (also RomM web, as the PPSSPP RetroArch core id — same string) |
| DraStic | `drastic` | Argosy |
| melonDS (standalone) | `melonds` | Argosy (same id as the legacy melonDS RetroArch core — Argosy doesn't distinguish "standalone app" from "libretro core of the same name," which is harmless since both produce the same raw save payload) |
| watermelonDS | `melondualds` | Argosy (its package was formerly "melonDS-android," which is the id Argosy's registry still reflects) |
| Flycast (standalone) | `flycast` | Argosy (same id as the Flycast RetroArch core — again harmless, same emulator) |
| ARMSX2 (GitHub build) | `armsx2_refresh` | Argosy — **not** `armsx2`, which Argosy reserves for the Play Store build (the one Caulker can't reach at all; see §9) |
| NetherSX2 | `nethersx2` | Argosy |
| DolphinCS | `dolphin_cs` | Argosy |
| Other Dolphin builds (Exchange) | `dolphin` | PROPOSED — Argosy has specific ids for Dolphin, Dolphin (Handheld), and DolphinCS but no single id for "some other Dolphin build"; `dolphin` is Argosy's plain-Dolphin id and its family base id for auto-detecting any Dolphin variant |
| Mupen64Plus AE | `mupen64plus_fz` | Argosy — "Mupen64Plus AE" is the old name for this same app; Argosy's registry uses its current name |

*RetroArch cores (the id sent is the core slug, per the decision above):*

| System | Core | Emulator id | Source |
|---|---|---|---|
| NES | FCEUmm | `fceumm` | Argosy + RomM web |
| NES | Nestopia UE | `nestopia` | Argosy + RomM web (Argosy's id drops "UE") |
| NES | Mesen | `mesen` | Argosy only — no `mesen` in RomM web's core list for NES |
| SNES | Snes9x | `snes9x` | Argosy + RomM web |
| SNES | Snes9x 2010 | `snes9x2010` | Argosy only |
| SNES | bsnes | `bsnes` | Argosy + RomM web (web player only offers it with netplay enabled) |
| GB/GBC | Gambatte | `gambatte` | Argosy + RomM web |
| GB/GBC | SameBoy | `sameboy` | Argosy only |
| GB/GBC | Gearboy | `gearboy` | Argosy only |
| GBA | mGBA | `mgba` | Argosy + RomM web |
| GBA | gpSP | `gpsp` | Argosy only |
| GBA | VBA-M | `vbam` | Argosy only |
| Genesis/MD, MS, GG, 32X, SG-1000 | Genesis Plus GX (or Wide) | `genesis_plus_gx` | Argosy + RomM web — Argosy has no separate id for the Wide build; both send the same string (see disagreement note below) |
| Genesis/MD, MS, GG, 32X, SG-1000 | PicoDrive | `picodrive` | Argosy + RomM web |
| Sega CD / Mega CD | Genesis Plus GX (or Wide) | `genesis_plus_gx` | Argosy + RomM web |
| Sega CD / Mega CD | PicoDrive | `picodrive` | Argosy + RomM web |
| N64 | Mupen64Plus-Next | `mupen64plus_next` | Argosy + RomM web — Argosy aliases both GLES2 and GLES3 build variants to this one id |
| N64 | ParaLLEl N64 | `parallel_n64` | Argosy + RomM web |
| PS1 | Beetle PSX | `mednafen_psx` | Argosy only — RomM web only offers the HW variant |
| PS1 | Beetle PSX HW | `mednafen_psx_hw` | Argosy + RomM web |
| PS1 | SwanStation | `swanstation` | Argosy only |
| PS1 | PCSX ReARMed | `pcsx_rearmed` | Argosy + RomM web |
| Saturn | Beetle Saturn | `mednafen_saturn` | Argosy only — RomM web's Saturn core list is just Yabause |
| Saturn | Yabause | `yabause` | Argosy + RomM web |
| Dreamcast | Flycast (RetroArch core) | `flycast` | Argosy only — RomM web has no Dreamcast core at all |
| PC Engine / PCE-CD | Beetle PCE | `mednafen_pce` | Argosy + RomM web |
| PC Engine / PCE-CD | Beetle PCE Fast | `mednafen_pce_fast` | Argosy only |
| Nintendo DS | melonDS DS | `melondsds` | Argosy only |
| Nintendo DS | melonDS (legacy core) | `melonds` | Argosy + RomM web |
| Nintendo DS | DeSmuME | `desmume` | Argosy + RomM web |
| Nintendo DS | DeSmuME 2015 | `desmume2015` | Argosy + RomM web |
| PSP | PPSSPP (core) | `ppsspp` | Argosy + RomM web |
| Arcade | MAME 2003-Plus | `mame2003_plus` | Argosy + RomM web |
| Arcade | FBNeo | `fbneo` | Argosy + RomM web |

*Not synced by Caulker, ids noted for completeness:* YabaSanshiro/Yaba Sanshiro 2 both use
Argosy's `yabasanshiro`. Kronos and bsnes-mercury have no id in any known client;
PROPOSED `kronos` and `bsnes_mercury` respectively, following the libretro-core-slug
convention, moot since neither ships in v1.

**Variant-distinguishing notes:**

- **Beetle PSX HW vs. Beetle PSX (non-HW)**: distinct ids (`mednafen_psx_hw` /
  `mednafen_psx`), no alias between them — unlike Mupen64Plus-Next's GLES2/GLES3 collapse.
- **Genesis Plus GX vs. "Wide"**: this is the one real Argosy-vs-RomM disagreement found,
  not just a coverage gap — RomM web's netplay-only core list *does* carry a distinct
  `genesis_plus_gx_wide` id for several Sega 8/16-bit platforms, but Argosy has no separate
  id for the Wide build at all. Per Argosy-wins-on-disagreement: Caulker always sends
  `genesis_plus_gx`, regardless of which build the user has configured.
- Every other case where RomM web has no equivalent (Mesen, SameBoy, Gearboy, gpSP, VBA-M,
  Snes9x 2010, non-netplay bsnes, Beetle PSX non-HW, SwanStation, Beetle Saturn, Beetle PCE
  Fast, melonDS DS, all of Dreamcast) is a coverage gap in the web player's fixed core
  allowlist, not a naming disagreement — the id is still correct, it just means that
  particular save can't be replayed in-browser today.

**Migration.** Existing rows tagged `"caulker"` keep pairing exactly as before — pairing,
pruning, and dedup are keyed on `(user_id, rom_id, slot)`, never on `emulator` (see verified
facts above), so switching what a device uploads doesn't start a new lineage. What does
change: the *next* upload to a slot writes a new server-side row (uploads are always
timestamp-tagged) tagged with the new id, landing in a new `.../{new_id}/` storage
subfolder. The old `"caulker"`-tagged files physically stay in the server's `caulker/`
subfolder — there is no server-side rewrite of old rows' `emulator` value or file
location — until normal slot retention (`MAX_SAVES_PER_SLOT`/autocleanup) prunes them out
of that slot's history.

**Client-side download guard (planned).** Before overwriting a local save, if the
incoming save's `emulator` is a known id whose save format is known-incompatible with the
device's currently-configured core for that platform (the interchangeable/non-interchangeable
families from §10 — e.g. DeSmuME vs. melonDS/melonDS DS, Beetle Saturn vs. Yabause), Caulker
warns and requires confirmation instead of silently overwriting. An unknown id, a null tag,
or `"caulker"` behaves exactly as today (no warning) — the guard only fires when the
incoming tag positively identifies a format Caulker knows is incompatible with what's
configured locally. **Limit**: this only protects devices running Caulker with the new
tagging in place. Every other RomM client ignores this field entirely (per the verified
facts above), so a save downloaded from — or overwritten by — a non-Caulker client gets no
such warning.

### 8. Out of scope

Save **states** are out of scope entirely for v1 — saves only.

### 9. v1 systems (6th-generation cutoff)

v1 covers classic consoles through the sixth generation (PS2/GameCube/Dreamcast-era).
Every entry below ships only after on-device verification confirms the real save location
against a live install (§10, phase 5) — nothing in this table is shipped yet; see
[supported-emulators.md](supported-emulators.md) for the user-facing status (currently all
"Planned").

| System | Preset(s) | Mode | Save shape | Notes |
|---|---|---|---|---|
| Classic consoles (NES, SNES, GB/GBC/GBA, Genesis/Mega Drive, etc.) | RetroArch | Direct | SINGLE_FILE (mostly); FILE_SET for a few cores (see §10) | Preset is (system + core), not per system alone (§10); per-core file sets, required option changes, and status are in §10.x |
| Sega CD / Mega CD | RetroArch (Genesis Plus GX / Wide, or PicoDrive core) | Direct | FILE_SET (Genesis Plus GX: per-game `.brm`, needs its CD System BRAM option set to Per-Game) / SINGLE_FILE (PicoDrive: `.srm`, already per-game) | Genesis Plus GX's CD System BRAM defaults to one shared file per BIOS region, not per game, until changed; PicoDrive's Sega CD save is not interchangeable with Genesis Plus GX's `.brm` — see §10.x |
| PC Engine / PCE-CD | RetroArch (Beetle PCE or Beetle PCE Fast core) | Direct | SINGLE_FILE (`.srm`) | No option changes needed; the two cores' `.srm` is an interchangeable family (identical layout + magic header) — see §10.x |
| PS1 | DuckStation (community shared-storage patch) or RetroArch (Beetle PSX/HW, SwanStation, or PCSX ReARMed core) | Direct | SINGLE_FILE / FILE_SET | DuckStation needs the patch that relocates its data folder to shared storage — plain DuckStation is unreachable (see §11); the three RetroArch cores' 128 KiB memory-card `.srm` is an interchangeable family — see §10.x |
| N64 | RetroArch (Mupen64Plus-Next or ParaLLEl N64 core) | Direct | SINGLE_FILE (one `.srm`, a fixed 296,960 B struct covering EEPROM/Controller Pak/SRAM/FlashRAM) | Byte-identical `.srm` between the two cores — see §10.x; ParaLLEl N64 needs Player 1 Pak set to Memory for games that use the Controller Pak |
| N64 | Mupen64Plus AE | Exchange | FILE_SET | Its saves are app-internal storage; export/import via its own flow; its `.eep`/`.sra`/`.fla`/`.mpk` split is this app's own layout, unrelated to the RetroArch cores' single `.srm` above |
| PSP | PPSSPP | Direct | FOLDER | Save is a folder per game under `SAVEDATA/`, `save_target_layout = folder-prefix` |
| Nintendo DS | DraStic / watermelonDS / melonDS | Direct | SINGLE_FILE | |
| Dreamcast | Flycast (standalone) | Direct | SINGLE_FILE (per-game VMU) | Per-game VMU A1 is Flycast's default; user moves its home directory to shared storage |
| Dreamcast | Flycast (standalone) | Exchange | SINGLE_FILE | Fallback if the home-directory move isn't viable on a given install |
| Dreamcast | RetroArch (Flycast core) | Direct | SINGLE_FILE (per-game VMU A1 only) | Needs Per-Game VMUs set to VMU A1 (default shares up to 8 VMU files in RetroArch's system dir); per-game filename is the disc's sanitized game ID, not the ROM stem — matching-key open item (§11); see §10.x |
| PS2 | ARMSX2 (GitHub build) / NetherSX2 | Direct | FOLDER (folder memory card) | Folder Memory Card mode must be enabled in-emulator; `save_target_layout = folder-prefix` (region-prefixed serial, e.g. `BASLUS-xxxxx`); ARMSX2's Play Store build can't use shared storage at all — GitHub build only |
| GameCube | DolphinCS (shared-storage mode) | Direct | FOLDER (`.gci` file set per game code) | |
| GameCube | Other Dolphin builds | Exchange | FOLDER | |
| Saturn | RetroArch (Beetle Saturn or Yabause core) | Direct | FILE_SET (Beetle Saturn default: `.srm` + `.smpc`, plus a `.bcr`-style cart-NV file only when a cart with NV memory is active; Yabause: single `.srm`, no RTC file) | Beetle Saturn and Yabause save formats are not interchangeable (different image sizes) — pick one core per device; see §10.x |
| Arcade (MAME 2003-Plus, FBNeo) | RetroArch | Direct | FILE_SET (`nvram`/`hi`/`memcard` only, never `cfg/`) | Stretch goal — ships only if it's pure preset data; FBNeo's exact on-disk layout is an open item (§11); see §10.x |

**Wii**: only if RomM's `save_target` for a Wii title matches Dolphin's NAND title folder
naming (`Wii/title/00010000/<id>`) closely enough to use `folder-exact` directly —
otherwise deferred. Open item, §11.

**Saturn — Yaba Sanshiro 2 not supported in v1**: its Saturn backup RAM is a single shared
image across every game (no per-game save unit), so it can't be synced per game without a
console-filesystem-level extractor Caulker doesn't have in v1 — use the RetroArch cores
above for Saturn instead.

**Not supported (any release)**: Redream — its four VMU files are shared across every game
on the install with no per-game option at all, same "single shared image" problem as Yaba
Sanshiro. More generally, any emulator whose saves exist only as one shared file across
every game (not one save per game) isn't something Caulker can sync per game, regardless of
whether the emulator itself is otherwise reachable.

**Later (not v1)**: Wii (unless the `save_target` match above turns out trivial), 3DS,
Vita, Switch.

### 10. RetroArch expectations

RetroArch is the fallback preset for systems without a good standalone emulator, and the
only preset for most classic consoles. v1 assumes:

- **"Sort Saves into Folders by Content Directory" ON** and **"Sort Saves into Folders by
  Core Name" OFF** (Core Name is ON by default in stock RetroArch — the user needs to
  change it). With that combination, RetroArch's save path is
  `saves/<ROM's parent folder name>/<rom>.srm`. If both options are left on, RetroArch
  nests an extra `<core name>/` level under the content-directory folder — Caulker's
  RetroArch preset targets the by-content-only shape, not the by-content-and-core shape.
- RetroArch must have its own "All files access" granted so its saves directory can live
  in shared storage at all — otherwise it defaults to its own `Android/data` folder,
  unreachable to Caulker.
- **A RetroArch preset is (system + core), not per
  system with a union of extensions.** The user picks which RetroArch core they use for
  each system; the core determines the file set/extensions and any matching key, not the
  system alone. Rationale: extensions and file sets differ per core (`.srm` vs `.sav` vs
  `.dsv`; Saturn `.srm` + `.smpc`; arcade subfolders), and a preset that unions every
  core's extensions risks syncing a stale file left behind by a *different* core than the
  one the user currently has configured for that system. Saves are also not always
  interchangeable across cores for the same system: DeSmuME's `.dsv` carries a proprietary
  footer that melonDS/melonDS DS's raw saves don't, and Beetle Saturn vs. Yabause backup-RAM
  formats are unverified and differ in size (32,768 B vs 65,536 B) — treat as
  non-interchangeable until proven otherwise. Where cores are confirmed interchangeable:
  PS1 128 KiB memory cards across Beetle PSX/HW, SwanStation, and PCSX ReARMed's `.srm`;
  Mupen64Plus-Next and ParaLLEl N64's `.srm` (byte-identical struct); Beetle PCE and Beetle
  PCE Fast's `.srm` (identical layout and magic header). See §10.x for the resulting
  per-core table.
- Save names come from the loaded ROM's filename (the *inner* filename, for archives —
  RetroArch names the save after the archive member, not the archive itself).
- RetroArch autosaves SRAM to disk every 10 seconds on Android by default. Users should
  sync with the game closed, not mid-session, to avoid syncing a save that's about to be
  overwritten by the next autosave tick.
- Per-game/per-core override configs can redirect a specific game's save location outside
  the normal sort-folder convention — if a user has set one up, matching it is the user's
  responsibility; Caulker doesn't detect or follow overrides.
- Most same-system cores share the plain `.srm` convention, but not all — see §10.x for
  the per-core file set, required option changes, matching key, and interop notes for
  every RetroArch core in scope for v1.

### 10.x. Per-core save behavior

Per the (system + core) preset model above. Files
are relative to the per-content save folder (`GET_SAVE_DIRECTORY` under Caulker's assumed
"sort by content directory ON / by core name OFF" config), unless noted otherwise.
`{name}` = the matching key (ROM stem, disc game ID, or PSP GameID folder). "Status" marks
whether the core is in scope for v1 (Supported) or excluded (Not supported).

| System | Core | Files per game | Save shape | Required core-option change (default → required) | Matching key | Notes | Status |
|---|---|---|---|---|---|---|---|
| NES | FCEUmm | `{name}.srm` (raw cart SRAM; FDS carts: the entire modified FDS disk image, still as `.srm`) | SINGLE_FILE | None | ROM stem | Cart `.srm` is a plain SRAM dump, likely interoperable with other NES cores' raw SRAM. FDS `.srm` is a full disk image — **not** interchangeable with Nestopia's `.sav` (UPS) or Mesen's `.ips`. | Supported |
| NES | Nestopia UE | Cart: `{name}.srm`. FDS: `{name}.sav` (UPS patch, core-written) by default | SINGLE_FILE | None (FDS format defaults to `.sav`; can be changed to `.ups` or `.ips` for cross-core compat) | ROM stem | FDS `.sav`/`.ups`/`.ips` are mutually exclusive with cart `.srm` per game. Setting FDS format to `.ips` is explicitly designed to interop with Mesen's FDS saves. | Supported |
| NES | Mesen | Cart: `{name}.srm`. FDS: `{name}.ips` (core-written, hardcoded). Special mappers (rare): extra `{name}.sav.chr`/`.tf`/`.bb`/`.eeprom128`/`.eeprom256` | SINGLE_FILE (FILE_SET only for the rare special-mapper cases) | None | ROM stem | FDS `.ips` is designed to interop with Nestopia's `ips` FDS mode. | Supported |
| SNES | Snes9x | `{name}.srm` + `{name}.rtc` (SRTC/SPC7110 carts only) | SINGLE_FILE (FILE_SET for RTC carts) | None | ROM stem | Structurally identical to Snes9x 2010 (same field names/sizes). | Supported |
| SNES | Snes9x 2010 | Same as Snes9x | SINGLE_FILE (FILE_SET for RTC carts) | None | ROM stem | Structurally identical to Snes9x. | Supported |
| SNES | bsnes | `{name}.srm` + `{name}.rtc` (RTC carts), core-written. BS Memory (Satellaview) flash writes are never persisted at all | SINGLE_FILE (FILE_SET for RTC carts) | None | ROM stem | **bsnes only writes its save on game unload (close), never on a periodic autosave** — a sync that runs while the game is merely paused/backgrounded, not closed, can read a stale save. Close the game before syncing. | Supported |
| GB/GBC | Gambatte | `{name}.srm` + `{name}.rtc` (MBC3 RTC carts) | SINGLE_FILE (FILE_SET for RTC carts) | None | ROM stem | RTC byte format not verified against other GB cores. | Supported |
| GB/GBC | SameBoy | `{name}.srm` + `{name}.rtc`, single-device mode (the default) | SINGLE_FILE (FILE_SET for RTC carts) | None | ROM stem | Dual-device link-cable mode (non-default, needs special multi-ROM content) may not persist the second cart's save — out of scope. | Supported |
| GB/GBC | Gearboy | `{name}.srm` + `{name}.rtc` | SINGLE_FILE (FILE_SET for RTC carts) | None | ROM stem | RTC byte format not verified against other GB cores. | Supported |
| GBA | mGBA | `{name}.srm`, sized to the detected save type | SINGLE_FILE | None | ROM stem | GBA cart RTC (e.g. Pokémon R/S/E, Boktai) is not exposed/persisted by this core at all — inherent core limitation, not a Caulker gap. | Supported |
| GBA | gpSP | `{name}.srm`, **always padded to 128 KiB** regardless of real save type | SINGLE_FILE | None | ROM stem | No RTC. EEPROM byte-offset-within-buffer not verified for cross-core interop. | Supported |
| GBA | VBA-M | `{name}.srm`, correctly sized (no padding); GB/GBC mode: `{name}.srm` + `{name}.rtc` | SINGLE_FILE | None | ROM stem | No GBA RTC (same gap as mGBA/gpSP). | Supported |
| Genesis/MD, MS, GG, SG-1000, 32X | Genesis Plus GX (+ Wide) | `{name}.srm` | SINGLE_FILE | None | ROM stem | Header-parsed SRAM, likely interoperable with PicoDrive's cart save (not byte-diffed). | Supported |
| Genesis/MD, MS, GG, SG-1000, 32X | PicoDrive | `{name}.srm` | SINGLE_FILE | None | ROM stem | Same cart-save family as Genesis Plus GX (not byte-diffed). | Supported |
| Sega CD / Mega CD | Genesis Plus GX (+ Wide) | `{name}.brm` (internal BRAM); optional `{name}_<size>Kbit_cart.brm` if a backup RAM cart is enabled | FILE_SET (when the cart-BRAM option is also on) | **CD System BRAM: Per-BIOS → Per-Game** (option `genesis_plus_gx_system_bram`); optional **CD Backup Cart BRAM: Per-Cart → Per-Game** (`genesis_plus_gx_cart_bram`) if using a RAM cart | ROM stem | Shared files never synced: default `scd_E.brm`/`scd_U.brm`/`scd_J.brm` (one per BIOS region) and `<size>Kbit_cart.brm` — both only go away once the option above is changed. | Supported |
| Sega CD / Mega CD | PicoDrive | `{name}.srm` (BRAM routed through the same frontend save-RAM slot as cart saves) | SINGLE_FILE | None (already per-game) | ROM stem | **Not interoperable with Genesis Plus GX's `.brm`** — different filename, different format, even when both are set per-game. | Supported |
| N64 | Mupen64Plus-Next | One `{name}.srm`, fixed 296,960 B struct (EEPROM + 4× Controller Pak + SRAM + FlashRAM) | SINGLE_FILE | None | ROM stem | Byte-identical struct to ParaLLEl N64 — interchangeable family (not runtime-tested). Optional N64 Transfer Pak (GB passthrough, non-default subsystem load) writes a separate file next to the GB ROM — out of scope for v1. | Supported |
| N64 | ParaLLEl N64 | Same `{name}.srm` struct as Mupen64Plus-Next | SINGLE_FILE | **Player 1 Pak: None → Memory** (option `parallel-n64-pak1`) for games that use the Controller Pak | ROM stem | Byte-identical to Mupen64Plus-Next for non-64DD content — interchangeable family. Transfer Pak is unimplemented (dead code) in this core. | Supported |
| PS1 | Beetle PSX / Beetle PSX HW | `{name}.srm`, raw 131,072 B (128 KiB) memory-card image, memory card slot 0 | SINGLE_FILE | None (Memory Card Slot 2 defaults **disabled** — no shared second-card file by default) | ROM/playlist stem | Interchangeable family with SwanStation and PCSX ReARMed (same 128 KiB raw format); core comment confirms `.srm` ↔ `.mcr` byte-equivalence within this core. | Supported |
| PS1 | SwanStation | `{name}.srm`, 131,072 B, Card 1 = Libretro / Card 2 = None (both default) | SINGLE_FILE | None | ROM/playlist stem | Interchangeable family with Beetle PSX and PCSX ReARMed. Loader rejects any file that isn't exactly 131,072 B. | Supported |
| PS1 | PCSX ReARMed | Slot 1: `{name}.srm`, 131,072 B | SINGLE_FILE (once fixed) | **Memory Card 2 Type: Shared Between All Games → No Memory Card** (option `pcsx_rearmed_memcard2`) — otherwise a shared `pcsx-card2.mcd` is created for every game | ROM/playlist stem | Interchangeable family with Beetle PSX and SwanStation. | Supported |
| Saturn | Beetle Saturn | Default: `{name}.srm` (32,768 B raw Backup RAM) + `{name}.smpc` (RTC, core-written, always) + a `.bcr`-style cart-NV file only if the active cart type has NV memory (default cart = Auto Detect) | FILE_SET | None by default (both "Shared Internal Memory" and "Shared Backup Memory" options default disabled/per-game) | ROM stem | Legacy Mednafen mode (`.bkr`) holds the same 32,768 B image — a one-time rename migrates it. `.smpc`/cart-NV are core-written directly into the save dir, bypassing the frontend's own path construction (still lands in the per-content folder via `GET_SAVE_DIRECTORY`). | Supported |
| Saturn | Yabause | One core-written `{name}.srm` (despite the extension, not via the frontend SAVE_RAM path), 65,536 B raw internal backup-RAM image | SINGLE_FILE | None (unconditionally per-game) | ROM stem | **Not byte-interchangeable with Beetle Saturn** — confirmed size mismatch (65,536 B vs. 32,768 B). No RTC file. | Supported |
| Dreamcast | Flycast (core) | Default: up to 8 files shared across every game in RetroArch's *system* dir. With Per-Game VMUs = VMU A1: `<disc game ID>.A1.bin` per game (sanitized disc game ID, not the ROM stem) | SINGLE_FILE (VMU A1 only, once fixed) | **Per-Game VMUs: disabled → VMU A1** (option `flycast_per_content_vmus`) | Disc game ID (how Caulker learns it is an open item, §11) | Ports B1/C1/D1 and `dc_nvmem.bin` stay shared across all games even after the fix — never sync them. Arcade-board (Naomi/AtomisWave) saves are always core-written and per-game, unaffected by this option. | Supported |
| PC Engine / PCE-CD | Beetle PCE | `{name}.srm` (2,048 B SaveRAM, or 32,768 B for the Populous HuCard's cart RAM) | SINGLE_FILE | None | ROM stem | Byte-identical layout and magic header to Beetle PCE Fast — interchangeable family. | Supported |
| PC Engine / PCE-CD | Beetle PCE Fast | Same as Beetle PCE | SINGLE_FILE | None | ROM stem | Byte-identical layout and magic header to Beetle PCE — interchangeable family. | Supported |
| Nintendo DS | melonDS DS | Retail carts: `{name}.srm` (frontend-delegated). DSiWare: + `{name}.public.sav` + `{name}.private.sav` (+ `{name}.banner.sav` if an animated banner) | SINGLE_FILE (FILE_SET for DSiWare) | None (already per-game by default) | ROM stem | Shared files never synced: DSi virtual SD card (`dsi_sd_card.bin`) and homebrew virtual SD card (`dldi_sd_card.bin`), both single files shared across all DSi/homebrew titles (both default enabled). `.srm` should interop with legacy melonDS's `.sav` (same raw save-chip dump). | Supported |
| Nintendo DS | melonDS (legacy) | Core-written `{name}.sav`, raw cart SRAM, no footer | SINGLE_FILE | None | ROM stem | Raw payload should match melonDS DS's `.srm`. Optional dual-slot GBA cart save (non-default) writes a separate file — out of scope. | Supported |
| Nintendo DS | DeSmuME / DeSmuME 2015 | Core-written `{name}.dsv` (raw save bytes + ~124 B proprietary footer) | SINGLE_FILE | None (no shared firmware file by default — the gating options for that are both disabled by default) | ROM stem | **Not byte-identical to melonDS/melonDS DS's raw saves** — the footer must be stripped first; do not treat as interchangeable without that step. Auto-imports a legacy raw `.sav` if no `.dsv` exists. | Supported |
| PSP | PPSSPP (core) | Folder per save, `PSP/SAVEDATA/<GameID><SaveName>/` (GameID = 9-char PSP disc ID from the disc's own PARAM.SFO, not the RetroArch content filename) | FOLDER | None (no save-related option exists) | PSP GameID folder (`save_target_layout = folder-prefix`, same as the standalone PPSSPP preset in §9) | Shared, never-synced: `PSP/SYSTEM/`, `PSP/NAND/flash0/` (simulated firmware), `PSP/GAME/`, `PSP/TEXTURES/`, `PSP/PLUGINS/`, `PSP/Cheats/`, `PSP/PPSSPP_STATE/` all live under the same `PSP/` tree as every game's save folder. SAVEDATA structure is identical to standalone PPSSPP (same code, no libretro-specific branching). | Supported |
| Arcade | FBNeo & MAME 2003-Plus | See §9 (stretch goal, same table) | FILE_SET | None | ROM/shortname stem | `.ini`/`cfg`/`ctrlr`/`diff` never synced (config, not save data); `memcard/MEMCARD.NNN.mem` (MAME2003+ Neo Geo) and `ngp.nvram`/`ngpc.nvram` (FBNeo Neo Geo Pocket) are shared by fixed slot/filename, not per game — exclude from per-game sync. | Supported (stretch) |
| Saturn | YabaSanshiro (core) | One file shared by every Saturn game: `yabasanshiro/backup.bin`, fixed literal filename | — | No option exists to make this per-game | — | Hard architectural limitation — every game shares one internal-memory image (as on real Saturn hardware), unlike the other three Saturn cores' per-ROM virtualization. | **Not supported** — single shared save, no per-game option |
| Saturn | Kronos | Two per-game files by default under a core-managed subfolder | FILE_SET | — | ROM stem | — | **Not supported** — no official Android build exists |
| SNES | bsnes-mercury | `{name}.srm` for plain cart SRAM | SINGLE_FILE | — | ROM stem | `RETRO_MEMORY_RTC` is defined but always returns null/size 0 — **cart RTC is never persisted**, so any RTC-equipped cart (e.g. SNES SRTC games) silently loses clock state every session. Super Game Boy / Sufami Turbo / BS-X PRAM persistence path also unverified. | **Not recommended** — RTC not persisted; use bsnes, Snes9x, or Snes9x 2010 instead |

### 11. Open items

- **FBNeo's exact on-disk save layout** — the arcade stretch goal needs the precise
  `nvram`/`hi`/`memcard` file naming/layout confirmed against a live install before it can
  ship; not resolved by this design.
- **Wii `save_target` match** — whether RomM's computed `save_target` for Wii titles lines
  up with Dolphin's `Wii/title/00010000/<id>` NAND folder closely enough for a direct
  `folder-exact` preset, or needs a translation step. Undetermined; Wii stays "later"
  until this is checked.
- **Which emulators have a usable import/export path for Exchange mode** — Exchange
  presumes the target emulator has *some* manual way to move saves in/out (confirmed to
  exist for Mupen64Plus AE's own import/export flow); for other Exchange candidates listed
  in §9 (other Dolphin builds, Flycast's Exchange fallback) the existence and shape of a
  comparable flow hasn't been confirmed and needs checking per emulator before its Exchange
  mode ships.
- **DuckStation's community patch vs. upstream** — the patch that relocates DuckStation's
  data folder to shared storage is a third-party APK patch, not an upstream feature;
  presets built against it need to be re-verified if the patch or DuckStation's own storage
  handling changes.
- **ARMSX2 GitHub vs. Play Store builds** — only the GitHub build can use shared storage at
  all (the Play Store build has no escape hatch from `Android/data`); the preset/setup UI
  needs to make this distinction clear rather than presenting one "ARMSX2" preset that only
  half-works depending on which build the user installed.
- **Dreamcast game-ID matching (largely resolved, needs on-device confirmation)** — both
  Flycast builds name the per-game VMU after the disc's IP.BIN product number (e.g.
  `T-8111N`, sanitized), not the ROM filename: the RetroArch core writes
  `<game ID>.A1.bin` (with Per-Game VMUs = VMU A1), standalone Flycast writes
  `<game ID>_vmu_save_A1.bin` (its `PerGameVmu` option, default on). RomM 5.3.1 runs
  argosy-sigil at scan time (pinned to the same commit reviewed here) and maps Dreamcast
  to sigil's `dreamcast` extractor, whose `title_id` is exactly that product number,
  reproducing Flycast's trim/truncate rules, with `save_target_layout = file-prefix`. So
  Caulker matches Dreamcast by `title_id` with a per-preset filename suffix — no disc
  parsing on-device. Caveats: sigil marks Dreamcast experimental, and it only extracts
  from `.chd`, `.iso` or the data-track `.bin`; `.gdi`/`.cdi` libraries will usually have
  no `title_id`, so those games fall back to the Unassigned-files UI (§3). The two builds'
  filenames differ, so moving a VMU between the core and standalone Flycast is a rename
  (content interchangeability unverified). Standalone Flycast also has a user-set VMU
  folder option (`VMUPath`) — worth evaluating as a simpler Direct-mode setup than moving
  its whole home directory.
- **Cross-core save conversion is out of scope for v1.** Per §10, some cores within a
  system are confirmed interchangeable and some are confirmed (or presumed) not to be; v1
  does not attempt to convert a save from one core's format to another's. Users should use
  the same core (or a confirmed-interchangeable one) for a given system on every device
  they sync between — switching cores mid-series risks a save the new core can't read.
- **Runtime verification of the cross-core interop claims in §10.x.** Every
  "interchangeable family" and "not interchangeable" claim in §10.x is a structural
  finding from reading core source (matching sizes, struct layouts, or explicit
  compatibility comments in the code) — none of it was confirmed by actually loading a
  real save across cores on a device. This needs to happen before Caulker's UI presents
  cross-core interop as a supported user workflow (as opposed to an FYI in this doc).

### 12. Phased implementation plan

1. **Data model + resolver.** Save shapes (`SINGLE_FILE`/`FILE_SET`/`FOLDER`), pattern
   matching, game-matching rules (§3), `save_target`/`save_target_layout` plumbing + Room
   migration, baseline path field (§5). Data model accounts for a RetroArch preset being
   keyed on (system, core) (§10) not system alone. Each preset
   carries its `emulator` id per §7's table, and upload sends it. The download guard's
   decision logic (§7) — known-incompatible-format check against the device's configured
   core — is pure data-driven logic and lands here too, gated behind a flag until phase 3
   wires up its UI. JVM unit tests, no Android dependencies needed for this layer.
2. **Zip pack/unpack** compatible with other RomM clients' format (§2) + tests, including
   the archive-root verification tiers and the path-traversal guard.
3. **Per-platform folder/preset settings UI** — D-pad friendly (square screens in mind),
   plus setup warnings (folder not readable / no saves found), the Unassigned-files UI,
   Exchange-mode "needs importing" notices, and the download guard's confirmation prompt
   (§7) wired up to the phase 1 decision logic.
4. **Presets data** — the actual per-emulator preset table from §9/§10, entered once the
   resolver/UI plumbing from phases 1–3 exists to consume it. RetroArch's per-core presets
   come from the §10.x table (system + core, not system alone).
5. **On-device verification per emulator** — each preset is checked against a real install
   before its status in [supported-emulators.md](supported-emulators.md) moves off
   "Planned." This is a hard gate: nothing in §9's table ships on the strength of source
   research alone.

### 13. Test plan

- **Resolver (phase 1)**: JVM unit tests per save shape — pattern expansion with `{name}`,
  each `save_target_layout` value's matching behavior, Unassigned-file fallback when no
  rule matches, baseline-path mismatch forcing a conflict rather than a silent overwrite,
  and the pre-v1-baseline (no path recorded) migration exception.
- **Emulator id mapping (phase 1)**: JVM unit tests asserting every preset in §7's table
  uploads its documented id, including the RetroArch (system, core) → core-slug mapping and
  the Mupen64Plus-Next GLES2/GLES3 alias.
- **Download guard decision table (phase 1)**: JVM unit tests over the guard's decision
  logic (§7) — warn-and-confirm for a known-incompatible pair (e.g. DeSmuME save incoming
  onto a melonDS-configured device, Beetle Saturn onto Yabause), silent overwrite for a
  known-interchangeable pair, and silent overwrite (today's behavior, unchanged) for an
  unknown id, a null tag, or `"caulker"`.
- **Zip (phase 2)**: flat FILE_SET (including subfolder placement and skipped extras) and
  single-/multi-root FOLDER pack/unpack round-trips; content hash known-answer tests
  against RomM's algorithm and pack-then-hash == hash-directly; root-verification
  tiers (exact/prefix/contains/no-match refusal) against synthetic archives, including
  archives shaped like what other RomM clients produce, to confirm read-compatibility;
  path-traversal rejection; empty-result abort.
- **Backward compatibility**: existing Part 1 behavior (stable filename resolution, legacy
  timestamped fallback, `.caulker_backup`) stays covered by its current tests unchanged —
  v1 must not regress a user who configures no per-platform preset.
- **On-device (phase 5)**: manual verification per emulator preset against a real install,
  per the phase 5 gate above — not automatable, tracked as part of moving each system off
  "Planned" in the user-facing doc.
