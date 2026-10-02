'use strict';

/* BitBoard renderer, talks to the P2P engine through the preload bridge. */

const els = {
  boardList: document.getElementById('board-list'),
  boardTitle: document.getElementById('board-title'),
  boardMeta: document.getElementById('board-meta'),
  imageList: document.getElementById('image-list'),
  imageGallery: document.getElementById('image-gallery'),
  tabGallery: document.getElementById('tab-gallery'),
  tabDetails: document.getElementById('tab-details'),
  lightbox: document.getElementById('lightbox'),
  lightboxImg: document.getElementById('lightbox-img'),
  lightboxCaption: document.getElementById('lightbox-caption'),
  addImageBtn: document.getElementById('btn-add-image'),
  addBoardBtn: document.getElementById('btn-add-board'),
  joinBoardBtn: document.getElementById('btn-join-board'),
  settingsBtn: document.getElementById('btn-settings'),
  log: document.getElementById('log')
};

let boards = [];
let discovered = [];           // boards seen on the LAN but not joined
let selectedBoard = null;
let currentView = 'gallery';   // 'gallery' (image board) | 'details' (file list)
let lightboxFiles = [];        // files of the board shown in the lightbox
let lightboxIndex = -1;
let settings = null;           // { showLog, confirmLeaveBoard, imageHashes, boards, peers }

const ICON_PALETTE = [
  '\uD83D\uDCCC', '\uD83C\uDFA8', '\uD83D\uDCF7', '\uD83D\uDC31', '\uD83D\uDC15',
  '\uD83C\uDF55', '\uD83D\uDE97', '\u2708\uFE0F', '\uD83C\uDFD6', '\u26F0',
  '\uD83C\uDFAE', '\uD83C\uDFB5', '\uD83D\uDCBB', '\uD83D\uDCDA', '\uD83D\uDCBC',
  '\uD83D\uDD25', '\u2B50', '\uD83C\uDF19', '\u2600\uFE0F', '\uD83C\uDF08',
  '\uD83C\uDF40', '\uD83C\uDF0A', '\uD83C\uDF82', '\uD83C\uDF81'
];

function fmtSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
}

function fmtDate(ts) {
  const d = new Date(ts);
  return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function renderBoardList() {
  els.boardList.innerHTML = '';
  for (const b of boards) {
    const item = document.createElement('div');
    item.className = 'board-item' + (b.name === selectedBoard ? ' active' : '');
    item.title = b.name + ' (right-click for options)';

    const icon = document.createElement('span');
    icon.className = 'board-icon';
    icon.textContent = b.icon || '\u25A6';

    const name = document.createElement('span');
    name.className = 'board-name';
    name.textContent = b.name;

    item.appendChild(icon);
    item.appendChild(name);
    item.addEventListener('click', () => {
      selectedBoard = b.name;
      renderBoardList();
      renderContent();
    });
    // Right-click / long-press: board options (leave / icon / blacklist).
    item.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      showBoardMenu(b.name);
    });
    els.boardList.appendChild(item);
  }

  // Boards other devices announced on the LAN that we have NOT joined.
  // They are offered here, nothing is ever auto-joined.
  const offerable = discovered.filter(n => !boards.some(b => b.name === n));
  if (offerable.length) {
    const head = document.createElement('div');
    head.className = 'discovered-header';
    head.textContent = 'Discovered on network';
    els.boardList.appendChild(head);

    for (const name of offerable) {
      const item = document.createElement('div');
      item.className = 'board-item discovered-item';
      item.title = 'Join "' + name + '"';

      const icon = document.createElement('span');
      icon.className = 'board-icon';
      icon.textContent = '\u2609';

      const nameEl = document.createElement('span');
      nameEl.className = 'board-name';
      nameEl.textContent = name;

      item.appendChild(icon);
      item.appendChild(nameEl);
      item.addEventListener('click', () => {
        window.bitboard.joinBoard(name).catch((err) => {
          const msg = String((err && err.message) || err)
            .replace(/^Error invoking remote method '[^']+': (Error: )?/, '');
          logLine('Failed to join "' + name + '": ' + msg);
        });
      });
      els.boardList.appendChild(item);
    }
  }
}

