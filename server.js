/* ============================================================
   uDash — Unified Web Server + DDPAI Reverse Proxy
   
   Serves the webapp on localhost AND proxies API calls to the
   dashcam, completely bypassing CORS browser restrictions.

   Usage:
     node server.js                    # defaults: port 8080, dashcam 193.168.0.1
     node server.js 8080 193.168.0.1   # explicit port and dashcam IP

   Then open: http://localhost:8080
   ============================================================ */

const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');

const PORT = parseInt(process.argv[2], 10) || 8080;
const DASHCAM_IP = process.argv[3] || '193.168.0.1';

/* ── MIME types for static files ── */
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css':  'text/css; charset=utf-8',
  '.js':   'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png':  'image/png',
  '.jpg':  'image/jpeg',
  '.svg':  'image/svg+xml',
  '.ico':  'image/x-icon',
  '.mp4':  'video/mp4',
};

const STATIC_DIR = __dirname;

const server = http.createServer((req, res) => {
  const parsed = url.parse(req.url, true);
  const pathname = parsed.pathname;

  /* ── Proxy: /api/* → dashcam ── */
  if (pathname.startsWith('/api/')) {
    const targetPath = pathname.slice(4) + (parsed.search || '');  // strip "/api"
    const targetUrl = `http://${DASHCAM_IP}${targetPath}`;

    const ts = new Date().toLocaleTimeString();
    console.log(`[${ts}] PROXY  ${req.method} ${req.url}  →  ${targetUrl}`);

    const proxyOpts = {
      hostname: DASHCAM_IP,
      port: 80,
      path: targetPath,
      method: req.method,
      headers: { ...req.headers, host: DASHCAM_IP },
    };

    const proxyReq = http.request(proxyOpts, (proxyRes) => {
      // Copy dashcam response headers and inject CORS
      const headers = { ...proxyRes.headers };
      headers['access-control-allow-origin'] = '*';
      headers['access-control-allow-methods'] = 'GET, POST, OPTIONS';
      headers['access-control-allow-headers'] = '*';
      res.writeHead(proxyRes.statusCode, headers);
      proxyRes.pipe(res);
    });

    proxyReq.on('error', (err) => {
      console.error(`[PROXY ERROR] ${err.message}`);
      res.writeHead(502, {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
      });
      res.end(JSON.stringify({
        error: true,
        message: `Cannot reach dashcam at ${DASHCAM_IP}: ${err.message}`,
        hint: 'Ensure this computer is connected to the dashcam WiFi (SSID: DDPAI_A1)',
      }));
    });

    proxyReq.setTimeout(15000, () => {
      proxyReq.destroy();
      res.writeHead(504, {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*',
      });
      res.end(JSON.stringify({
        error: true,
        message: 'Gateway timeout: dashcam did not respond within 15 seconds.',
      }));
    });

    req.pipe(proxyReq);
    return;
  }

  /* ── CORS preflight for /api/ ── */
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': '*',
    });
    res.end();
    return;
  }

  /* ── Static file server ── */
  let filePath = pathname === '/' ? '/index.html' : pathname;
  filePath = path.join(STATIC_DIR, filePath);

  // Security: prevent path traversal
  if (!filePath.startsWith(STATIC_DIR)) {
    res.writeHead(403, { 'Content-Type': 'text/plain' });
    res.end('Forbidden');
    return;
  }

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Not found');
      return;
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME[ext] || 'application/octet-stream';

    res.writeHead(200, { 'Content-Type': contentType });
    fs.createReadStream(filePath).pipe(res);
  });
});

server.listen(PORT, '0.0.0.0', () => {
  // Detect local IPs for phone access
  const os = require('os');
  const nets = os.networkInterfaces();
  const localIPs = [];
  for (const iface of Object.values(nets)) {
    for (const cfg of iface) {
      if (cfg.family === 'IPv4' && !cfg.internal) {
        localIPs.push(cfg.address);
      }
    }
  }
  const phoneUrl = localIPs.length > 0
    ? `http://${localIPs[0]}:${PORT}`
    : '(connect to dashcam WiFi first)';

  console.log(`
╔══════════════════════════════════════════════════════════════╗
║                                                              ║
║   🎥  uDash — DDPAI Dashcam Web Manager                     ║
║                                                              ║
║   Laptop:     http://localhost:${String(PORT).padEnd(5)}                         ║
║   Phone:      ${phoneUrl.padEnd(45)}║
║   Dashcam IP: ${DASHCAM_IP.padEnd(15)}                                ║
║   Proxy:      /api/* → http://${DASHCAM_IP.padEnd(15)}              ║
║                                                              ║
║   1. Connect laptop + phone to dashcam WiFi (DDPAI_A1)      ║
║   2. On laptop:  http://localhost:${String(PORT).padEnd(5)}                     ║
║   3. On phone:   ${phoneUrl.padEnd(41)}║
║   4. Click "Scan" to discover video clips                    ║
║                                                              ║
╚══════════════════════════════════════════════════════════════╝
`);
});

