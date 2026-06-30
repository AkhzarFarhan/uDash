/* ============================================================
   uDash — DDPAI Dashcam Web Manager
   Application Logic
   ============================================================ */

(function () {
  'use strict';

  /* ── State ── */
  const state = {
    ip: '193.168.0.1',
    useProxy: false,
    proxyPort: 3000,
    clips: [],              // { fileName, date, time, size, duration }
    selected: new Set(),
    downloaded: new Set(),   // persisted in localStorage
    scanning: false,
    batchActive: false,
    dirHandle: null,         // File System Access API directory handle
    downloadPath: 'Browser Downloads',
  };

  /* ── DOM refs ── */
  const $ = (sel) => document.querySelector(sel);
  const $$ = (sel) => document.querySelectorAll(sel);

  const DOM = {
    ipInput:         $('#ip-input'),
    btnScan:         $('#btn-scan'),
    btnSelectAll:    $('#btn-select-all'),
    btnBatch:        $('#btn-batch'),
    btnChoosePath:   $('#btn-choose-path'),
    btnProxyToggle:  $('#btn-proxy-toggle'),
    pathDisplay:     $('#path-display'),
    grid:            $('#video-grid'),
    statusDot:       $('#status-dot'),
    statusText:      $('#status-text'),
    statTotal:       $('#stat-total'),
    statSelected:    $('#stat-selected'),
    statDownloaded:  $('#stat-downloaded'),
    statSize:        $('#stat-size'),
    modalOverlay:    $('#stream-modal'),
    modalTitle:      $('#modal-title'),
    modalVideo:      $('#modal-video'),
    btnModalClose:   $('#btn-modal-close'),
    batchOverlay:    $('#batch-overlay'),
    batchSubtitle:   $('#batch-subtitle'),
    batchCurrent:    $('#batch-current'),
    batchFill:       $('#batch-fill'),
    batchCancel:     $('#batch-cancel'),
    toastContainer:  $('#toast-container'),
  };

  /* ── Init ── */
  function init() {
    loadState();
    DOM.ipInput.value = state.ip;
    updatePathDisplay();
    updateToolbar();
    updateStats();

    // Event listeners
    DOM.btnScan.addEventListener('click', scan);
    DOM.btnSelectAll.addEventListener('click', toggleSelectAll);
    DOM.btnBatch.addEventListener('click', batchDownload);
    DOM.btnChoosePath.addEventListener('click', choosePath);
    DOM.btnProxyToggle.addEventListener('click', toggleProxy);
    DOM.btnModalClose.addEventListener('click', closeStream);
    DOM.batchCancel.addEventListener('click', cancelBatch);
    DOM.modalOverlay.addEventListener('click', (e) => {
      if (e.target === DOM.modalOverlay) closeStream();
    });
    DOM.ipInput.addEventListener('change', () => {
      state.ip = DOM.ipInput.value.trim();
      saveState();
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') closeStream();
    });
  }

  /* ── Persistence ── */
  function loadState() {
    try {
      const saved = localStorage.getItem('udash_state');
      if (saved) {
        const parsed = JSON.parse(saved);
        if (parsed.ip) state.ip = parsed.ip;
        if (parsed.useProxy !== undefined) state.useProxy = parsed.useProxy;
        if (parsed.downloaded) state.downloaded = new Set(parsed.downloaded);
        if (parsed.downloadPath) state.downloadPath = parsed.downloadPath;
      }
    } catch { /* ignore */ }
  }

  function saveState() {
    try {
      localStorage.setItem('udash_state', JSON.stringify({
        ip: state.ip,
        useProxy: state.useProxy,
        downloaded: [...state.downloaded],
        downloadPath: state.downloadPath,
      }));
    } catch { /* ignore */ }
  }

  /* ── URL Building ── */
  function baseUrl() {
    if (state.useProxy) {
      return `http://localhost:${state.proxyPort}/api`;
    }
    return `http://${state.ip}`;
  }

  function fileUrl(fileName) {
    return `${baseUrl()}/${fileName}`;
  }

  function apiUrl() {
    return `${baseUrl()}/vcam/cmd.cgi`;
  }

  /* ── Scan ── */
  async function scan() {
    if (state.scanning) return;
    state.scanning = true;
    state.clips = [];
    state.selected.clear();
    setStatus('scanning', 'Scanning…');
    DOM.btnScan.disabled = true;
    DOM.btnScan.innerHTML = '<span class="spinner">⟳</span> Scanning…';
    showSkeletons();

    try {
      const clips = await fetchFileList();
      state.clips = clips;
      setStatus('connected', `${clips.length} clips found`);
      toast('success', `Found ${clips.length} video clips on dashcam.`);
    } catch (err) {
      setStatus('', 'Disconnected');
      toast('error', `Scan failed: ${err.message}`);
      console.error('Scan error:', err);
    } finally {
      state.scanning = false;
      DOM.btnScan.disabled = false;
      DOM.btnScan.innerHTML = '🔍 Scan';
      renderGrid();
      updateStats();
      updateToolbar();
    }
  }

  async function fetchFileList() {
    /* Strategy:
       1. Try the API endpoint /vcam/cmd.cgi?cmd=APP_GetFileList
       2. If that fails, try fetching the root / and parsing HTML directory listing
       3. Parse the response to extract MP4 filenames */

    let clips = [];

    // Attempt 1: DDPAI API
    try {
      clips = await fetchViaApi();
      if (clips.length > 0) return clips;
    } catch { /* fall through */ }

    // Attempt 2: HTML directory listing
    try {
      clips = await fetchViaDirectoryListing();
      if (clips.length > 0) return clips;
    } catch { /* fall through */ }

    throw new Error(
      'Could not reach dashcam. Ensure you are connected to the dashcam Wi-Fi' +
      (state.useProxy ? '' : ' or enable Proxy Mode.')
    );
  }

  async function fetchViaApi() {
    const urls = [
      `${apiUrl()}?cmd=APP_GetFileList`,
      `${apiUrl()}?cmd=getFileList`,
      `${apiUrl()}?cmd=APP_PlaybackListReq`,
    ];

    for (const url of urls) {
      try {
        const resp = await fetch(url, { signal: AbortSignal.timeout(8000) });
        if (!resp.ok) continue;
        const text = await resp.text();
        const clips = parseApiResponse(text);
        if (clips.length > 0) return clips;
      } catch { continue; }
    }
    return [];
  }

  function parseApiResponse(text) {
    const clips = [];
    // Try JSON parse first
    try {
      const json = JSON.parse(text);
      const files = json.files || json.data?.files || json.list || json.data?.list || [];
      for (const f of files) {
        const name = f.name || f.fileName || f.NAME || '';
        if (name.toLowerCase().endsWith('.mp4')) {
          clips.push(parseFileName(name, f.size || f.SIZE || 0));
        }
      }
      if (clips.length > 0) return clips;
    } catch { /* not JSON */ }

    // Try extracting MP4 filenames from text/xml/html
    const regex = /(\d{14}_\w+\.mp4)/gi;
    const matches = text.match(regex) || [];
    for (const m of [...new Set(matches)]) {
      clips.push(parseFileName(m, 0));
    }

    return clips;
  }

  async function fetchViaDirectoryListing() {
    const resp = await fetch(`${baseUrl()}/`, { signal: AbortSignal.timeout(8000) });
    const html = await resp.text();
    const regex = /href="([^"]*\.mp4)"/gi;
    const clips = [];
    let match;
    while ((match = regex.exec(html)) !== null) {
      const name = match[1].replace(/^\//, '');
      clips.push(parseFileName(name, 0));
    }
    return clips;
  }

  function parseFileName(fileName, sizeBytes) {
    /* Expected format: YYYYMMDDHHMMSS_0060.mp4 or YYYYMMDDHHMMSS_F.mp4 */
    const nameOnly = fileName.split('/').pop();
    let date = '', time = '', duration = 60;

    const tsMatch = nameOnly.match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})/);
    if (tsMatch) {
      date = `${tsMatch[1]}-${tsMatch[2]}-${tsMatch[3]}`;
      time = `${tsMatch[4]}:${tsMatch[5]}:${tsMatch[6]}`;
    }

    const durMatch = nameOnly.match(/_(\d{4})\./);
    if (durMatch) {
      duration = parseInt(durMatch[1], 10);
    }

    return {
      fileName: nameOnly,
      date,
      time,
      duration,
      size: sizeBytes,
    };
  }

  /* ── Render ── */
  function renderGrid() {
    if (state.clips.length === 0 && !state.scanning) {
      DOM.grid.innerHTML = `
        <div class="empty-state">
          <div class="empty-icon">📹</div>
          <h3>No clips loaded</h3>
          <p>Connect to your DDPAI dashcam Wi-Fi and press <strong>Scan</strong> to discover video clips on the memory card.</p>
        </div>`;
      return;
    }

    DOM.grid.innerHTML = state.clips.map(clip => {
      const isSelected = state.selected.has(clip.fileName);
      const isDownloaded = state.downloaded.has(clip.fileName);
      const classes = [
        'video-card',
        isSelected ? 'selected' : '',
        isDownloaded ? 'downloaded' : '',
      ].filter(Boolean).join(' ');

      const sizeMB = clip.size > 0 ? (clip.size / (1024 * 1024)).toFixed(1) + ' MB' : '—';

      return `
        <div class="${classes}" data-file="${clip.fileName}">
          <div class="card-header">
            <div class="card-checkbox" onclick="window.__udash.toggleSelect('${clip.fileName}')" title="Select for batch download">
              ${isSelected ? '✓' : ''}
            </div>
            ${isDownloaded
              ? '<span class="card-status-badge downloaded">✓ Downloaded</span>'
              : '<span class="card-status-badge new">New</span>'}
          </div>
          <div class="card-body">
            <div class="card-filename">${clip.fileName}</div>
            <div class="card-meta">
              <span class="card-meta-item">
                <span class="icon">📅</span> ${clip.date || 'Unknown'}
              </span>
              <span class="card-meta-item">
                <span class="icon">🕐</span> ${clip.time || '—'}
              </span>
              <span class="card-meta-item">
                <span class="icon">💾</span> ${sizeMB}
              </span>
              <span class="card-meta-item">
                <span class="icon">⏱</span> ${clip.duration}s
              </span>
            </div>
          </div>
          <div class="card-actions">
            <button class="btn btn-stream" onclick="window.__udash.stream('${clip.fileName}')">
              ▶ Stream
            </button>
            ${isDownloaded
              ? `<button class="btn btn-downloaded" disabled>✓ Downloaded</button>`
              : `<button class="btn btn-download" onclick="window.__udash.download('${clip.fileName}')">
                  📥 Download
                </button>`}
          </div>
        </div>`;
    }).join('');
  }

  function showSkeletons() {
    DOM.grid.innerHTML = Array.from({ length: 8 }, () =>
      '<div class="skeleton-card"></div>'
    ).join('');
  }

  /* ── Selection ── */
  function toggleSelect(fileName) {
    if (state.selected.has(fileName)) {
      state.selected.delete(fileName);
    } else {
      state.selected.add(fileName);
    }
    renderGrid();
    updateStats();
    updateToolbar();
  }

  function toggleSelectAll() {
    if (state.selected.size === state.clips.length) {
      state.selected.clear();
    } else {
      state.clips.forEach(c => state.selected.add(c.fileName));
    }
    renderGrid();
    updateStats();
    updateToolbar();
  }

  /* ── Stream ── */
  function stream(fileName) {
    const url = fileUrl(fileName);
    DOM.modalTitle.textContent = fileName;
    DOM.modalVideo.src = url;
    DOM.modalOverlay.classList.add('active');
    DOM.modalVideo.play().catch(() => {
      toast('warning', 'Playback may require direct dashcam connection.');
    });
  }

  function closeStream() {
    DOM.modalOverlay.classList.remove('active');
    DOM.modalVideo.pause();
    DOM.modalVideo.src = '';
  }

  /* ── Download ── */
  async function download(fileName) {
    try {
      const url = fileUrl(fileName);

      if (state.dirHandle) {
        // Use File System Access API
        toast('info', `Downloading ${fileName}…`);
        const resp = await fetch(url);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const blob = await resp.blob();
        const fileHandle = await state.dirHandle.getFileHandle(fileName, { create: true });
        const writable = await fileHandle.createWritable();
        await writable.write(blob);
        await writable.close();
      } else {
        // Standard browser download via hidden link
        const a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
      }

      state.downloaded.add(fileName);
      saveState();
      renderGrid();
      updateStats();
      toast('success', `Downloaded: ${fileName}`);
    } catch (err) {
      toast('error', `Download failed: ${err.message}`);
    }
  }

  /* ── Batch Download ── */
  let batchCancelled = false;

  async function batchDownload() {
    const files = [...state.selected].filter(f => !state.downloaded.has(f));
    if (files.length === 0) {
      toast('warning', 'No undownloaded files selected.');
      return;
    }

    batchCancelled = false;
    state.batchActive = true;
    DOM.batchOverlay.classList.add('active');
    DOM.batchSubtitle.textContent = `0 of ${files.length} completed`;
    DOM.batchFill.style.width = '0%';

    for (let i = 0; i < files.length; i++) {
      if (batchCancelled) break;

      const f = files[i];
      DOM.batchCurrent.textContent = f;
      DOM.batchSubtitle.textContent = `${i} of ${files.length} completed`;
      DOM.batchFill.style.width = `${(i / files.length) * 100}%`;

      try {
        await download(f);
      } catch { /* individual errors already toasted */ }
    }

    DOM.batchFill.style.width = '100%';
    DOM.batchSubtitle.textContent = batchCancelled
      ? 'Batch cancelled.'
      : `${files.length} of ${files.length} completed`;

    setTimeout(() => {
      DOM.batchOverlay.classList.remove('active');
      state.batchActive = false;
      state.selected.clear();
      renderGrid();
      updateStats();
      updateToolbar();
    }, 1200);

    if (!batchCancelled) {
      toast('success', `Batch download complete: ${files.length} files.`);
    }
  }

  function cancelBatch() {
    batchCancelled = true;
    toast('warning', 'Batch download cancelled.');
  }

  /* ── Choose Download Path ── */
  async function choosePath() {
    if (!window.showDirectoryPicker) {
      toast('warning', 'Custom download path requires a Chromium-based browser (Chrome, Edge).');
      return;
    }
    try {
      state.dirHandle = await window.showDirectoryPicker({ mode: 'readwrite' });
      state.downloadPath = state.dirHandle.name;
      saveState();
      updatePathDisplay();
      toast('success', `Download path set to: ${state.dirHandle.name}`);
    } catch (err) {
      if (err.name !== 'AbortError') {
        toast('error', `Could not set path: ${err.message}`);
      }
    }
  }

  /* ── Proxy Toggle ── */
  function toggleProxy() {
    state.useProxy = !state.useProxy;
    saveState();
    DOM.btnProxyToggle.textContent = state.useProxy ? '🌐 Proxy: ON' : '🌐 Proxy: OFF';
    DOM.btnProxyToggle.classList.toggle('btn-success', state.useProxy);
    DOM.btnProxyToggle.classList.toggle('btn-secondary', !state.useProxy);
    toast('info', state.useProxy
      ? `Proxy mode enabled (localhost:${state.proxyPort}). Run proxy.js first.`
      : 'Direct mode enabled. Ensure dashcam Wi-Fi is connected.'
    );
  }

  /* ── UI Updates ── */
  function setStatus(type, text) {
    DOM.statusDot.className = 'status-dot' + (type ? ` ${type}` : '');
    DOM.statusText.textContent = text;
  }

  function updateStats() {
    DOM.statTotal.textContent = state.clips.length;
    DOM.statSelected.textContent = state.selected.size;
    DOM.statDownloaded.textContent = state.downloaded.size;

    const totalBytes = state.clips.reduce((s, c) => s + (c.size || 0), 0);
    DOM.statSize.textContent = totalBytes > 0
      ? (totalBytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
      : '—';
  }

  function updateToolbar() {
    const hasClips = state.clips.length > 0;
    const hasSelection = state.selected.size > 0;

    DOM.btnSelectAll.disabled = !hasClips;
    DOM.btnBatch.disabled = !hasSelection;

    const undownloadedSelected = [...state.selected].filter(f => !state.downloaded.has(f)).length;
    DOM.btnBatch.innerHTML = hasSelection
      ? `📥 Batch Download <span class="badge">${undownloadedSelected}</span>`
      : '📥 Batch Download';

    DOM.btnSelectAll.textContent = (state.selected.size === state.clips.length && hasClips)
      ? '☑ Deselect All'
      : '☐ Select All';
  }

  function updatePathDisplay() {
    DOM.pathDisplay.innerHTML = `
      <span class="icon">📁</span>
      ${state.downloadPath}`;
  }

  /* ── Toast Notifications ── */
  function toast(type, message) {
    const icons = { success: '✅', error: '❌', warning: '⚠️', info: 'ℹ️' };
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    el.innerHTML = `<span class="toast-icon">${icons[type] || ''}</span> ${message}`;
    DOM.toastContainer.appendChild(el);

    setTimeout(() => {
      el.style.opacity = '0';
      el.style.transform = 'translateX(30px)';
      el.style.transition = 'all 0.3s ease-in';
      setTimeout(() => el.remove(), 300);
    }, 4000);
  }

  /* ── Expose public API for inline onclick handlers ── */
  window.__udash = {
    toggleSelect,
    stream,
    download,
  };

  /* ── Boot ── */
  document.addEventListener('DOMContentLoaded', init);

})();
