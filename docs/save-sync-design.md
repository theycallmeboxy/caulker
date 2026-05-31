# Save Sync Design Notes

## RomM save filename behavior

RomM appends a `[YYYY-MM-DD_HH-MM-SS]` timestamp to every save filename when storing it
server-side. A file uploaded as `game.srm` is stored and returned as `[2026-05-13_00-19-56].srm`
(RomM strips the original base name in some versions — the exact server `file_name` in API
responses may be just `[timestamp].ext` without any game name prefix).

Each new upload (from any device) creates a new `file_name` on the server. The previous
`file_name` is superseded — `getSaves()` returns the latest per-slot.

## Grout's approach (reference implementation)

`grout/sync/flow.go` — `SyncItem` download path:

```go
savePath := item.LocalSave.FilePath          // use existing local path if present
if savePath == "" {
    fileName := item.RemoteSave.FileName     // server's timestamped name as fallback
    if item.LocalSave.RomFileName != "" {
        romNameNoExt := strings.TrimSuffix(item.LocalSave.RomFileName,
            filepath.Ext(item.LocalSave.RomFileName))
        fileName = romNameNoExt + "." + item.RemoteSave.FileExtension  // stable name
    }
    savePath = filepath.Join(saveDir, fileName)
}
```

Key points:
- Prefers the existing local file path (never moves files once established)
- Falls back to `romBase.ext` derived from the ROM's own filename — NOT the server's timestamped name
- After download, sets file **mtime** to `RemoteSave.UpdatedAt` (not filename)

`grout/romm/saves.go` — upload uses `filepath.Base(savePath)` as the multipart filename,
so it uploads the stable local name back to RomM.

## Caulker's approach (after fix)

`SaveRepository.resolveLocalSaveFileName(serverFileName, romFileName, platformFsSlug)`:
Returns the local filename to use for a save. Preference order:
1. **Stable name** `$romBase.$ext` if it exists on disk.
2. **Legacy timestamped name** `$romBase [*].$ext` (newest by mtime) — migration fallback
   for users who downloaded saves before the stable-filename fix landed.
3. **Stable name as a target** (file doesn't exist yet but is where downloads will go).
4. If no `serverFileName` is provided (synthesized slot, no server saves), scans the save
   dir for any file starting with `$romBase.` or `$romBase [` and returns the newest match.

This filename is stored in `SlotUiState.fileName` and used for all local operations:
- `hasLocalSave` / `localSaveModifiedMs` — check/read the resolved path
- `downloadSave` — writes to the stable path (backs up existing first via `RootFileHelper.backupFile`)
- `uploadSaveFromDisk` — reads from the resolved path, uploads with that filename, and
  sets local mtime to the **server's** `updatedAt` (not `System.currentTimeMillis()`) to
  avoid sync drift from client/server clock skew.

## Timestamp sync (mtime)

Both grout and Caulker set the local file's mtime to the server's `updatedAt` after download.
This is what the sync comparison (`determineSyncAction`) uses — it compares `localModifiedMs`
(from `File.lastModified()` or `stat -c %Y`) against `remoteUpdatedAt` parsed to epoch ms.

## Backup files

`RootFileHelper.backupFile` writes to `.caulker_backup/` in the same directory as the save,
named `{baseName}_{yyyyMMdd_HHmmss}.{ext}`. Max 5 backups kept per file. These are separate
from RomM's server-side timestamped filenames.

## Key files

| File | Role |
|------|------|
| `ui/screens/savesync/SaveSyncViewModel.kt` | All sync logic, slot state, stable filename derivation |
| `data/repository/SaveRepository.kt` | Download/upload/local file ops |
| `data/util/RootFileHelper.kt` | Root-aware file I/O + backup |
| `data/api/model/ApiModels.kt` — `SaveResponse` | `file_name` = server timestamped name |
| `data/db/entity/Entities.kt` — `RomEntity` | `fileName` = stable ROM filename (e.g., `game.gbc`) |
| `grout/sync/flow.go` | Reference: stable filename derivation |
| `grout/romm/saves.go` | Reference: upload uses `filepath.Base(savePath)` |