function renderContent() {
  const board = boards.find(b => b.name === selectedBoard);
  els.addImageBtn.disabled = !board;

  els.tabGallery.classList.toggle('active', currentView === 'gallery');
  els.tabDetails.classList.toggle('active', currentView === 'details');
  els.imageList.style.display = currentView === 'details' ? '' : 'none';
  els.imageGallery.style.display = currentView === 'gallery' ? '' : 'none';

  if (!board) {
    els.boardTitle.textContent = 'No board selected';
    els.boardMeta.textContent = '';
    const empty = '<div class="empty-state">Select or create a board in the sidebar.<br>Boards replicate automatically between devices running BitBoard on the same network.</div>';
    els.imageList.innerHTML = empty;
    els.imageGallery.innerHTML = empty;
    return;
  }

  els.boardTitle.textContent = board.name;
  els.boardMeta.textContent =
    `${board.fileCount} images \u00B7 ${fmtSize(board.totalBytes)} \u00B7 ${board.peers} peer(s) \u00B7 ${Math.round(board.progress * 100)}% synced`;

  if (currentView === 'gallery') renderGallery(board);
  else renderDetails(board);
}

function renderDetails(board) {
  els.imageList.innerHTML = '';
  if (!board.files.length) {
    els.imageList.innerHTML = '<div class="empty-state">No images yet. Use "Add image" to publish one to the swarm.</div>';
    return;
  }

  for (const f of board.files) {
    const row = document.createElement('div');
    row.className = 'file-row';

    const thumb = document.createElement('img');
    thumb.className = 'file-thumb';
    window.bitboard.getThumbnail(board.name, f.name).then(dataUrl => {
      if (dataUrl) {
        thumb.src = dataUrl;
      } else {
        const ph = document.createElement('div');
        ph.className = 'file-thumb-placeholder';
        ph.textContent = '\u25A6';
        thumb.replaceWith(ph);
      }
    });

    const info = document.createElement('div');
    info.className = 'file-info';
    const nm = document.createElement('div');
    nm.className = 'file-name';
    nm.textContent = f.name;
    const dt = document.createElement('div');
    dt.className = 'file-date';
    dt.textContent = 'Downloaded ' + fmtDate(f.downloadedAt);
    info.appendChild(nm);
    info.appendChild(dt);

    const size = document.createElement('div');
    size.className = 'file-size';
    size.textContent = fmtSize(f.size);

    // Per-image actions: blacklist by content hash / delete.
    const actions = document.createElement('div');
    actions.className = 'file-actions';
    const blockBtn = document.createElement('button');
    blockBtn.className = 'mini-btn';
    blockBtn.title = 'Blacklist this image (by content hash)';
    blockBtn.textContent = '\u26D4';
    blockBtn.addEventListener('click', () => blacklistImage(board.name, f.name));
    const delBtn = document.createElement('button');
    delBtn.className = 'mini-btn';
    delBtn.title = 'Delete this image locally';
    delBtn.textContent = '\uD83D\uDDD1';
    delBtn.addEventListener('click', () => deleteImage(board.name, f.name));
    actions.appendChild(blockBtn);
    actions.appendChild(delBtn);

    row.appendChild(thumb);
    row.appendChild(info);
    row.appendChild(size);
    row.appendChild(actions);
    els.imageList.appendChild(row);
  }
}

/* Image-board style masonry gallery. Tiles are clickable and open the
   lightbox; keyboard left/right cycles through the board's images. */
function renderGallery(board) {
  els.imageGallery.innerHTML = '';
  if (!board.files.length) {
    els.imageGallery.innerHTML = '<div class="empty-state">No images yet. Use "Add image" to publish one to the swarm.</div>';
    return;
  }

  lightboxFiles = board.files;

  for (let i = 0; i < board.files.length; i++) {
    const f = board.files[i];
    const tile = document.createElement('div');
    tile.className = 'gallery-tile';
    tile.title = f.name + ' \u00B7 ' + fmtSize(f.size);

    const img = document.createElement('img');
    img.className = 'gallery-img';
    img.alt = f.name;
    img.loading = 'lazy';
    window.bitboard.getThumbnail(board.name, f.name).then(dataUrl => {
      if (dataUrl) {
        img.src = dataUrl;
      } else {
        tile.classList.add('pending');
        const ph = document.createElement('div');
        ph.className = 'gallery-placeholder';
        ph.textContent = '\u25A6';
        img.replaceWith(ph);
      }
    });

    const meta = document.createElement('div');
    meta.className = 'gallery-meta';
    const nm = document.createElement('span');
    nm.className = 'gallery-name';
    nm.textContent = f.name;
    const sz = document.createElement('span');
    sz.className = 'gallery-size';
    sz.textContent = fmtSize(f.size);
    meta.appendChild(nm);
    meta.appendChild(sz);

    tile.appendChild(img);
    tile.appendChild(meta);
    tile.addEventListener('click', () => openLightbox(i));
    els.imageGallery.appendChild(tile);
  }
}

