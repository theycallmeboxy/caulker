# Supported Emulators

> **This page describes planned support.** Caulker's save sync doesn't ship emulator
> support yet — every status below is "Planned," updated as each preset is implemented
> and verified against a real install.

Caulker is a sync tool, not a launcher. It never installs, configures, or changes settings
in any emulator. You pick and set up your own emulator for each platform, tell Caulker
which folder its saves live in (per platform, in Settings), and Caulker syncs whatever it
finds there against your RomM server.

## Direct vs. Exchange folders

For each platform you configure two things: a folder (picked with the system file picker)
and an emulator preset. There are two kinds of folder:

- **Direct** — the folder you point Caulker at *is* the emulator's real save folder.
  Caulker reads and writes saves there directly, same as any other sync.
- **Exchange** — for an emulator whose real save storage Caulker can't reach at all (see
  "Android storage and root" below), but that has its own export/import feature. You pick
  any folder you like as an exchange point, and you move saves between it and the emulator
  yourself, using the emulator's own export/import. Caulker syncs the exchange folder
  exactly like a Direct folder — same conflict detection, same everything — it just
  doesn't know about the emulator's internal storage at all. When a newly downloaded save
  lands in an Exchange folder, Caulker flags it as needing to be imported into the
  emulator.

Most systems below use Direct. Where a system is Exchange-only or offers Exchange as a
fallback, it's called out in the table and setup notes.

A save can be a single file, several files per game, or a whole folder. Folder (and
multi-file) saves are zipped for upload and unzipped on download, using the same
convention other RomM clients use, so a save zipped by Caulker should be readable by them
and vice versa. Some emulators keep one shared memory-card or VMU file holding many games'
saves instead of one save per game — those can't be synced per game and are called out in
"Not supported" below.

**Sync with the game closed.** Most emulators write saves to disk periodically while a
game is running (RetroArch autosaves SRAM every 10 seconds on Android by default), so a
sync that runs mid-session can catch a save that's about to be overwritten again a moment
later. Close the game first, then sync.

## Status legend

| Status | Meaning |
|---|---|
| **Planned** | Targeted for Caulker's first release of save sync for this platform. |
| **Planned (later)** | Targeted for a later release, after the first batch of platforms ships. |

## Supported platforms

| System | Emulator | Mode | Status | Where saves must be | Setup needed |
|---|---|---|---|---|---|
| Classic consoles (NES, SNES, Game Boy / Color / Advance, Genesis/Mega Drive, etc.) | RetroArch | Direct | Planned | Shared storage — depends on install, verify Savefile Directory | Yes — see RetroArch section |
| Sega CD / Mega CD | RetroArch (Genesis Plus GX / Wide or PicoDrive core) | Direct | Planned | Shared storage — depends on install, verify Savefile Directory | Yes — see RetroArch section |
| PC Engine / PCE-CD | RetroArch (Beetle PCE or Beetle PCE Fast core) | Direct | Planned | Shared storage — depends on install, verify Savefile Directory | Yes — see RetroArch section |
| PlayStation | DuckStation (with the shared-storage patch) | Direct | Planned | Shared storage, after applying the patch | Yes — see setup notes |
| PlayStation | RetroArch (Beetle PSX / HW, SwanStation, or PCSX ReARMed core) | Direct | Planned | Shared storage — depends on install, verify Savefile Directory | Yes — see RetroArch section |
| Nintendo 64 | RetroArch (Mupen64Plus-Next or ParaLLEl N64 core) | Direct | Planned | Shared storage — depends on install, verify Savefile Directory | Yes — see RetroArch section |
| Nintendo 64 | Mupen64Plus AE | Exchange | Planned | Any folder you pick — saves live in the app's own storage otherwise | Yes — use Mupen64Plus AE's own export/import |
| PSP | PPSSPP | Direct | Planned | Shared storage (memory stick folder you picked on first run) | No |
| Nintendo DS | DraStic | Direct | Planned | Shared storage (`DraStic/backup`, default) | No |
| Nintendo DS | melonDS | Direct | Planned | Shared storage (next to your ROMs, default) | No |
| Nintendo DS | watermelonDS | Direct | Planned | Shared storage (next to your ROMs, default, once a ROM has been opened once) | No |
| Dreamcast | Flycast (standalone) | Direct | Planned | Shared storage, after two setting changes | Yes — see setup notes |
| Dreamcast | Flycast (standalone) | Exchange | Planned | Any folder you pick, if moving Flycast's whole data folder isn't practical | Yes |
| Dreamcast | RetroArch (Flycast core) | Direct | Planned | Shared storage — depends on install, verify Savefile Directory | Yes — see RetroArch section |
| PlayStation 2 | ARMSX2 (GitHub build) | Direct | Planned | Shared storage, after storage + memory-card setup | Yes — see setup notes |
| PlayStation 2 | NetherSX2 | Direct | Planned | Shared storage, after enabling Folder Memory Card mode | Yes — see setup notes |
| GameCube | DolphinCS | Direct | Planned | Shared storage, in DolphinCS's shared-storage mode | Yes — see setup notes |
| GameCube | Other Dolphin builds | Exchange | Planned | Any folder you pick | Yes |
| Saturn | RetroArch (Beetle Saturn or Yabause core) | Direct | Planned | Shared storage — depends on install, verify Savefile Directory | Yes — see RetroArch section |
| Arcade (MAME 2003-Plus, FBNeo) | RetroArch | Direct | Planned (later) | Shared storage — depends on install, verify Savefile Directory | Yes — see RetroArch section |

