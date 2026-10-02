# BitBoard

P2P image replication between devices

Every **board** is a named folder of images. Each device running BitBoard that
joins a board both **seeds and downloads** it over the **BitTorrent protocol**
(via WebTorrent: DHT + trackers + WebRTC peers), so images replicate
automatically to every device that has the same board.

## How it works

```
Device A                 Device B
┌──────────┐   BitTorrent swarm (DHT + trackers)   ┌──────────┐
│ board:   │ �,�──────────── images ────────────►    │ board:   │
│ Vacation │ �,�──────────── images ────────────►    │ Vacation │
└──────────┘                                       └──────────┘
     └──────────── LAN UDP multicast beacon ────────────┘
        (auto-discovers boards on the same network)
```

- **Boards** are published as torrents; adding an image re-publishes the
  torrent and peers pick up the new file automatically.
- **LAN discovery**: a UDP multicast beacon (`239.255.66.66:45666`) announces
  which boards each device has; devices auto-join boards they are missing.
- **Cross-network**: peers also find each other through public WebTorrent
  trackers and the DHT, so sync works over the internet too.

## Run

```
npm install
npm start
```

## Usage

1. **New board** in the sidebar → give it a name (e.g. `Vacation`).
2. **Add image** → pick an image; it is copied into the board folder and
   published to the swarm.
3. On another device, either wait for the LAN beacon to auto-join the board,
   or use **Join board** and type the exact same name.
4. The main area lists the board's images **by download date** (newest first).

## Data location

`data/boards/<hash>/`, board folders (images live here, safe to back up).
`data/torrents/`, generated `.torrent` files.
`data/state.json`, board registry.