'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { request } = require('../lib/gateway.cjs');

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