Wii, Nintendo 3DS, PS Vita, and Switch are not in the first batch — see "Not supported (yet)"
below.

## Android storage and root

Starting with Android 11, no app — not even one with "All files access" — can read another
app's `Android/data/<package>/files/...` folder. This is a filesystem-level restriction,
not just a permission you can grant your way around, and it hasn't loosened on any Android
version since. Practically, this means: if an emulator only stores saves inside its own
`Android/data` folder and offers no way to move that out, Caulker can't reach it in Direct
mode.

Caulker doesn't use tricks or workarounds for this. Two real options where Direct isn't
possible:

1. **Move the save (or the whole data folder) somewhere Caulker can reach**, using a
   setting the emulator itself provides — see the per-emulator setup below. Where that
   exists, this is the recommended path (Direct mode).
2. **Exchange mode** — for an emulator with a manual export/import feature but no way to
   relocate its live storage, sync an exchange folder you choose and move saves in/out of
   the emulator yourself using its own export/import.

Caulker also has an opt-in root mode (via a standard superuser prompt, e.g. Magisk or
KernelSU) that can read and write `Android/data` folders directly on a rooted device. This
is unchanged by anything on this page and isn't expanded by the presets described here —
it's a separate, existing fallback.

## Setting up each emulator

### RetroArch

RetroArch is the preset for most classic consoles, and for several other systems where you
pick which core you use for that system. **Which core you pick matters**: the core decides
what save file(s) get written, and — for some systems — you need to change one of the
core's own options before it saves in a way Caulker can sync per game. See "Choose your
core" below for the full breakdown.

- **Settings → Saving → Sort Saves into Folders by Content Directory**: turn this **ON**.
- **Settings → Saving → Sort Saves into Folders by Core Name**: turn this **OFF** (it's ON
  by default in stock RetroArch).
- With that combination, RetroArch's save path is
  `<Savefile Directory>/<ROM's parent folder name>/<rom>.srm`. If you leave Core Name on
  too, RetroArch nests an extra core-name folder underneath — point Caulker at the
  by-content-only shape above.
- **Settings → Directory → Savefile Directory**: check this. Depending on how you
  installed RetroArch (Play Store vs. sideload) it may already point at shared storage, or
  it may default inside RetroArch's own `Android/data` folder. RetroArch needs its own "All
  files access" granted for a shared-storage Savefile Directory to work at all.
- Point Caulker's save folder for that platform at the resulting sorted subfolder.
- Save naming: `.srm` for most cores, one file per game, named from the loaded ROM's
  filename (the inner filename, for archives) — the per-core table below has the exceptions.
