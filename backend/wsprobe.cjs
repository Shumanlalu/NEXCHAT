const WebSocket = require('ws');
const ws = new WebSocket('wss://nexchat-three.vercel.app/v1/ws', { headers: { Authorization: 'Bearer invalid' } });
const timer = setTimeout(() => { console.log('TIMEOUT'); process.exit(0); }, 15000);
ws.on('open', () => { console.log('OPEN'); ws.close(); });
ws.on('unexpected-response', (req, res) => { console.log('HTTP', res.statusCode, res.statusMessage); res.resume(); clearTimeout(timer); process.exit(0); });
ws.on('error', (e) => { console.log('ERROR', e.message); });
ws.on('close', (c, r) => { console.log('CLOSE', c, r.toString()); clearTimeout(timer); process.exit(0); });