/* ---------------- lightbox ---------------- */

function openLightbox(index) {
  const f = lightboxFiles[index];
  if (!f || !selectedBoard) return;
  lightboxIndex = index;
  els.lightboxImg.src = '';
  els.lightboxCaption.textContent = f.name + ' \u00B7 ' + fmtSize(f.size) + ' \u00B7 ' + fmtDate(f.downloadedAt);
  els.lightbox.classList.remove('hidden');
  window.bitboard.getThumbnail(selectedBoard, f.name).then(dataUrl => {
    // Ignore stale responses if the user already moved to another image.
    if (dataUrl && lightboxIndex === index) els.lightboxImg.src = dataUrl;
  });
}

function closeLightbox() {
  els.lightbox.classList.add('hidden');
  els.lightboxImg.src = '';
  lightboxIndex = -1;
}

function stepLightbox(delta) {
  if (lightboxIndex < 0 || !lightboxFiles.length) return;
  openLightbox((lightboxIndex + delta + lightboxFiles.length) % lightboxFiles.length);
}

els.lightbox.addEventListener('click', (e) => {
  if (e.target === els.lightbox || e.target === els.lightboxImg || e.target === els.lightboxCaption) closeLightbox();
});

document.addEventListener('keydown', (e) => {
  if (els.lightbox.classList.contains('hidden')) return;
  if (e.key === 'Escape') closeLightbox();
  else if (e.key === 'ArrowLeft') stepLightbox(-1);
  else if (e.key === 'ArrowRight') stepLightbox(1);
});

/* ---------------- view tabs ---------------- */

els.tabGallery.addEventListener('click', () => { currentView = 'gallery'; renderContent(); });
els.tabDetails.addEventListener('click', () => { currentView = 'details'; renderContent(); });

/* ---------------- modal dialog (Electron has no window.prompt) ---------------- */

const modal = {
  backdrop: document.getElementById('modal-backdrop'),
  title: document.getElementById('modal-title'),
  input: document.getElementById('modal-input'),
  error: document.getElementById('modal-error'),
  ok: document.getElementById('modal-ok'),
  cancel: document.getElementById('modal-cancel')
};

let modalResolve = null;

function askBoardName(title, prefill = '', errorText = '') {
  return new Promise((resolve) => {
    modalResolve = resolve;
    modal.title.textContent = title;
    modal.input.value = prefill;
    modal.error.textContent = errorText;
    modal.backdrop.classList.remove('hidden');
    modal.input.focus();
  });
}

function closeModal(value) {
  modal.backdrop.classList.add('hidden');
  if (modalResolve) { modalResolve(value); modalResolve = null; }
}

modal.ok.addEventListener('click', () => closeModal(modal.input.value.trim() || null));
modal.cancel.addEventListener('click', () => closeModal(null));
modal.input.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') closeModal(modal.input.value.trim() || null);
  if (e.key === 'Escape') closeModal(null);
});

/* ---------------- actions ---------------- */

/* Ask for a name, run the action, and if it fails keep the dialog open with the
   error shown (the log panel is hidden, so failures used to be invisible). */
async function promptAndRun(title, action, failLabel) {
  let name = '';
  let errorText = '';
  for (; ;) {
    name = await askBoardName(title, name, errorText);
    if (!name) return;
    try {
      await action(name);
      selectedBoard = name;
      return;
    } catch (err) {
      // Strip Electron's "Error invoking remote method ..." prefix.
      const msg = String((err && err.message) || err).replace(/^Error invoking remote method '[^']+': (Error: )?/, '');
      logLine(failLabel + ': ' + msg);
      errorText = failLabel + ': ' + msg;
    }
  }
}

