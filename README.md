# BitBoard

P2P image replication between devices

| Folder   | Client            | Stack                          |
|----------|-------------------|--------------------------------|
| `electron/` | Desktop app    | Electron + Node (WebTorrent)   |
| `android/`  | Mobile app     | Kotlin + Android (jlibtorrent) |

Every **board** is a named folder of images. Each device running BitBoard that
joins a board both **seeds and downloads** it over the **BitTorrent protocol**,
so images replicate automatically to every device that has the same board —
like a cloud folder, but fully peer-to-peer.

## How it works

```
Device A                 Device B
┌──────────┐   BitTorrent swarm (DHT + trackers)   ┌──────────┐
│ board:   │ ◄──────────── images ────────────►    │ board:   │
│ Vacation │ ◄──────────── images ────────────►    │ Vacation │
└──────────┘                                       └──────────┘
     └──────────── LAN UDP multicast beacon ────────────┘
        (auto-discovers boards on the same network)
```

- **Boards** are published as torrents; adding an image re-publishes the
  torrent and peers pick up the new file automatically.
- **LAN discovery**: a UDP multicast beacon (`239.255.66.66:45666`) announces
  which boards each device has; devices auto-join boards they are missing.
- **Cross-network (internet rendezvous)**: every device seeds its *own* torrent
  and its infohash differs from every other device's, so trackers/DHT alone can
  never match two devices. Devices therefore publish "board X = infohash H" to a
  tiny pub/sub relay (ntfy protocol, `https://ntfy.sh` by default; one topic per
  board, derived from a hash of the board name). A peer that learns H fetches it
  through the normal trackers/DHT/PEX and merges the images it lacks. Only
  infohashes go through the relay: never images, IPs or file names. Desktop:
  `electron/src/signal.js`; Android: `SignalChannel.kt`. Set
  `BITBOARD_SIGNAL_URL` (desktop) to use your own ntfy server, or
  `BITBOARD_SIGNAL=off` to disable it (LAN-only).
- **NAT caveat**: two phones that are both on mobile data (carrier-grade NAT)
  usually cannot open a connection to each other at all. At least one side on
  home Wi-Fi with UPnP/NAT-PMP (or a forwarded TCP port 6881) is normally enough.

## Running the desktop app (Electron)

```
npm install        # from the repo root (installs electron/ deps)
npm start
```

Or directly:

```
cd electron
npm install
npm start
```

## Building the Android app

```
cd android
.\gradlew.bat assembleDebug
```

See `android/README.md` for details.

## Usage (both clients)

1. **New board** → give it a name (e.g. `Vacation`).
2. **Add image** → it is copied into the board folder and published to the swarm.
3. On another device, either wait for the LAN beacon to auto-join the board,
   or use **Join board** and type the exact same name.
4. Boards list their images **by download date** (newest first).

## Data location (desktop)

`data/boards/<hash>/` — board folders (images live here, safe to back up).
`data/torrents/` — generated `.torrent` files.
`data/state.json` — board registry.
