/* ============================================================
   uDash — DDPAI CORS Proxy
   Lightweight reverse proxy to bypass browser CORS restrictions.

   Usage:
     node proxy.js
     node proxy.js 3000 193.168.0.1

   The webapp connects to http://localhost:<port>/api/<path>
   and this proxy forwards it to http://<dashcamIP>/<path>.
   ============================================================ */

const http = require('http');
const url = require('url');

const PORT = parseInt(process.argv[2], 10) || 3000;
const DASHCAM_IP = process.argv[3] || '193.168.0.1';

const server = http.createServer((req, res) => {
  // CORS headers for every response
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', '*');

  // Handle preflight
  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // Only proxy /api/* paths
  const parsed = url.parse(req.url);
  const apiPrefix = '/api';
  if (!parsed.pathname.startsWith(apiPrefix)) {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not found. Use /api/<path> to proxy to dashcam.');
    return;
  }

  const targetPath = parsed.pathname.slice(apiPrefix.length) + (parsed.search || '');
  const targetUrl = `http://${DASHCAM_IP}${targetPath}`;

  console.log(`[Proxy] ${req.method} ${req.url}  →  ${targetUrl}`);

  const proxyReq = http.request(targetUrl, { method: req.method }, (proxyRes) => {
    // Forward dashcam headers + override CORS
    const headers = { ...proxyRes.headers };
    headers['access-control-allow-origin'] = '*';
    res.writeHead(proxyRes.statusCode, headers);
    proxyRes.pipe(res);
  });

  proxyReq.on('error', (err) => {
    console.error(`[Proxy] Error: ${err.message}`);
    res.writeHead(502, { 'Content-Type': 'text/plain' });
    res.end(`Proxy error: ${err.message}`);
  });

  // Set a timeout for dashcam connections
  proxyReq.setTimeout(15000, () => {
    proxyReq.destroy();
    res.writeHead(504, { 'Content-Type': 'text/plain' });
    res.end('Gateway timeout: dashcam did not respond.');
  });

  req.pipe(proxyReq);
});

server.listen(PORT, () => {
  console.log(`
╔══════════════════════════════════════════════════════╗
║  uDash CORS Proxy                                   ║
║  Listening on:  http://localhost:${String(PORT).padEnd(5)}                ║
║  Forwarding to: http://${DASHCAM_IP.padEnd(15)}               ║
║                                                      ║
║  Enable "Proxy: ON" in the webapp to use this proxy. ║
╚══════════════════════════════════════════════════════╝
`);
});
