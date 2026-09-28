'use strict';

/* BitBoard renderer — talks to the P2P engine through the preload bridge. */

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
  log: document.getElementById('log')
};

let boards = [];
let selectedBoard = null;
let currentView = 'gallery';   // 'gallery' (image board) | 'details' (file list)
let lightboxFiles = [];        // files of the board shown in the lightbox
let lightboxIndex = -1;

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
    item.title = b.name;

    const icon = document.createElement('span');
    icon.className = 'board-icon';
    icon.textContent = '\u25A6';

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
    els.boardList.appendChild(item);
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

    row.appendChild(thumb);
    row.appendChild(info);
    row.appendChild(size);
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
  for (;;) {
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

/* ---------------- engine events ---------------- */

window.bitboard.onBoards((snapshot) => {
  boards = snapshot;
  if (selectedBoard && !boards.some(b => b.name === selectedBoard)) selectedBoard = null;
  if (!selectedBoard && boards.length) selectedBoard = boards[0].name;
  renderBoardList();
  renderContent();
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
  if (boards.length) selectedBoard = boards[0].name;
  renderBoardList();
  renderContent();
})();