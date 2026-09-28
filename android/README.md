# BitBoard for Android

Native Android port of BitBoard — P2P image replication between devices over
the BitTorrent protocol. **Interoperates with the desktop (Electron) app**:
boards created on either platform sync to the other automatically.

## Architecture

| Desktop (Electron) | Android |
|---|---|
| WebTorrent (Node.js) | libtorrent4j 2.1.0-39 (prebuilt natives, no NDK needed) |
| `dgram` UDP multicast | `MulticastSocket` + WifiManager multicast lock |
| `data/` in app folder | `filesDir/data/` (private app storage) |
| Electron renderer UI | RecyclerView + Material dialogs |

The wire protocol is identical on both platforms:

- **Board folder**: `sha1(name.trim().toLowerCase())` hex, first 16 chars.
- **Manifest**: `bitboard-manifest.json` (visible, not dot-prefixed) with
  `{board, createdAt, files:[{name, size, mtime}]}`.
- **Torrent name**: `bitboard-<boardName>`, trackers: opentrackr + open.tracker.cl.
- **LAN beacon**: JSON `{app:"bitboard", peerId, host, boards:[{name, infoHash}]}`
  on `239.255.66.66:45666` every 3 s. The beacon carries the **real
  content-derived infohash** — that is how joiners find the swarm.

## Project layout

```
android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts            # libtorrent4j deps, arm64 + x86_64
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml     # INTERNET, multicast, foreground service
        ├── java/com/bitboard/app/
        │   ├── App.kt              # singleton engine holder
        │   ├── MainActivity.kt     # board list, dialogs, image picker
        │   ├── BoardAdapter.kt     # RecyclerView adapter
        │   └── engine/
        │       ├── Protocol.kt     # shared constants + hashing (desktop parity)
        │       ├── BitBoardEngine.kt  # torrent session, boards, persistence
        │       ├── LanDiscovery.kt # UDP multicast beacon (desktop wire format)
        │       └── BitBoardService.kt # foreground service (keeps sync alive)
        └── res/                    # layouts, themes, strings, adaptive icon
```

## Build & run

1. Open the `android/` folder in **Android Studio** (it will sync Gradle
   automatically; the wrapper properties are already set to Gradle 8.9,
   AGP 8.5.2, Kotlin 2.0.20).
2. Run on a device or emulator (arm64-v8a and x86_64 natives are bundled).

## Usage

Same as desktop: **+** creates a board, **Add image** picks an image from the
gallery, **Join board…** joins by exact name. Boards are also auto-discovered
on the same Wi-Fi network via the LAN beacon.

A foreground notification keeps seeding/syncing alive while the app is
backgrounded.

## Notes

- WebTorrent's WebRTC trackers are not used on Android (libtorrent speaks
  BitTorrent over TCP/UDP); sync works via DHT, UDP trackers, LSD and the LAN
  beacon — which covers LAN and most internet scenarios.
- The desktop engine (`src/engine.js`) was updated so its LAN beacon handler
  passes the real infohash to `joinBoard()` — required for cross-platform
  sync, since the deterministic name-hash never equals a seeded torrent's
  content-derived infohash.
