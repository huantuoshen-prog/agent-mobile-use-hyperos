'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { request, client } = require('../lib/gateway.cjs');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

test('显式凭据路径用于宿主生命周期请求，并拒绝向非回环地址发送凭据', async t => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mobile-use-token-'));
  const tokenFile = path.join(dir, 'token');
  fs.writeFileSync(tokenFile, 'a'.repeat(64), { mode: 0o600 });
  const server = http.createServer((req, res) => {
    assert.equal(req.headers.authorization, 'Bearer ' + 'a'.repeat(64));
    res.writeHead(200, { 'Content-Type': 'application/json' });res.end('{"success":true}');
  });
  server.listen(0, '127.0.0.1');await once(server, 'listening');
  t.after(() => { server.closeAllConnections();server.close();fs.rmSync(dir, { recursive: true, force: true }); });
  const post = client(`http://127.0.0.1:${server.address().port}`, { tokenFile });
  assert.equal((await post('/api/integration', {})).success, true);
  await assert.rejects(request('http://192.0.2.1:3070/api/action', { tokenFile }), /ENDPOINT_REQUIRES_LOOPBACK_SSH_FORWARD/);
});

test('响应头后的响应体同样受到截止保护', async () => {
  const server = http.createServer((req, res) => { res.writeHead(200);res.flushHeaders(); });
  server.listen(0, '127.0.0.1');await once(server, 'listening');
  try { await assert.rejects(request(`http://127.0.0.1:${server.address().port}`, { timeoutMs: 50 })); }
  finally { server.closeAllConnections();server.close(); }
});

test('排队点击取消后不发送动作，raw 读屏返回文本', async () => {
  const actions = [];
  const server = http.createServer((req, res) => {
    if (req.url === '/api/action') actions.push(req.url);
    if (req.url.includes('raw=1')) { res.writeHead(200, { 'Content-Type': 'text/plain' });return res.end('ok display=2\n# columns'); }
    setTimeout(() => { res.writeHead(200, { 'Content-Type': 'application/json' });res.end('{"success":true}'); }, 150);
  });
  server.listen(0, '127.0.0.1');await once(server, 'listening');
  const child = spawn(process.execPath, [require.resolve('../lib/server.cjs')], {
    env: { ...process.env, MOBILE_USE_BASE: `http://127.0.0.1:${server.address().port}`, MOBILE_USE_TOKEN_FILE: 'NONEXISTENT_TEST_TOKEN' }, stdio: ['pipe', 'pipe', 'pipe'],
  });
  const replies = [], waiters = new Map();let buffer = '';
  child.stdout.on('data', bytes => {
    buffer += bytes;
    let pos;
    while ((pos = buffer.indexOf('\n')) !== -1) {
      const value = JSON.parse(buffer.slice(0, pos));buffer = buffer.slice(pos + 1);
      replies.push(value);waiters.get(value.id)?.(value);
    }
  });
  const send = value => child.stdin.write(JSON.stringify({ jsonrpc: '2.0', ...value }) + '\n');
  const reply = id => new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('test reply timeout')), 3000);
    waiters.set(id, value => { clearTimeout(timer);resolve(value); });
  });
  try {
    const a = reply(1), b = reply(2), c = reply(3);
    send({ id: 1, method: 'tools/call', params: { name: 'mobile_status', arguments: {} } });
    send({ id: 2, method: 'tools/call', params: { name: 'mobile_action', arguments: { action: 'click', coordinate: [1, 2] } } });
    send({ method: 'notifications/cancelled', params: { requestId: 2 } });
    send({ id: 3, method: 'tools/call', params: { name: 'mobile_ui_dump', arguments: { raw: true } } });
    await a;assert.equal((await b).error.code, -32800);
    const raw = (await c).result;assert.equal(raw.isError, false);assert.match(raw.content[0].text, /^ok display=2/);
    assert.equal(actions.length, 0);
  } finally { child.kill();server.closeAllConnections();server.close(); }
});
