# Caulker

> **Note:** This project was built almost entirely with [Claude Code](https://claude.com/claude-code), Anthropic's agentic coding tool. The architecture, implementation, and iteration were driven through AI pair-programming. Review the code accordingly before relying on it.

An Android client for [RomM](https://github.com/rommapp/romm) — browse your ROM library, download games, and sync save files across devices.

## Features

- **Library** — browse platforms and games cached from your RomM server; filter by installed / not installed
- **Downloads** — download ROMs and BIOS files directly to your device; multi-disc games generate an `.m3u` playlist
- **Save sync** — per-game slot management with conflict detection; sync all enrolled games at once from the save sync screen
- **Quick Settings tile** — sync saves from anywhere without opening the app; foreground notification with per-game progress and a cancel action
- **Collections** — browse user-defined and smart collections from RomM
- **Incremental sync** — library syncs use `updated_after` so only changed records are fetched after the first run
- **Root support** — optional root access via libsu for emulators that store saves in restricted paths
- **TLS skip-verify** — toggle for self-signed homelab certificates

## Requirements

- Android 8.0 (API 26) or higher
- A running [RomM](https://github.com/rommapp/romm) instance
- A pairing code from RomM (Settings → API tokens)

## Building

Requires Android Studio and Java 17.

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle
4. Run on a device or emulator

No additional configuration is needed. A `local.properties` file pointing at your Android SDK is created automatically by Android Studio.

## Setup

1. Launch Caulker and enter your RomM server URL and pairing code
2. Configure your ROM and save folder paths (Settings)
3. Tap **Sync database now** on the dashboard to populate your library
4. Open a game's detail page to enable save sync for it

## Save sync Quick Settings tile

The tile does not install automatically. To enable it:

1. Pull down the Quick Settings shade fully
2. Tap the pencil / edit icon
3. Find **Caulker save sync** in the available tiles
4. Drag it into your active row

Tapping the tile starts a background sync of all enrolled games. Tap again while running to cancel.

## Architecture

- Kotlin + Jetpack Compose + Material3
- MVVM with Hilt dependency injection
- Room for local caching, DataStore for preferences
- Retrofit + Moshi for API communication
- Coil for image loading
- libsu for optional root file access

## Acknowledgements

Caulker's save sync design — including the stable local filename derivation, `DeviceSaveSync` conflict detection logic, and per-device sync tracking — draws heavily from [grout](https://github.com/theycallmeboxy/grout), a RomM sync client for handheld CFW devices.

> grout is MIT licensed. Copyright © 2025 Brandon T. Kowalski, Grout Contributors.

## License

MIT License

Copyright (c) 2025 theycallmeboxy

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