- **Use the same core on every device you sync between.** Two cores for the same system
  don't always read each other's saves — the table below flags which ones do and don't.
- Known limitation: a per-game or per-core override config can redirect a specific game's
  save location outside the sort-folder convention above. If you've set one up, that's on
  you to account for — Caulker doesn't detect overrides.

#### Choose your core

| System | Cores | Settings you must change | Caveats |
|---|---|---|---|
| NES | FCEUmm, Nestopia UE, Mesen | None | FDS games: Nestopia's `.sav` and Mesen's `.ips` aren't the same format — pick one core and stick with it for FDS games. |
| SNES | Snes9x, Snes9x 2010, bsnes | None | **bsnes only writes its save when you close the game**, not on the periodic autosave — always fully close the game before syncing a bsnes save. |
| Game Boy / Color | Gambatte, SameBoy, Gearboy | None | |
| Game Boy Advance | mGBA, gpSP, VBA-M | None | **The cartridge's real-time clock isn't saved** by any of these cores (a handful of games use one, e.g. Pokémon Ruby/Sapphire/Emerald) — expect it to reset each session. |
| Genesis / Mega Drive, Master System, Game Gear, 32X | Genesis Plus GX (or Wide), PicoDrive | None | |
| Sega CD / Mega CD | Genesis Plus GX (or Wide) | Core option **CD System BRAM → Per-Game** (default is one save shared by every game from the same BIOS region) | |
| Sega CD / Mega CD | PicoDrive | None (already per-game) | Its Sega CD save isn't known to be interchangeable with Genesis Plus GX's — pick one core and stick with it for Sega CD. |
| N64 | Mupen64Plus-Next, ParaLLEl N64 | ParaLLEl N64 only: core option **Player 1 Pak → Memory** if the game uses the Controller Pak (default is None) | These two write the same save file, so switching between them should work (not yet tested on a device). |
| PS1 | Beetle PSX / Beetle PSX HW, SwanStation, PCSX ReARMed | PCSX ReARMed only: core option **Memory Card 2 Type → No Memory Card** (default is a card shared by every game) | These three write the same 128 KB memory-card format, so switching between them should work (not yet tested on a device). |
| Saturn | Beetle Saturn, Yabause | None | These two aren't known to read each other's saves — pick one and stick with it. |
| Dreamcast | Flycast (RetroArch core) | Core option **Per-Game VMUs → VMU A1** (default shares up to 8 VMU files across every game) | Only port A1 becomes per-game; other VMU ports stay shared and aren't synced. Prefer standalone Flycast (below) if you'd rather this work without a core option change. **Use CHD (or ISO, or a data-track BIN) images** — RomM reads the disc's game ID from those, which Caulker needs to match a save to a game automatically. `.gdi`/`.cdi` games can't be matched automatically and show up in the Unassigned-files list. |
| PC Engine / PCE-CD | Beetle PCE, Beetle PCE Fast | None | These two write the same save format, so switching between them should work (not yet tested on a device). |
| Nintendo DS | melonDS DS, melonDS, DeSmuME / DeSmuME 2015 | None | **DeSmuME's save isn't interchangeable with melonDS/melonDS DS's** — pick one and stick with it. |
| PSP | PPSSPP (core) | None | |
| Arcade (MAME 2003-Plus, FBNeo) | FBNeo, MAME 2003-Plus | None | Planned (later). |

#### Cores not supported

- **YabaSanshiro** (Saturn) — one save file shared by every Saturn game on the install, no
  per-game option. Not to be confused with the standalone Yaba Sanshiro 2 app below, which
  has the same limitation for the same reason.
- **Kronos** (Saturn) — no official Android build exists.
- **bsnes-mercury** (SNES) — not recommended: it never saves a cartridge's real-time clock,
  so games that use one (e.g. SNES SRTC titles) silently lose clock state every session.
  Use bsnes, Snes9x, or Snes9x 2010 instead.

### DuckStation (PlayStation)

