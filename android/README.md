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
- **Torrent name**: the board folder's basename, trackers: opentrackr + open.tracker.cl. Torrents are **v1-only** (WebTorrent cannot read hybrid v1+v2).
- **LAN beacon**: JSON `{app:"bitboard", v:2, peerId, host, port, reply, boards:[{name, infoHash}]}`
  on multicast `239.255.66.66:45666` **and** the subnet broadcast address,
  every 3 s, on every network interface. `port` is the sender's BitTorrent TCP
  port; receivers connect to it directly. New peers get a unicast `reply` beacon.
- **Sync = merge, not swarm switching.** Two devices never share an infohash
  for the same board (it covers mtimes, creation date, creator string).
  Each device seeds its own folder; on a beacon with an unseen hash it fetches
  that torrent into a staging folder (only the images it lacks), copies them
  in and republishes. Each peer hash is merged once, so devices converge.
  Same-name/different-content images are both kept (`name-<sha1 prefix>.ext`).

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