els.addBoardBtn.addEventListener('click', () =>
  promptAndRun('New board', (n) => window.bitboard.createBoard(n), 'Failed to create board'));

els.joinBoardBtn.addEventListener('click', () =>
  promptAndRun('Join board (name must match exactly on the other device)',
    (n) => window.bitboard.joinBoard(n), 'Failed to join board'));

els.addImageBtn.addEventListener('click', () => {
  if (selectedBoard) window.bitboard.addImage(selectedBoard);
});

/* ---------------- board options menu (leave / icon / blacklist) ---------------- */

function showBoardMenu(name) {
  promptChoice('Board "' + name + '"', ['Set icon', 'Leave board', 'Blacklist board'])
    .then((choice) => {
      if (choice === 0) showIconPicker(name);
      else if (choice === 1) confirmLeaveBoard(name);
      else if (choice === 2) blacklistBoard(name);
    });
}

/* Minimal in-page choice dialog. Resolves with the chosen index, or null. */
function promptChoice(title, options) {
  return new Promise((resolve) => {
    const backdrop = document.createElement('div');
    backdrop.className = 'menu-backdrop';
    const box = document.createElement('div');
    box.className = 'menu-box';
    const t = document.createElement('div');
    t.className = 'menu-title';
    t.textContent = title;
    box.appendChild(t);
    const done = (value) => { backdrop.remove(); resolve(value); };
    options.forEach((opt, i) => {
      const b = document.createElement('button');
      b.className = 'menu-option';
      b.textContent = opt;
      b.addEventListener('click', () => done(i));
      box.appendChild(b);
    });
    const cancel = document.createElement('button');
    cancel.className = 'menu-option menu-cancel';
    cancel.textContent = 'Cancel';
    cancel.addEventListener('click', () => done(null));
    box.appendChild(cancel);
    backdrop.appendChild(box);
    backdrop.addEventListener('click', (e) => { if (e.target === backdrop) done(null); });
    document.body.appendChild(backdrop);
  });
}

function confirmLeaveBoard(name) {
  const doLeave = (deleteFiles) => {
    window.bitboard.leaveBoard(name, deleteFiles).catch((err) => {
      logLine('Failed to leave "' + name + '": ' + String((err && err.message) || err));
    });
  };
  if (settings && settings.confirmLeaveBoard) {
    promptChoice('Leave "' + name + '"? You will stop syncing it.',
      ['Leave (keep files)', 'Leave and delete files'])
      .then((choice) => {
        if (choice === 0) doLeave(false);
        else if (choice === 1) doLeave(true);
      });
  } else {
    doLeave(false);
  }
}

function blacklistBoard(name) {
  window.bitboard.addBoardBlacklist(name);
  logLine('Board "' + name + '" blacklisted, unblock it in Settings.');
}

/* ---------------- board icon picker ---------------- */

let iconPickerBoard = null;

function showIconPicker(name) {
  iconPickerBoard = name;
  const grid = document.getElementById('icon-grid');
  grid.innerHTML = '';
  const current = (boards.find(b => b.name === name) || {}).icon || '';
  for (const emoji of ICON_PALETTE) {
    const b = document.createElement('button');
    b.className = 'icon-option' + (emoji === current ? ' selected' : '');
    b.textContent = emoji;
    b.addEventListener('click', () => {
      window.bitboard.setBoardIcon(iconPickerBoard, emoji);
      document.getElementById('icon-picker-backdrop').classList.add('hidden');
    });
    grid.appendChild(b);
  }
  document.getElementById('icon-picker-backdrop').classList.remove('hidden');
}

document.getElementById('icon-cancel').addEventListener('click', () => {
  document.getElementById('icon-picker-backdrop').classList.add('hidden');
});
document.getElementById('icon-clear').addEventListener('click', () => {
  if (iconPickerBoard) window.bitboard.setBoardIcon(iconPickerBoard, '');
  document.getElementById('icon-picker-backdrop').classList.add('hidden');
});

/* ---------------- image actions (blacklist by hash / delete) ---------------- */