- **Setting to apply**: DuckStation's own storage is inside its `Android/data` folder by
  default, which Caulker can't reach. A community-maintained patch relocates DuckStation's
  whole data folder to shared storage (`/storage/emulated/0/DuckStation`) and prompts for
  "All files access" on first launch after patching.
- Point Caulker's PlayStation save folder at the memory card folder under that relocated
  data folder.
- Save naming: one memory card file per game by default (DuckStation's default memory card
  mode is per-game-by-title).
- Known limitation: this depends on a third-party patch, not an upstream DuckStation
  feature — if you're not comfortable applying it, use RetroArch for PS1 instead.

### PPSSPP (PSP)

- No setting to change for sync — PPSSPP already requires you to pick a "Memory Stick
  folder" in shared storage the first time you run it (Settings → System → PSP Memory
  Stick → Memory Stick folder), and that's where saves live.
- Point Caulker's PSP save folder at `<your memory stick folder>/PSP/SAVEDATA`.
- Save naming: a PSP save is a **folder**, not a file — one folder per save slot, named
  from the game's disc ID. Some games create several save folders (e.g. auto-save vs
  manual slots); this is normal and handled automatically.
- Known limitation: PPSSPP also writes non-save folders (like installed game data) under
  the same disc-ID prefix — don't be surprised to see extra folders there that aren't game
  saves.

### DraStic (Nintendo DS)

- No setting to change by default — DraStic saves to a top-level `DraStic/backup` folder in
  shared storage out of the box.
- Point Caulker's Nintendo DS save folder at `/DraStic/backup` (or the matching subfolder if
  you use DraStic's "users" feature for a custom save path).
