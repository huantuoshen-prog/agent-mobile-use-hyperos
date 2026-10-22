import test from 'node:test';
import assert from 'node:assert/strict';
import { installLifecycle, classifyTool } from '../lib/lifecycle.js';

function fixture() {
  const handlers = new Map(), sent = [];
  const ctx = { on(name, fn) { const list = handlers.get(name) || []; list.push(fn); handlers.set(name, list); } };
  const emit = async (name, ...args) => { for (const fn of handlers.get(name) || []) await fn(...args); };
  const live = installLifecycle(ctx, { post: async (route, body) => { sent.push({ route, body }); return {}; }, intervalMs: 60000, uuid: () => 'turn-test' });
  const drain = async () => { for (const task of live.tasks.values()) await task.queue; };
  return { emit, live, sent, drain };
}

test('整轮结束才发完成，子调用 idle 和全部 todo 完成不提前变绿', async () => {
  const f = fixture(), session = { id: 's', title: '测试任务' };
  try {
    await f.emit('session/event', session, { type: 'turn/start' });
    await f.emit('agent/status', { agent: { session }, status: 'idle' });
    await f.emit('tools/result', { name: 'todo_write', agent: { session }, arguments: { todos: [{ status: 'completed' }] } }, {});
    await f.drain();
    assert.equal(f.live.tasks.get('s').phase, 'running');
    await f.emit('session/event', session, { type: 'turn/end', data: { reason: { kind: 'completed' } } });
    await f.drain();
    assert.equal(f.sent.filter(x => x.body?.phase === 'completed').length, 1);
    assert.equal(f.sent.at(-1).body.completed, 1);
  } finally { f.live.dispose(); }
});

test('前台、后台、等待与取消保留各自语义', async () => {
  const f = fixture(), session = { id: 's' };
  try {
    await f.emit('session/event', session, { type: 'turn/start' });
    await f.emit('session/event', session, { type: 'tool/call', data: { name: 'bash', arguments: { command: 'curl http://127.0.0.1:3090/app/ui/tap?x=1' } } });
    await f.drain();assert.equal(f.live.tasks.get('s').phase, 'foreground');
    await f.emit('session/event', session, { type: 'tool/call', data: { name: 'mcp__mobile-use__mobile_mode', arguments: { mode: 'background' } } });
    await f.drain();assert.equal(f.live.tasks.get('s').phase, 'background');
    assert.equal(f.sent.at(-1).body.display_backend, 'root');
    await f.emit('session/event', session, { type: 'tool/call', data: { name: 'android_vscreen_tap', arguments: {} } });
    await f.drain();assert.equal(f.sent.at(-1).body.display_backend, 'dsha');
    let resolveQuestion;
    const question = f.emit('user-questions/request', { session, questions: [{ question: '继续吗？' }] }, () => new Promise(resolve => { resolveQuestion = resolve; }));
    while (!resolveQuestion) await new Promise(r => setImmediate(r));
    assert.equal(f.live.tasks.get('s').phase, 'waiting');
    assert.equal(f.sent.find(x => x.route === '/api/question').body.turn_id, 'turn-test');
    resolveQuestion({ answers: ['继续'] });await question;
    assert.equal(f.live.tasks.get('s').phase, 'background');
    await f.emit('session/event', session, { type: 'turn/end', data: { reason: { kind: 'interrupted' } } });
    await f.drain();assert.equal(f.sent.at(-1).body.phase, 'cancelled');
    assert.equal(f.sent.some(x => x.body?.phase === 'completed'), false);
  } finally { f.live.dispose(); }
});

test('失败重试与多会话不会提前完成或互相覆盖', async () => {
  const f = fixture(), a = { id: 'a' }, b = { id: 'b' };
  try {
    await f.emit('session/event', a, { type: 'turn/start' });
    await f.emit('session/event', b, { type: 'turn/start' });
    await f.emit('agent/error', { agent: { session: a } });
    assert.equal(f.live.tasks.get('a').phase, 'running');
    await f.emit('session/event', a, { type: 'turn/end', data: { reason: { kind: 'blocked' } } });
    await f.drain();
    assert.equal(f.live.tasks.get('a').phase, 'failed');assert.equal(f.live.tasks.get('b').phase, 'running');
  } finally { f.live.dispose(); }
});

test('DSHA 两套屏幕后端识别独立', () => {
  assert.equal(classifyTool('android_vscreen_tap'), 'background');
  assert.equal(classifyTool('android_tap'), 'foreground');
  assert.equal(classifyTool('read_file'), 'running');
});

test('岛内停止使用宿主 cancel user，重复轮询不会重复取消', async () => {
  const handlers = new Map();let cancelled = 0, cause;
  const ctx = { on(name, fn) { handlers.set(name, fn); } };
  const live = installLifecycle(ctx, { intervalMs: 5, post: async route => route.startsWith('/api/live/commands') ? { command: 'stop' } : {} });
  try {
    handlers.get('agent/status')({ status: 'running', agent: { id: 's', cancel(value) { cancelled++;cause = value; } } });
    await new Promise(r => setTimeout(r, 35));
    assert.equal(cancelled, 1);assert.deepEqual(cause, { kind: 'user' });
  } finally { live.dispose(); }
});
