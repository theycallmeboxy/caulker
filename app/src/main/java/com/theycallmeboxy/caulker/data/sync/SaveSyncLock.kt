package com.theycallmeboxy.caulker.data.sync

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

// App-wide mutual exclusion for anything that mutates a local save file or
// uploads/downloads one against the server. SaveSyncOrchestrator (bulk "sync
// all") and any per-ROM action (SaveSyncViewModel download/upload, "revert
// all") share the same local files and server saves, but act independently.
// Without coordination, the orchestrator can execute a negotiated op that was
// computed from local state captured *before* a manual action changed that
// same file, silently overwriting the newer save.
//
// Usage: the orchestrator holds this for its entire negotiate→execute→close
// run via `mutex.withLock { ... }`. Per-ROM/manual paths use `mutex.tryLock()`
// so they fail fast with a clear message instead of blocking behind a bulk
// sync that could take a while, then `mutex.unlock()` in a finally block.
@Singleton
class SaveSyncLock @Inject constructor() {
    val mutex = Mutex()
}