- Save naming: `.dsv`, one file per game (DraStic's native format).
- Known limitation: a small number of users have reported this folder becoming unreachable
  on Android 12 devices. If your saves aren't showing up, check DraStic's own save location
  under its settings first.

### melonDS / watermelonDS (Nintendo DS)

- No setting to change by default — both save next to the ROM file unless you've turned
  that off in Settings → General.
- Point Caulker's Nintendo DS save folder at wherever your DS ROMs live (default), or at
  the save directory you set if you changed it.
- Save naming: `.sav`, one flat file per game, matched to the ROM's filename.
- Known limitation (watermelonDS specifically): the "next to ROM" location only takes
  effect once the ROM has been opened in watermelonDS's own library at least once. A save
  created before that first open lands in the app's internal storage instead — open the
  game in watermelonDS once before expecting Caulker to see its save.

### Mupen64Plus AE (Nintendo 64) — Exchange

- Mupen64Plus AE's saves live in its own app-internal storage, which Caulker can't reach
  at all (not even with the usual shared-storage workaround). Use its own
  export/import feature to move saves to a folder of your choice, and point Caulker's
  Nintendo 64 Exchange folder at that same folder.
- Save naming: N64 games can use more than one save type — you may see `.eep`, `.sra`,
  and/or `.fla` files per game, all named from the ROM filename.
- For a Direct (no manual export/import) N64 setup instead, use RetroArch's
  Mupen64Plus-Next core (see the RetroArch section above).

### Flycast standalone (Dreamcast)

Two changes are needed, both because Flycast's defaults don't produce a per-game save on
shared storage:

1. **Per-game VMU A1 is on by default** in current Flycast — nothing to change here, but
   worth confirming under Settings → Controls if you're on an older build.
2. **Get the save out of Android/data**: Flycast's data folder defaults to its own
   `Android/data` folder. Use Flycast's "move home directory" option to relocate its whole
   data folder to shared storage. Newer builds also offer a dedicated "VMU Folder" path
   under Settings → General — if you have that option, you only need to redirect the VMU
   folder, not the whole home directory.

- Point Caulker's Dreamcast save folder at wherever the VMU file ends up after the move.
- Save naming: one file per game, named from the game's disc ID (e.g. `MK-51000`), suffixed
  `_vmu_save_A1.bin`.
- **Use CHD (or ISO, or a data-track BIN) images.** Caulker matches a Dreamcast save to a
  game using the disc's game ID, which RomM only reads from those formats. `.gdi`/`.cdi`
  games can't be matched automatically — Caulker will list their saves in the
  Unassigned-files screen instead of matching them on its own.
- Known limitations:
  - Only VMU port A1 becomes per-game. Ports B1/C1/D1 (if a game uses them) stay in shared
    files across every game and aren't synced individually.
  - If you already have progress in shared-VMU mode, turning on Per Game VMU A1 starts each
    game with a fresh save — your old shared-card progress isn't automatically split out
    per game.
  - If relocating Flycast's whole home directory isn't practical on your device, Caulker
    also supports pointing at Flycast in Exchange mode instead — check Flycast's own
    export options for moving individual VMU files.

### ARMSX2 (PlayStation 2, GitHub build only)

- **Only the GitHub-distributed build of ARMSX2 can use shared storage at all.** The Play
  Store build has no path to shared storage and is unreachable to Caulker without root —
  if you installed ARMSX2 from the Play Store, use NetherSX2 instead, or switch to the
  GitHub build.
- **Setting to change**: during ARMSX2's onboarding, choose the SD Card or Custom storage
  option (not the default, Internal).
- **Setting to change**: ARMSX2's memory cards default to a single monolithic file, which
  can't be synced per game. In ARMSX2's memory card manager, create or switch to a
  **Folder**-type card.
- Point Caulker's PlayStation 2 save folder at that folder memory card.
- Save naming: one subfolder per game inside the folder memory card, named from the game's
  region-prefixed serial (e.g. `BASLUS-20152`).
- Known limitation: if a game has no memory card pinned and more than one ambiguous match
  exists, sync can't tell which folder is meant for it — pin a specific memory card per
  game in ARMSX2 if you use more than one.

### NetherSX2 (PlayStation 2)

- **Setting to change**: enable **Folder Memory Card** mode in NetherSX2's settings. Its
  default monolithic memory card can't be synced per game.
- Point Caulker's PlayStation 2 save folder at the resulting folder memory card, under
  NetherSX2's data folder.
- Save naming: one subfolder per game inside the folder memory card, named from the game's
  region-prefixed serial (e.g. `BASLUS-20152`).
- Known limitation: NetherSX2's data folder defaults to `Android/data`; check whether your
  build offers a shared-storage relocation option, otherwise this platform needs root.

### DolphinCS (GameCube)

- **Setting to change**: DolphinCS defaults to Android/data (Scoped) storage. Switch it to
  its shared-storage mode in DolphinCS's storage settings.
- Point Caulker's GameCube save folder at `GC/<region>/Card A` under DolphinCS's relocated
  user folder (region is one of `USA`, `EUR`, `JPN`, `DEV`, matching whatever regions of
  games you've played — a single install can have more than one).
- Save naming: one `.gci` file per save, named `{maker}-{game}-{internal save name}.gci`,
  matched to a game by the `{game}` segment.
- For other Dolphin builds that don't offer a shared-storage mode, use Exchange instead:
  point Caulker at any folder and use that build's own way of getting `.gci` files out.

## Not supported (yet)

Wii, Nintendo 3DS, PS Vita, and Switch aren't in the first batch of platforms. Wii support
depends on whether RomM's computed save identity for a Wii title lines up cleanly with
Dolphin's own save folder naming — undecided as of this writing.

## Not supported (and why)

**Redream (Dreamcast)** — Redream has no per-game save option at all: every game on a
Redream install shares the same four VMU files (`vmu0.bin`–`vmu3.bin`), regardless of
platform. There's nothing for Caulker to sync per game. If you want Dreamcast save sync, use
Flycast standalone (above).

**Yaba Sanshiro 2 (Saturn)** — its backup RAM is a single image shared by every Saturn game
on the install, with no per-game option, the same limitation as Redream above. Use
RetroArch's Beetle Saturn or Yabause core instead, which save per game.

More generally, any emulator whose saves only exist as one shared memory-card or VMU file
across every game — rather than one save per game — isn't something Caulker can sync per
game, even where the emulator itself is otherwise reachable.
