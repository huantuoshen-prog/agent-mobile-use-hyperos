'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const gateway = require('../lib/gateway.cjs');
const { RemoteClient } = require('../lib/remote.cjs');
const value = response => JSON.parse(response.content[0].text);

async function fixture(t) {
  let clock = 100000;
  const routes = [], events = [];
  const state = { faults: new Map(), command: '' };
  t.mock.method(Date, 'now', () => clock);
  t.mock.method(gateway, 'request', async (url, options) => {
    const route = new URL(url).pathname;
    routes.push(route);
    if (state.faults.has(route)) throw state.faults.get(route);
    if (route === '/api/live/event') events.push(JSON.parse(options.body));
    return new Response(JSON.stringify({ success: true, released: true, command: state.command }));
  });
  const client = new RemoteClient('http://127.0.0.1:3070');
  await client.call('mobile_task_begin', { title: '连接恢复测试' });
  const task = client.task;
  clearInterval(task.timer);
  t.after(() => client.close());
  return { client, task, state, routes, events, advance: ms => { clock += ms; } };
}

test('租约内监视超时和临时 503 可恢复，续租失败不延长本地租约', async t => {
  const f = await fixture(t);
  f.state.faults.set('/api/live/commands', new DOMException('timeout', 'TimeoutError'));
  await f.client.watch(f.task);
  assert.equal(f.client.task, f.task);assert.equal(f.task.abort.signal.aborted, false);
  f.advance(10000);
  f.state.faults.set('/api/control/renew', new Error('GATEWAY_HTTP_503'));
  const before = f.task.lastRenew;
  await f.client.watch(f.task);
  assert.equal(f.task.lastRenew, before);assert.equal(f.client.task, f.task);
  f.state.faults.clear();
  await f.client.watch(f.task);
  assert.ok(f.task.lastRenew > before);
  assert.equal(f.client.task, f.task);
});

test('通知心跳 503 后继续检查手机停止，重复收尾保留取消结果', async t => {
  const f = await fixture(t);
  f.advance(10000);f.state.command = 'stop';
  f.state.faults.set('/api/live/event', new Error('GATEWAY_HTTP_503'));
  await f.client.watch(f.task);
  assert.equal(f.client.task, null);
  const done = value(await f.client.call('mobile_task_end', { outcome: 'completed' }));
  assert.equal(done.outcome, 'cancelled');assert.equal(done.notification_confirmed, false);
  assert.equal(done.cleanup_pending, false);
  assert.equal(done.session_id, f.task.sid);
  assert.deepEqual(value(await f.client.call('mobile_task_end', { outcome: 'failed' })), done);
});

test('续租暂时失败后仍能收到手机停止命令', async t => {
  const f = await fixture(t);
  f.advance(10000);f.state.command = 'stop';
  f.state.faults.set('/api/control/renew', new Error('GATEWAY_HTTP_503'));
  await f.client.watch(f.task);
  assert.equal(f.client.task, null);
  assert.equal(value(await f.client.call('mobile_task_end', { outcome: 'completed' })).outcome, 'cancelled');
});

test('租约或停止轮询失联达到 30 秒即停止，恢复网络后不续租旧任务', async t => {
  const f = await fixture(t);
  f.advance(30000);
  await f.client.watch(f.task);
  assert.equal(f.client.task, null);
  assert.equal(f.routes.includes('/api/control/renew'), false);
  const done = value(await f.client.call('mobile_task_end', { outcome: 'completed' }));
  assert.equal(done.outcome, 'failed');assert.equal(done.failure_code, 'CONTROL_CONNECTION_EXPIRED');
  await assert.rejects(f.client.call('mobile_mode', { mode: 'background' }), /TASK_NOT_ACTIVE/);
  await f.client.call('mobile_task_begin', { title: '显式开始新任务' });
  clearInterval(f.client.task.timer);
  assert.notEqual(f.client.task.sid, done.session_id);
  assert.equal(value(await f.client.call('mobile_task_end', { outcome: 'completed' })).outcome, 'completed');
});

test('续租成功也不能无限容忍停止命令通道失联', async t => {
  const f = await fixture(t);
  f.state.faults.set('/api/live/commands', new TypeError('fetch failed'));
  for (let i = 0; i < 2; i++) {
    f.advance(10000);await f.client.watch(f.task);
    assert.equal(f.client.task, f.task);
  }
  f.advance(10000);await f.client.watch(f.task);
  assert.equal(f.client.task, null);
});

test('鉴权或控制权拒绝立即终止，回收失败保持 cleanup_pending', async t => {
  const f = await fixture(t);
  f.state.faults.set('/api/live/commands', new Error('GATEWAY_HTTP_401'));
  f.state.faults.set('/api/control/release', new TypeError('fetch failed'));
  await f.client.watch(f.task);
  const done = value(await f.client.call('mobile_task_end', { outcome: 'completed' }));
  assert.equal(done.outcome, 'failed');assert.equal(done.failure_code, 'GATEWAY_HTTP_401');
  assert.equal(done.cleanup_pending, true);
});

test('网关拒绝当前控制身份时立即停止并保留故障码', async t => {
  const f = await fixture(t);
  f.state.faults.set('/api/live/commands', new Error('CONTROL_BUSY_OR_EXPIRED'));
  await f.client.watch(f.task);
  assert.equal(f.client.task, null);
  const done = value(await f.client.call('mobile_task_end', { outcome: 'completed' }));
  assert.equal(done.outcome, 'failed');assert.equal(done.failure_code, 'CONTROL_BUSY_OR_EXPIRED');
});