async function blacklistImage(boardName, fileName) {
  const hash = await window.bitboard.imageHash(boardName, fileName);
  if (!hash) { logLine('Could not hash "' + fileName + '"'); return; }
  await window.bitboard.addImageHash(hash);
  logLine('Image blacklisted by hash ' + hash.slice(0, 12) + '…, it is now hidden and never synced.');
}

function deleteImage(boardName, fileName) {
  window.bitboard.removeImage(boardName, fileName).catch((err) => {
    logLine('Failed to delete "' + fileName + '": ' + String((err && err.message) || err));
  });
}

/* ---------------- settings panel ---------------- */

function openSettings() {
  window.bitboard.getSettings().then((s) => {
    settings = s;
    document.getElementById('setting-show-log').checked = !!s.showLog;
    document.getElementById('setting-confirm-leave').checked = !!s.confirmLeaveBoard;
    renderBlacklist('blacklist-images', s.imageHashes || [], (h) => window.bitboard.removeImageHash(h), (x) => x.slice(0, 16) + '…');
    renderBlacklist('blacklist-boards', s.boards || [], (n) => window.bitboard.removeBoardBlacklist(n));
    renderBlacklist('blacklist-peers', s.peers || [], (p) => window.bitboard.removePeerBlacklist(p), (x) => x.slice(0, 12) + '…');
    window.bitboard.getPeerId().then((id) => {
      document.getElementById('settings-peerid').textContent = 'Your peer ID: ' + id;
    });
    document.getElementById('settings-backdrop').classList.remove('hidden');
  });
}

function renderBlacklist(containerId, items, onRemove, fmt) {
  const box = document.getElementById(containerId);
  box.innerHTML = '';
  if (!items.length) {
    const e = document.createElement('div');
    e.className = 'blacklist-empty';
    e.textContent = 'Nothing blacklisted.';
    box.appendChild(e);
    return;
  }
  for (const item of items) {
    const row = document.createElement('div');
    row.className = 'blacklist-row';
    const label = document.createElement('span');
    label.className = 'blacklist-label';
    label.textContent = fmt ? fmt(item) : item;
    label.title = item;
    const btn = document.createElement('button');
    btn.className = 'mini-btn';
    btn.textContent = 'Remove';
    btn.addEventListener('click', () => {
      onRemove(item);
      row.remove();
      if (!box.children.length) {
        const e = document.createElement('div');
        e.className = 'blacklist-empty';
        e.textContent = 'Nothing blacklisted.';
        box.appendChild(e);
      }
    });
    row.appendChild(label);
    row.appendChild(btn);
    box.appendChild(row);
  }
}

els.settingsBtn.addEventListener('click', openSettings);
document.getElementById('settings-close').addEventListener('click', () => {
  document.getElementById('settings-backdrop').classList.add('hidden');
});
document.getElementById('setting-show-log').addEventListener('change', (e) => {
  window.bitboard.setSetting('showLog', e.target.checked);
  els.log.parentElement.style.display = e.target.checked ? '' : 'none';
});
document.getElementById('setting-confirm-leave').addEventListener('change', (e) => {
  window.bitboard.setSetting('confirmLeaveBoard', e.target.checked);
});

/* ---------------- engine events ---------------- */

window.bitboard.onBoards((snapshot) => {
  boards = snapshot;
  if (selectedBoard && !boards.some(b => b.name === selectedBoard)) selectedBoard = null;
  if (!selectedBoard && boards.length) selectedBoard = boards[0].name;
  renderBoardList();
  renderContent();
});

window.bitboard.onDiscovered((names) => {
  discovered = names || [];
  renderBoardList();
});

function logLine(line) {
  const div = document.createElement('div');
  div.textContent = line;
  els.log.appendChild(div);
  while (els.log.childNodes.length > 100) els.log.removeChild(els.log.firstChild);
  els.log.scrollTop = els.log.scrollHeight;
}

window.bitboard.onLog(logLine);

/* ---------------- boot ---------------- */

(async () => {
  boards = await window.bitboard.getBoards();
  discovered = await window.bitboard.getDiscovered();
  settings = await window.bitboard.getSettings();
  if (!settings.showLog) els.log.parentElement.style.display = 'none';
  if (boards.length) selectedBoard = boards[0].name;
  renderBoardList();
  renderContent();
})();