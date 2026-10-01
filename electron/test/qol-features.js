'use strict';
/* Quick smoke test for the new QoL engine features (settings, leaveBoard,
 * icons, blacklists). Uses an isolated data dir. */
const path = require('path');
const fs = require('fs');
const os = require('os');
const { BitBoardEngine } = require('../src/engine');

const DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'bitboard-qol-'));

async function main() {
    const engine = new BitBoardEngine({ dataDir: DATA, signal: false });
    await engine.ready();

    // 1. create + icon
    await engine.createBoard('QoL Test');
    engine.setBoardIcon('QoL Test', '\uD83D\uDE80');
    let snap = engine.getBoardsSnapshot();
    console.assert(snap.length === 1, 'one board');
    console.assert(snap[0].icon === '\uD83D\uDE80', 'icon persisted in snapshot');

    // 2. addImage + imageHash + blacklist by hash
    const img = path.join(DATA, 'test.png');
    fs.writeFileSync(img, Buffer.from([0x89, 0x50, 0x4e, 0x47, 1, 2, 3, 4]));
    await engine.addImage('QoL Test', img);
    const hash = engine.imageHash('QoL Test', 'test.png');
    console.assert(hash && hash.length === 40, 'imageHash returns sha1');
    engine.settings.add('imageHashes', hash);
    console.assert(engine.settings.isImageBlocked(hash), 'image blocked');
    // blocked image must not re-import
    const added = engine._importStaged(engine.boards.get('QoL Test'), [
        { name: 'copy.png', full: img }
    ]);
    console.assert(added === 0, 'blacklisted image not imported, got ' + added);

    // 3. board blacklist hides discovery
    engine.settings.add('boards', 'Evil Board');
    console.assert(engine.settings.isBoardBlocked('Evil Board'), 'board blocked');
    engine._handlePeerBoard('Evil Board', 'a'.repeat(40), '127.0.0.1', 6881, 'peerX');
    console.assert(!engine.getDiscoveredSnapshot().includes('Evil Board'), 'blocked board not discovered');

    // 4. peer blacklist ignores beacons
    engine.settings.add('peers', 'badPeer');
    engine._handlePeerBoard('Nice Board', 'b'.repeat(40), '127.0.0.1', 6881, 'badPeer');
    console.assert(!engine.getDiscoveredSnapshot().includes('Nice Board'), 'blocked peer boards ignored');

    // 5. leaveBoard
    const dir = engine.boardDir('QoL Test');
    const ok = await engine.leaveBoard('QoL Test', false);
    console.assert(ok, 'leaveBoard ok');
    console.assert(engine.getBoardsSnapshot().length === 0, 'board gone from snapshot');
    console.assert(fs.existsSync(dir), 'files kept when deleteFiles=false');
    const state = JSON.parse(fs.readFileSync(path.join(DATA, 'state.json'), 'utf8'));
    console.assert(!state.boards['QoL Test'], 'board removed from state.json');
    console.assert(state.icons && state.icons['QoL Test'] === '\uD83D\uDE80', 'icon kept for rejoin');

    // 6. leaveBoard with deleteFiles
    await engine.createBoard('Wipe Me');
    await engine.leaveBoard('Wipe Me', true);
    console.assert(!fs.existsSync(engine.boardDir('Wipe Me')), 'files deleted');

    // 7. settings persistence
    engine.settings.set('showLog', false);
    const engine2 = new BitBoardEngine({ dataDir: DATA, signal: false });
    await engine2.ready();
    console.assert(engine2.settings.get().showLog === false, 'settings persisted across instances');

    engine.destroy();
    engine2.destroy();
    console.log('ALL QOL ENGINE TESTS PASSED');
}

main().catch((e) => { console.error('TEST FAILED:', e); process.exit(1); });