'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { setTimeout: delay } = require('node:timers/promises');

async function fixture(t) {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'mobile-use-remote-'));
  const token = 'a'.repeat(64), tokenFile = path.join(temp, 'token');
  fs.writeFileSync(tokenFile, token, { mode: 0o600 });
  const state = { owner: '', mode: 'idle', display: -1, events: [], questions: [], settlements: [], actions: 0, command: '', renewals: 0 };
  const server = http.createServer(async (req, res) => {
    let bytes = '';for await (const chunk of req) bytes += chunk;
    const body = bytes ? JSON.parse(bytes) : {};
    const url = new URL(req.url, 'http://localhost'), owner = req.headers['x-mobile-use-control'];
    const reply = (v, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' });res.end(JSON.stringify(v)); };
    if (req.headers.authorization !== 'Bearer ' + token) return reply({}, 401);
    if (url.pathname === '/api/control/acquire') {
      if (state.owner && state.owner !== owner) return reply({}, 409);
      state.owner = owner;return reply({ success: true, ttl_seconds: 30 });
    }
    if (url.pathname.startsWith('/api/control/')) {
      if (owner !== state.owner) return reply({}, 409);
      if (url.pathname.endsWith('/release')) { state.owner = '';state.display = -1;state.mode = 'idle';return reply({ success: true, released: true }); }
      state.renewals++;return reply({ success: true });
    }
    if (owner && owner !== state.owner) return reply({}, 409);
    switch (url.pathname) {
      case '/api/live/event': state.events.push(body);return reply({ success: true, accepted: true });
      case '/api/live/commands': return reply({ success: true, command: state.command });
      case '/api/mode': state.mode = url.searchParams.get('mode');return reply({ success: true, mode: state.mode });
      case '/api/start': state.display = 8;return reply({ success: true, display_id: 8 });
      case '/api/stop': state.display = -1;return reply({ status: 'stopped' });
      case '/api/action': state.actions++;return reply({ success: true });
      case '/api/screenshot': res.writeHead(200);return res.end(Buffer.from([0xff, 0xd8, 0xff, 0xd9]));
      case '/api/question':
        if (req.method === 'POST') { state.questions.push(body);return reply({ success: true }); }
        return reply(state.answer || { state: 'open' });
      case '/api/question/settle': state.settlements.push(body);return reply({ success: true });
      default: return reply({ success: true, mode: state.mode, display_id: state.display });
    }
  });
  server.listen(0, '127.0.0.1');await once(server, 'listening');
  const children = [];
  function start(entry = process.env.MOBILE_USE_REMOTE_TEST_ENTRY || require.resolve('../lib/remote-cli.cjs'), extraArgs = []) {
    const child = spawn(process.execPath, [entry, '--endpoint', `http://127.0.0.1:${server.address().port}`, '--token-file', tokenFile, ...extraArgs], { stdio: ['pipe', 'pipe', 'pipe'] });
    children.push(child);
    let buffer = '', stderr = '', seq = 0;
    const waiting = new Map();
    child.stderr.on('data', chunk => { stderr += chunk; });
    child.stdout.on('data', chunk => {
      buffer += chunk;
      let p;
      while ((p = buffer.indexOf('\n')) !== -1) {
        const value = JSON.parse(buffer.slice(0, p));buffer = buffer.slice(p + 1);
        waiting.get(value.id)?.(value);waiting.delete(value.id);
      }
    });
    const send = value => child.stdin.write(JSON.stringify({ jsonrpc: '2.0', ...value }) + '\n');
    const rpc = (method, params) => {
      const id = ++seq;
      const promise = new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('MCP timeout; ' + stderr)), 8000);
        waiting.set(id, v => { clearTimeout(timeout);resolve(v); });
      });
      send({ id, method, params });
      promise.id = id;
      return promise;
    };
    return { child, rpc, send, stderr: () => stderr, call: (name, args = {}) => rpc('tools/call', { name, arguments: args }) };
  }
  t.after(async () => {
    for (const child of children) if (child.exitCode === null) { child.kill();await once(child, 'exit'); }
    server.closeAllConnections();server.close();
    assert.ok(temp.startsWith(path.join(os.tmpdir(), 'mobile-use-remote-')));
    fs.rmSync(temp, { recursive: true, force: true });
  });
  return { state, start };
}
const text = response => JSON.parse(response.result.content[0].text);
async function until(predicate) {
  for (let i = 0; i < 200; i++) { if (predicate()) return;await delay(20); }
  assert.fail('state did not arrive');
}

