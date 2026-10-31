import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath, pathToFileURL } from 'node:url';

// Exercise the bundle entry and real MCP subprocess with DSH-style env scrubbing.
async function fixture(t) {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'mobile-use-bundle-'));
  const plugin = path.join(temp, 'plugin');
  const source = fileURLToPath(new URL('../lib', import.meta.url));
  fs.mkdirSync(plugin);
  fs.cpSync(source, path.join(plugin, 'lib'), { recursive: true });
  fs.writeFileSync(path.join(plugin, 'package.json'), '{"type":"module"}');
  const peer = path.join(plugin, 'node_modules', '@deepseek-ai', 'dsh-mcp-client');
  fs.mkdirSync(peer, { recursive: true });
  fs.writeFileSync(path.join(peer, 'package.json'), '{"type":"module","exports":"./index.js"}');
  fs.writeFileSync(path.join(peer, 'index.js'), 'export const Config = config => config;');
  const tokenFile = path.join(temp, 'token');
  fs.writeFileSync(tokenFile, 'a'.repeat(64), { mode: 0o600 });
  const state = { owner: '', requests: [], tools: [], prompts: [] };
  const server = http.createServer(async (req, res) => {
    for await (const chunk of req) void chunk;
    const url = new URL(req.url, 'http://127.0.0.1');
    const authorized = req.headers.authorization === 'Bearer ' + 'a'.repeat(64);
    const owner = req.headers['x-mobile-use-control'];
    state.requests.push({ path: url.pathname, authorized, hasOwner: Boolean(owner) });
    const reply = (value, status = 200) => { res.writeHead(status, { 'content-type': 'application/json' });res.end(JSON.stringify(value)); };
    if (!authorized) return reply({}, 401);
    if (url.pathname === '/api/control/acquire') { state.owner = owner;return reply({ success: true }); }
    if (url.pathname === '/api/control/release') {
      if (state.owner !== owner) return reply({}, 409);
      state.owner = '';return reply({ success: true, released: true });
    }
    if (url.pathname === '/api/live/event') return reply({ success: true, accepted: true });
    if (url.pathname === '/api/live/commands') return reply({ success: true, command: '' });
    return reply({ success: true, status: 'ok', display_id: 8 });
  });
  server.listen(0, '127.0.0.1');await once(server, 'listening');
  const endpoint = `http://127.0.0.1:${server.address().port}`;
  const disposers = [], handlers = [];
  let child, rpc, stderr = '';
  const ctx = {
    tools: { schemas: () => state.tools.map(tool => ({ name: 'mcp__mobile-use__' + tool.name })) },
    effect(generator) { disposers.push(generator().next().value); },
    on(name, fn) { if (name === 'dispose') handlers.push(fn); },
    inject(_services, fn) { fn({ systemPrompt: { section: section => state.prompts.push(section.text) } }); },
    plugin(_mcp, config) {
      const scrubbed = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/KEY|PASSWORD|SECRET|TOKEN|^DSH_/i.test(key)));
      child = spawn(config.command, config.args, { env: { ...scrubbed, ...config.env }, stdio: ['pipe', 'pipe', 'pipe'] });
      let buffer = '', seq = 0;
      const pending = new Map();
      child.stderr.on('data', chunk => { stderr += chunk; });
      child.stdout.on('data', chunk => {
        buffer += chunk;
        let pos;
        while ((pos = buffer.indexOf('\n')) !== -1) {
          const value = JSON.parse(buffer.slice(0, pos));buffer = buffer.slice(pos + 1);
          pending.get(value.id)?.(value);pending.delete(value.id);
        }
      });
      rpc = (method, params) => new Promise((resolve, reject) => {
        const id = ++seq;
        const timer = setTimeout(() => reject(new Error('MCP timeout: ' + stderr)), 5000);
        pending.set(id, value => { clearTimeout(timer);resolve(value); });
        child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
      });
      return {
        async await() {
          await rpc('initialize', { protocolVersion: '2025-11-25' });
          state.tools = (await rpc('tools/list', {})).result.tools;
        },
        dispose() { child.stdin.end(); },
      };
    },
  };
  t.after(async () => {
    for (const handler of handlers) handler();
    for (const dispose of disposers) dispose();
    if (child && child.exitCode === null) {
      const exit = once(child, 'exit');
      const timer = setTimeout(() => child.kill(), 3000);
      await exit;clearTimeout(timer);
    }
    server.closeAllConnections();server.close();
    fs.rmSync(temp, { recursive: true, force: true });
  });
  const bundle = await import(pathToFileURL(path.join(plugin, 'lib', 'index.js')).href);
  return { bundle, ctx, state, endpoint, tokenFile, call: async (name, args = {}) => (await rpc('tools/call', { name, arguments: args })).result };
}

test('通用 DSH bundle 将配置交给 MCP 子进程并完成带租约的受保护调用', async t => {
  const f = await fixture(t);
  await f.bundle.apply(f.ctx, { mode: 'remote', endpoint: f.endpoint, tokenFile: f.tokenFile });
  assert.equal(f.state.tools.length, 17);
  assert.equal((await f.call('mobile_status')).isError, false);
  assert.equal((await f.call('mobile_task_begin', { title: 'bundle test' })).isError, false);
  assert.equal((await f.call('mobile_start')).isError, false);
  assert.equal((await f.call('mobile_task_end', { outcome: 'completed' })).isError, false);
  assert.equal(f.state.owner, '');
  assert.ok(f.state.requests.some(x => x.path === '/api/control/acquire' && x.authorized && x.hasOwner));
  assert.ok(f.state.requests.some(x => x.path === '/api/start' && x.authorized && x.hasOwner));
  assert.ok(f.state.requests.some(x => x.path === '/api/control/release' && x.authorized && x.hasOwner));
  assert.match(f.state.prompts[0], /mobile_task_begin/);
});

test('DSHA 模式保留工具，并把被宿主过滤的凭据路径交给子进程', async t => {
  const f = await fixture(t);
  const previous = [process.env.MOBILE_USE_MODE, process.env.MOBILE_USE_BASE, process.env.MOBILE_USE_TOKEN_FILE];
  process.env.MOBILE_USE_MODE = 'dsha';process.env.MOBILE_USE_BASE = f.endpoint;process.env.MOBILE_USE_TOKEN_FILE = f.tokenFile;
  t.after(() => {
    for (const [i, name] of ['MOBILE_USE_MODE', 'MOBILE_USE_BASE', 'MOBILE_USE_TOKEN_FILE'].entries()) {
      if (previous[i] === undefined) delete process.env[name];else process.env[name] = previous[i];
    }
  });
  await f.bundle.apply(f.ctx);
  assert.equal(f.state.tools.length, 16);
  assert.equal((await f.call('mobile_start')).isError, false);
  assert.ok(f.state.requests.some(x => x.path === '/api/start' && x.authorized && !x.hasOwner));
  assert.match(f.state.prompts[0], /ask_user_question/);
});
