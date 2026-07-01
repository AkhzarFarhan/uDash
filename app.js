/* ============================================================
   uDash — DDPAI Dashcam Web Manager
   Application Logic (v2 — with session handshake & proxy)
   ============================================================ */

(function () {
  'use strict';

  /* ── State ── */
  const state = {
    dashcamIp: '193.168.0.1',
    sessionId: null,
    clips: [],              // { fileName, date, time, size, duration, path }
    selected: new Set(),
    downloaded: new Set(),   // persisted in localStorage
    scanning: false,
    batchActive: false,
    dirHandle: null,         // File System Access API directory handle
    downloadPath: 'Browser Downloads',
  };

  /* ── DOM refs ── */
  const $ = (sel) => document.querySelector(sel);
  const DOM = {};

  function cacheDom() {
    DOM.ipInput         = $('#ip-input');
    DOM.btnScan         = $('#btn-scan');
    DOM.btnSelectAll    = $('#btn-select-all');
    DOM.btnBatch        = $('#btn-batch');
    DOM.btnChoosePath   = $('#btn-choose-path');
    DOM.pathDisplay     = $('#path-display');
    DOM.grid            = $('#video-grid');
    DOM.statusDot       = $('#status-dot');
    DOM.statusText      = $('#status-text');
    DOM.statTotal       = $('#stat-total');
    DOM.statSelected    = $('#stat-selected');
    DOM.statDownloaded  = $('#stat-downloaded');
    DOM.statSize        = $('#stat-size');
    DOM.modalOverlay    = $('#stream-modal');
    DOM.modalTitle      = $('#modal-title');
    DOM.modalVideo      = $('#modal-video');
    DOM.btnModalClose   = $('#btn-modal-close');
    DOM.batchOverlay    = $('#batch-overlay');
    DOM.batchSubtitle   = $('#batch-subtitle');
    DOM.batchCurrent    = $('#batch-current');
    DOM.batchFill       = $('#batch-fill');
    DOM.batchCancel     = $('#batch-cancel');
    DOM.toastContainer  = $('#toast-container');
    DOM.logPanel        = $('#log-panel');
  }

  /* ─────────────────────────────────────────────────
     URL Routing
     If served via server.js → use /api/ prefix (CORS-free)
     If opened as file:// → try direct (will fail on CORS)
     ───────────────────────────────────────────────── */
  function isServedViaServer() {
    return window.location.protocol === 'http:' || window.location.protocol === 'https:';
  }

  function apiBase() {
    if (isServedViaServer()) {
      // Proxied through server.js — no CORS issues
      return '/api';
    }
    // Direct mode (file://) — will likely be blocked by CORS
    return `http://${state.dashcamIp}`;
  }

  function dashcamDirectUrl(filePath) {
    // For streaming/download — always use direct dashcam IP
    // <video> and <a download> elements don't enforce CORS
    return `http://${state.dashcamIp}/${filePath}`;
  }

  function proxyUrl(filePath) {
    if (isServedViaServer()) {
      return `/api/${filePath}`;
    }
    return `http://${state.dashcamIp}/${filePath}`;
  }

  /* ─────────────────────────────────────────────────
     Logging
     ───────────────────────────────────────────────── */
  function log(msg) {
    const ts = new Date().toLocaleTimeString();
    const line = `[${ts}] ${msg}`;
    console.log(line);
    if (DOM.logPanel) {
      DOM.logPanel.textContent += line + '\n';
      DOM.logPanel.scrollTop = DOM.logPanel.scrollHeight;
    }
  }

  /* ─────────────────────────────────────────────────
     DDPAI Session Management
     The dashcam requires a session ID before listing files.
     POST /vcam/cmd.cgi?cmd=API_RequestSessionID
     ───────────────────────────────────────────────── */
  async function requestSession() {
    log('Requesting session from dashcam...');
    const url = `${apiBase()}/vcam/cmd.cgi?cmd=API_RequestSessionID`;
    try {
      const resp = await fetch(url, {
        method: 'POST',
        signal: AbortSignal.timeout(8000),
      });
      const text = await resp.text();
      log(`Session response: ${text.substring(0, 200)}`);

      // Try to extract session ID from JSON or text
      try {
        const json = JSON.parse(text);
        if (json.sessionid) {
          state.sessionId = json.sessionid;
        } else if (json.data && json.data.sessionid) {
          state.sessionId = json.data.sessionid;
        }
      } catch {
        // Try regex extraction
        const match = text.match(/sessionid["\s:=]+([a-zA-Z0-9]+)/i);
        if (match) state.sessionId = match[1];
      }

      if (state.sessionId) {
        log(`Session acquired: ${state.sessionId}`);
      } else {
        log('Session response received but no session ID found (may not be required for this model).');
      }
      return true;
    } catch (err) {
      log(`Session request failed: ${err.message}`);
      return false;
    }
  }

  /* ─────────────────────────────────────────────────
     Fetch file list — multi-strategy approach
     ───────────────────────────────────────────────── */
  async function fetchFileList() {
    const strategies = [
      { name: 'record.log', fn: fetchViaRecordLog },
      { name: 'API PlaybackList', fn: fetchViaPlaybackApi },
      { name: 'API GetFileList', fn: fetchViaGetFileList },
      { name: 'HTML directory listing', fn: fetchViaDirectoryListing },
      { name: 'Root index', fn: fetchViaRootIndex },
    ];

    for (const strategy of strategies) {
      try {
        log(`Trying strategy: ${strategy.name}...`);
        const clips = await strategy.fn();
        if (clips && clips.length > 0) {
          log(`✅ ${strategy.name} returned ${clips.length} clips.`);
          return clips;
        }
        log(`⚠ ${strategy.name} returned 0 clips.`);
      } catch (err) {
        log(`✗ ${strategy.name} failed: ${err.message}`);
      }
    }

    throw new Error('All scan strategies exhausted. Is the PC connected to DDPAI WiFi?');
  }

  /* Strategy 1: GET /record.log — plain text file list */
  async function fetchViaRecordLog() {
    const url = `${apiBase()}/record.log`;
    const resp = await fetch(url, { signal: AbortSignal.timeout(8000) });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const text = await resp.text();
    log(`record.log content (${text.length} bytes): ${text.substring(0, 300)}`);
    return parseTextFileList(text);
  }

  /* Strategy 2: POST /vcam/cmd.cgi?cmd=APP_PlaybackListReq */
  async function fetchViaPlaybackApi() {
    const headers = {};
    if (state.sessionId) {
      headers['Cookie'] = `sessionid=${state.sessionId}`;
    }

    // Try multiple playback commands used by different DDPAI firmware versions
    const commands = [
      'APP_PlaybackListReq',
      'API_PlaybackListReq',
    ];

    for (const cmd of commands) {
      try {
        const url = `${apiBase()}/vcam/cmd.cgi?cmd=${cmd}`;
        const resp = await fetch(url, {
          method: 'POST',
          headers,
          signal: AbortSignal.timeout(8000),
        });
        if (!resp.ok) continue;
        const text = await resp.text();
        log(`${cmd} response (${text.length} bytes): ${text.substring(0, 300)}`);
        const clips = parseApiResponse(text);
        if (clips.length > 0) return clips;
      } catch { continue; }
    }
    return [];
  }

  /* Strategy 3: GET /vcam/cmd.cgi?cmd=APP_GetFileList */
  async function fetchViaGetFileList() {
    const headers = {};
    if (state.sessionId) {
      headers['Cookie'] = `sessionid=${state.sessionId}`;
    }

    const commands = [
      'APP_GetFileList',
      'API_GetFileList',
      'APP_GetFileList&type=0&start=0&count=1000',
    ];

    for (const cmd of commands) {
      try {
        const url = `${apiBase()}/vcam/cmd.cgi?cmd=${cmd}`;
        const resp = await fetch(url, { headers, signal: AbortSignal.timeout(8000) });
        if (!resp.ok) continue;
        const text = await resp.text();
        log(`${cmd} response (${text.length} bytes): ${text.substring(0, 300)}`);
        const clips = parseApiResponse(text);
        if (clips.length > 0) return clips;
      } catch { continue; }
    }
    return [];
  }

  /* Strategy 4: GET / and parse HTML directory listing (common for simple HTTP servers) */
  async function fetchViaDirectoryListing() {
    const url = `${apiBase()}/`;
    const resp = await fetch(url, { signal: AbortSignal.timeout(8000) });
    const html = await resp.text();
    log(`Root HTML (${html.length} bytes): ${html.substring(0, 300)}`);

    const clips = [];
    // Match href="...mp4" patterns
    const hrefRegex = /href="([^"]*\.mp4)"/gi;
    let match;
    while ((match = hrefRegex.exec(html)) !== null) {
      const name = match[1].replace(/^\//, '');
      clips.push(parseFileName(name, 0));
    }

    // Also try to find mp4 filenames as plain text (some servers return text lists)
    if (clips.length === 0) {
      const textClips = parseTextFileList(html);
      if (textClips.length > 0) return textClips;
    }

    return clips;
  }

  /* Strategy 5: Probe common DDPAI file paths */
  async function fetchViaRootIndex() {
    // Some DDPAI models serve files at /tmp/SD0/Normal/ or /sd/normal/
    const paths = ['tmp/SD0/Normal/', 'sd/normal/', 'DCIM/'];

    for (const p of paths) {
      try {
        const url = `${apiBase()}/${p}`;
        const resp = await fetch(url, { signal: AbortSignal.timeout(5000) });
        if (!resp.ok) continue;
        const html = await resp.text();
        const clips = [];
        const regex = /href="([^"]*\.mp4)"/gi;
        let match;
        while ((match = regex.exec(html)) !== null) {
          let name = match[1].replace(/^\//, '');
          clips.push(parseFileName(name, 0, p));
        }
        if (clips.length > 0) return clips;
      } catch { continue; }
    }
    return [];
  }

  /* ─────────────────────────────────────────────────
     Response Parsers
     ───────────────────────────────────────────────── */
  function parseApiResponse(text) {
    const clips = [];

    // Try JSON
    try {
      const json = JSON.parse(text);
      const fileArrays = [
        json.files, json.data?.files,
        json.list, json.data?.list,
        json.result?.files, json.result?.list,
        json.playback,
      ];

      for (const files of fileArrays) {
        if (Array.isArray(files)) {
          for (const f of files) {
            const name = f.name || f.fileName || f.NAME || f.fn || '';
            const size = f.size || f.SIZE || f.s || 0;
            const path = f.path || f.PATH || f.d || '';
            if (name.toLowerCase().endsWith('.mp4')) {
              clips.push(parseFileName(name, size, path));
            }
          }
          if (clips.length > 0) return clips;
        }
      }
    } catch { /* Not JSON */ }

    // Try XML
    if (text.includes('<') && text.includes('file')) {
      const nameRegex = /<(?:file|name|fn)[^>]*>([^<]*\.mp4)<\//gi;
      let match;
      while ((match = nameRegex.exec(text)) !== null) {
        clips.push(parseFileName(match[1].trim(), 0));
      }
      if (clips.length > 0) return clips;
    }

    // Fallback: extract MP4 filenames from raw text
    return parseTextFileList(text);
  }

  function parseTextFileList(text) {
    const clips = [];
    const seen = new Set();

    // Match DDPAI-style filenames: 20260626170956_0060.mp4 or 20260626170956_F.mp4
    const regex = /(\d{14}[_\-][^\s"'<>]+\.mp4)/gi;
    const matches = text.match(regex) || [];
    for (const m of matches) {
      const name = m.split('/').pop();
      if (!seen.has(name)) {
        seen.add(name);
        clips.push(parseFileName(name, 0));
      }
    }

    // Also match any .mp4 filename patterns
    if (clips.length === 0) {
      const broadRegex = /([A-Za-z0-9_\-]+\.mp4)/gi;
      const broad = text.match(broadRegex) || [];
      for (const m of broad) {
        const name = m.split('/').pop();
        if (!seen.has(name) && name.length > 5) {
          seen.add(name);
          clips.push(parseFileName(name, 0));
        }
      }
    }

    return clips;
  }

  function parseFileName(fileName, sizeBytes, basePath) {
    const nameOnly = fileName.split('/').pop();
    let date = '', time = '', duration = 60;

    // YYYYMMDDHHMMSS pattern
    const tsMatch = nameOnly.match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})/);
    if (tsMatch) {
      date = `${tsMatch[1]}-${tsMatch[2]}-${tsMatch[3]}`;
      time = `${tsMatch[4]}:${tsMatch[5]}:${tsMatch[6]}`;
    }

    const durMatch = nameOnly.match(/_(\d{4})\./);
    if (durMatch) {
      duration = parseInt(durMatch[1], 10);
    }

    // Build the full path for downloading
    let fullPath = nameOnly;
    if (basePath && basePath !== '/') {
      fullPath = basePath.replace(/\/$/, '') + '/' + nameOnly;
    }

    return {
      fileName: nameOnly,
      fullPath,
      date,
      time,
      duration,
      size: sizeBytes,
    };
  }

  /* ─────────────────────────────────────────────────
     Init
     ───────────────────────────────────────────────── */
  function init() {
    cacheDom();
    loadState();
    DOM.ipInput.value = state.dashcamIp;
    updatePathDisplay();
    updateToolbar();
    updateStats();

    // Show connection mode
    if (isServedViaServer()) {
      log('✅ Running via server.js — API calls will be proxied (no CORS issues).');
      setStatus('', 'Ready (proxied)');
    } else {
      log('⚠ Running as local file — API calls may be blocked by CORS.');
      log('💡 Tip: Run "node server.js" and open http://localhost:8080 instead.');
      setStatus('', 'Ready (direct — CORS risk)');
    }

    // Event listeners
    DOM.btnScan.addEventListener('click', scan);
    DOM.btnSelectAll.addEventListener('click', toggleSelectAll);
    DOM.btnBatch.addEventListener('click', batchDownload);
    DOM.btnChoosePath.addEventListener('click', choosePath);
    DOM.btnModalClose.addEventListener('click', closeStream);
    DOM.batchCancel.addEventListener('click', cancelBatch);
    DOM.modalOverlay.addEventListener('click', (e) => {
      if (e.target === DOM.modalOverlay) closeStream();
    });
    DOM.ipInput.addEventListener('change', () => {
      state.dashcamIp = DOM.ipInput.value.trim();
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
        if (parsed.ip) state.dashcamIp = parsed.ip;
        if (parsed.downloaded) state.downloaded = new Set(parsed.downloaded);
        if (parsed.downloadPath) state.downloadPath = parsed.downloadPath;
      }
    } catch { /* ignore */ }
  }

  function saveState() {
    try {
      localStorage.setItem('udash_state', JSON.stringify({
        ip: state.dashcamIp,
        downloaded: [...state.downloaded],
        downloadPath: state.downloadPath,
      }));
    } catch { /* ignore */ }
  }

  /* ─────────────────────────────────────────────────
     Scan
     ───────────────────────────────────────────────── */
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
      // Step 1: Request session (may or may not be needed)
      await requestSession();

      // Step 2: Fetch file list using multiple strategies
      const clips = await fetchFileList();

      // Sort by date descending (newest first)
      clips.sort((a, b) => b.fileName.localeCompare(a.fileName));

      state.clips = clips;
      setStatus('connected', `${clips.length} clips found`);
      toast('success', `Found ${clips.length} video clips on dashcam.`);
    } catch (err) {
      setStatus('', 'Scan failed');
      log(`❌ Scan failed: ${err.message}`);
      if (!isServedViaServer()) {
        toast('error', 'Scan failed (CORS blocked). Run "node server.js" and open http://localhost:8080');
      } else {
        toast('error', `Scan failed: ${err.message}`);
      }
    } finally {
      state.scanning = false;
      DOM.btnScan.disabled = false;
      DOM.btnScan.innerHTML = '🔍 Scan';
      renderGrid();
      updateStats();
      updateToolbar();
    }
  }

  /* ─────────────────────────────────────────────────
     Render
     ───────────────────────────────────────────────── */
  function renderGrid() {
    if (state.clips.length === 0 && !state.scanning) {
      DOM.grid.innerHTML = `
        <div class="empty-state">
          <div class="empty-icon">📹</div>
          <h3>No clips loaded</h3>
          <p>Connect this PC to dashcam WiFi (<strong>DDPAI_A1</strong>), then press <strong>Scan</strong>.</p>
          <p style="margin-top:8px;font-size:0.8rem;color:var(--text-muted)">
            Run <code>node server.js</code> and open <code>http://localhost:8080</code>
          </p>
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
            <button class="btn btn-stream" onclick="window.__udash.stream('${clip.fullPath}', '${clip.fileName}')">
              ▶ Stream
            </button>
            ${isDownloaded
              ? `<button class="btn btn-downloaded" disabled>✓ Downloaded</button>`
              : `<button class="btn btn-download" onclick="window.__udash.download('${clip.fullPath}', '${clip.fileName}')">
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

  /* ─────────────────────────────────────────────────
     Stream — uses <video> element (no CORS needed)
     ───────────────────────────────────────────────── */
  function stream(fullPath, fileName) {
    // <video> elements bypass CORS — use direct dashcam URL
    const url = dashcamDirectUrl(fullPath);
    log(`Streaming: ${url}`);
    DOM.modalTitle.textContent = fileName;
    DOM.modalVideo.src = url;
    DOM.modalOverlay.classList.add('active');
    DOM.modalVideo.play().catch((e) => {
      log(`Playback error: ${e.message}`);
      // Fallback: try via proxy
      const proxyU = proxyUrl(fullPath);
      log(`Retrying via proxy: ${proxyU}`);
      DOM.modalVideo.src = proxyU;
      DOM.modalVideo.play().catch(() => {
        toast('warning', 'Could not stream. Ensure PC is connected to dashcam WiFi.');
      });
    });
  }

  function closeStream() {
    DOM.modalOverlay.classList.remove('active');
    DOM.modalVideo.pause();
    DOM.modalVideo.removeAttribute('src');
    DOM.modalVideo.load(); // Release resources
  }

  /* ─────────────────────────────────────────────────
     Download
     ───────────────────────────────────────────────── */
  async function download(fullPath, fileName) {
    try {
      log(`Downloading: ${fileName}`);

      if (state.dirHandle) {
        // File System Access API — download via proxy to custom folder
        toast('info', `Downloading ${fileName}…`);
        const url = proxyUrl(fullPath);
        const resp = await fetch(url);
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        const blob = await resp.blob();
        const fileHandle = await state.dirHandle.getFileHandle(fileName, { create: true });
        const writable = await fileHandle.createWritable();
        await writable.write(blob);
        await writable.close();
        log(`✅ Saved to custom folder: ${fileName}`);
      } else {
        // Standard browser download via hidden anchor
        // Try proxy URL first (more reliable), fallback to direct
        const url = isServedViaServer() ? proxyUrl(fullPath) : dashcamDirectUrl(fullPath);
        const a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        log(`📥 Download triggered: ${fileName}`);
      }

      state.downloaded.add(fileName);
      saveState();
      renderGrid();
      updateStats();
      toast('success', `Downloaded: ${fileName}`);
    } catch (err) {
      log(`❌ Download failed: ${err.message}`);
      toast('error', `Download failed: ${err.message}`);
    }
  }

  /* ── Batch Download ── */
  let batchCancelled = false;

  async function batchDownload() {
    const selectedClips = state.clips.filter(c =>
      state.selected.has(c.fileName) && !state.downloaded.has(c.fileName)
    );
    if (selectedClips.length === 0) {
      toast('warning', 'No undownloaded files selected.');
      return;
    }

    batchCancelled = false;
    state.batchActive = true;
    DOM.batchOverlay.classList.add('active');
    DOM.batchSubtitle.textContent = `0 of ${selectedClips.length} completed`;
    DOM.batchFill.style.width = '0%';

    for (let i = 0; i < selectedClips.length; i++) {
      if (batchCancelled) break;

      const clip = selectedClips[i];
      DOM.batchCurrent.textContent = clip.fileName;
      DOM.batchSubtitle.textContent = `${i} of ${selectedClips.length} completed`;
      DOM.batchFill.style.width = `${(i / selectedClips.length) * 100}%`;

      try {
        await download(clip.fullPath, clip.fileName);
      } catch { /* individual errors already handled */ }

      // Small delay between downloads to avoid overwhelming the dashcam
      await new Promise(r => setTimeout(r, 300));
    }

    DOM.batchFill.style.width = '100%';
    DOM.batchSubtitle.textContent = batchCancelled
      ? 'Batch cancelled.'
      : `${selectedClips.length} of ${selectedClips.length} completed`;

    setTimeout(() => {
      DOM.batchOverlay.classList.remove('active');
      state.batchActive = false;
      state.selected.clear();
      renderGrid();
      updateStats();
      updateToolbar();
    }, 1200);

    if (!batchCancelled) {
      toast('success', `Batch download complete: ${selectedClips.length} files.`);
    }
  }

  function cancelBatch() {
    batchCancelled = true;
    toast('warning', 'Batch download cancelled.');
  }

  /* ── Choose Download Path (Chromium File System Access API) ── */
  async function choosePath() {
    if (!window.showDirectoryPicker) {
      toast('warning', 'Custom download paths require Chrome or Edge browser.');
      return;
    }
    try {
      state.dirHandle = await window.showDirectoryPicker({ mode: 'readwrite' });
      state.downloadPath = state.dirHandle.name;
      saveState();
      updatePathDisplay();
      toast('success', `Download path: ${state.dirHandle.name}`);
    } catch (err) {
      if (err.name !== 'AbortError') {
        toast('error', `Could not set path: ${err.message}`);
      }
    }
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

    const undownloaded = [...state.selected].filter(f => !state.downloaded.has(f)).length;
    DOM.btnBatch.innerHTML = hasSelection
      ? `📥 Batch Download <span class="badge">${undownloaded}</span>`
      : '📥 Batch Download';

    DOM.btnSelectAll.textContent = (state.selected.size === state.clips.length && hasClips)
      ? '☑ Deselect All'
      : '☐ Select All';
  }

  function updatePathDisplay() {
    DOM.pathDisplay.innerHTML = `<span class="icon">📁</span> ${state.downloadPath}`;
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
    }, 4500);
  }

  /* ── Public API for onclick handlers ── */
  window.__udash = { toggleSelect, stream, download };

  /* ── Boot ── */
  document.addEventListener('DOMContentLoaded', init);

})();