test('portable MCP real stdio: task, screen, phone answer, completion and exclusive owner', async t => {
  const { state, start } = await fixture(t), a = start(), b = start();
  assert.equal((await a.rpc('initialize', { protocolVersion: '2025-11-25' })).result.serverInfo.name, 'agent-mobile-use');
  const listed = (await a.rpc('tools/list')).result.tools;
  assert.equal(listed.length, 17);assert.ok(listed.some(t => t.name === 'mobile_ask'));
  assert.equal((await a.call('mobile_action', { action: 'click', coordinate: [1, 2] })).result.isError, true);
  assert.equal(state.actions, 0);
  assert.equal(text(await a.call('mobile_task_begin', { title: '后台只读任务' })).success, true);
  assert.equal((await b.call('mobile_task_begin', { title: '不能抢占' })).result.isError, true);
  await a.call('mobile_start');await a.call('mobile_mode', { mode: 'background' });
  assert.equal((await a.call('mobile_screenshot')).result.content[1].type, 'image');
  await a.call('mobile_task_update', { total: 2, completed: 1, detail: '已打开设置' });
  const question = a.call('mobile_ask', { questions: [{ id: 'next', question: '继续查看？', options: [{ label: '继续' }, { label: '停止' }] }] });
  await until(() => state.questions.length === 1);
  assert.equal(state.events.at(-1).phase, 'waiting');
  state.answer = { state: 'submitted', answers: [{ id: 'next', selected: ['继续'] }] };
  assert.deepEqual(text(await question).answers, [{ id: 'next', selected: ['继续'] }]);
  await until(() => state.settlements.length === 1 && state.events.at(-1).phase === 'background');
  assert.equal(state.settlements[0].answer_source, 'island');
  assert.equal(state.settlements[0].request_id, state.questions[0].request_id);
  const done = text(await a.call('mobile_task_end', { outcome: 'completed', detail: '读屏核验完成' }));
  assert.equal(done.cleanup_pending, false);
  assert.equal(state.display, -1);assert.equal(state.mode, 'idle');assert.equal(state.owner, '');
  assert.equal(state.events.at(-1).phase, 'completed');
  assert.equal(text(await b.call('mobile_task_begin', { title: '另一台设备' })).success, true);
  assert.equal(state.events.every((e, i, events) => !i || e.session_id !== events[i - 1].session_id || e.sequence > events[i - 1].sequence), true);
});

test('phone stop cancels pending ask and blocks later actions; stdin EOF cleans up', async t => {
  const { state, start } = await fixture(t), a = start();
  await a.call('mobile_task_begin', { title: '测试停止' });
  const previousOwner = state.owner;
  await a.call('mobile_start');
  const pending = a.call('mobile_ask', { questions: [{ id: 'q', question: '等待回答' }] });
  await until(() => state.questions.length === 1);
  state.command = 'stop';
  assert.equal((await pending).result.isError, true);
  await until(() => !state.owner);
  assert.equal(state.events.at(-1).phase, 'cancelled');
  assert.equal((await a.call('mobile_action', { action: 'click', coordinate: [1, 2] })).result.isError, true);
  state.command = '';
  await a.call('mobile_task_begin', { title: '测试断开' });await a.call('mobile_start');
  assert.notEqual(state.owner, previousOwner, 'new task must reject delayed actions from the previous lease');
  a.child.stdin.end();await once(a.child, 'exit');
  assert.equal(state.owner, '');assert.equal(state.display, -1);assert.equal(state.events.at(-1).phase, 'cancelled');
  assert.equal(a.stderr(), '');
});

test('MCP cancellation settles question and releases task; invalid arguments never reach phone', async t => {
  const { state, start } = await fixture(t), a = start();
  await a.call('mobile_task_begin', { title: '取消调用' });
  const eventCount = state.events.length;
  for (const [name, args] of [
    ['mobile_task_update', { total: -1 }],
    ['mobile_ask', { questions: [{ id: 'q', question: '选择' }], timeout_seconds: 4 }],
    ['mobile_ask', { questions: [{ id: 'q', question: '选择' }], timeout_seconds: 601 }],
  ]) assert.equal((await a.call(name, args)).result.isError, true);
  assert.equal(state.events.length, eventCount, 'runtime bounds remain enforced after schema compatibility conversion');
  assert.equal(state.questions.length, 0);
  const invalid = await a.call('mobile_ask', { questions: [{ id: 'q', question: '选择', options: [{ label: '重复' }, { label: '重复' }] }] });
  assert.equal(invalid.result.isError, true);assert.equal(state.questions.length, 0);
  const pending = a.call('mobile_ask', { questions: [{ id: 'q', question: '选择' }] });
  await until(() => state.questions.length === 1);
  a.send({ method: 'notifications/cancelled', params: { requestId: pending.id } });
  assert.equal((await pending).error.code, -32800);
  assert.equal(state.settlements.at(-1).state, 'cancelled');assert.equal(state.owner, '');
});

test('lease and live heartbeat remain active while Agent is thinking', async t => {
  const { state, start } = await fixture(t), a = start();
  await a.call('mobile_task_begin', { title: '思考期间保活' });
  await delay(11000);
  assert.ok(state.renewals >= 1);assert.ok(state.events.length >= 2);assert.equal(state.events.at(-1).phase, 'running');
  await a.call('mobile_task_end', { outcome: 'cancelled' });
});

test('CLI rejects non-loopback endpoints without printing token', async t => {
  const { start } = await fixture(t), a = start(undefined, ['--endpoint', 'http://192.0.2.1:3070']);
  await once(a.child, 'exit');
  assert.equal(a.child.exitCode, 1);assert.equal(a.stderr(), 'ENDPOINT_REQUIRES_LOOPBACK_SSH_FORWARD\n');
});
