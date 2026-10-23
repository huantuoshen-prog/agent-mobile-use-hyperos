'use strict';
const { randomUUID } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');
const gateway = require('./gateway.cjs');
const base = require('./server.cjs');

const extra = [
  ['mobile_task_begin', '开始远程手机任务，独占控制手机并显示超级岛实况。操作屏幕前必须调用；已有任务时先结束。', { title: { type: 'string' } }, ['title']],
  ['mobile_task_update', '更新当前任务说明和实际进度。前台/后台阶段随 mobile_mode 自动变化。', { detail: { type: 'string' }, total: { type: 'integer', minimum: 0 }, completed: { type: 'integer', minimum: 0 } }, []],
  ['mobile_task_end', '以真实结果结束整轮任务、退出屏幕控制并回收副屏。任务完成后必须调用。', { outcome: { type: 'string', enum: ['completed', 'failed', 'cancelled'] }, detail: { type: 'string' } }, ['outcome']],
  ['mobile_ask', '在手机超级岛/答题页提问，等待手机答案并返回当前 Agent。仅此调用等待期间有效；不是系统授权接口。', {
    questions: { type: 'array', minItems: 1, maxItems: 12, items: { type: 'object', additionalProperties: false, properties: {
      id: { type: 'string' }, question: { type: 'string' }, header: { type: 'string' }, detail: { type: 'string' }, multiSelect: { type: 'boolean' },
      options: { type: 'array', maxItems: 32, items: { type: 'object', additionalProperties: false, properties: { label: { type: 'string' }, description: { type: 'string' } }, required: ['label'] } },
    }, required: ['id', 'question'] } }, timeout_seconds: { type: 'integer', minimum: 5, maximum: 600 },
  }, ['questions']],
];
const tools = [...base.tools.filter(t => !['mobile_notify', 'mobile_question', 'mobile_question_cancel'].includes(t.name)), ...extra.map(([name, description, properties, required]) => ({ name, description, inputSchema: { type: 'object', properties, required, additionalProperties: false } }))];
const readOnly = new Set(['mobile_diagnose', 'mobile_status', 'mobile_live_status', 'mobile_apps', 'mobile_audio_status']);
const result = value => ({ content: [{ type: 'text', text: JSON.stringify(value) }], isError: false });
const fail = code => { throw new Error(code); };

function validate(name, args) {
  const def = extra.find(d => d[0] === name);
  if (!def) return base.validate(name, args);
  if (!args || typeof args !== 'object' || Array.isArray(args)) fail('INVALID_ARGUMENTS');
  for (const k of Object.keys(args)) {
    if (!Object.hasOwn(def[2], k)) fail('UNKNOWN_ARGUMENT');
    const f = def[2][k];
    if (f.type === 'string' && (typeof args[k] !== 'string' || args[k].length > 2000)) fail('INVALID_ARGUMENTS');
    if (f.type === 'integer' && (!Number.isInteger(args[k]) || args[k] < f.minimum || args[k] > (f.maximum || 1000000))) fail('INVALID_ARGUMENTS');
    if (f.enum && !f.enum.includes(args[k])) fail('INVALID_ARGUMENTS');
  }
  for (const k of def[3]) if (!Object.hasOwn(args, k)) fail('INVALID_ARGUMENTS');
  if (name === 'mobile_task_begin' && (!args.title.trim() || args.title.length > 80)) fail('INVALID_TITLE');
  if (name === 'mobile_ask') {
    if (!Array.isArray(args.questions) || !args.questions.length || args.questions.length > 12 || Buffer.byteLength(JSON.stringify(args.questions)) > 40000) fail('INVALID_QUESTIONS');
    const ids = new Set();
    for (const q of args.questions) {
      if (!q || typeof q !== 'object' || Object.keys(q).some(k => !['id', 'question', 'header', 'detail', 'options', 'multiSelect'].includes(k))) fail('INVALID_QUESTIONS');
      if (typeof q.id !== 'string' || !q.id || q.id.length > 128 || ids.has(q.id) || typeof q.question !== 'string' || !q.question.trim()) fail('INVALID_QUESTIONS');
      ids.add(q.id);
      if (['header', 'detail'].some(k => q[k] !== undefined && typeof q[k] !== 'string') || (q.multiSelect !== undefined && typeof q.multiSelect !== 'boolean')) fail('INVALID_QUESTIONS');
      if (q.options !== undefined && (!Array.isArray(q.options) || q.options.length > 32)) fail('INVALID_QUESTIONS');
      const labels = new Set();
      for (const o of q.options || []) {
        if (!o || typeof o.label !== 'string' || !o.label.trim() || labels.has(o.label) || (o.description !== undefined && typeof o.description !== 'string') || Object.keys(o).some(k => !['label', 'description'].includes(k))) fail('INVALID_QUESTIONS');
        labels.add(o.label);
      }
    }
  }
}

class RemoteClient {
  constructor(endpoint) {
    this.endpoint = endpoint;
    this.task = null;
    this.closed = false;
  }
  async api(route, body, options = {}) {
    const response = await gateway.request(this.endpoint + route, {
      method: body === undefined ? 'GET' : 'POST', headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body), timeoutMs: 5000, ...options,
    });
    if (!response.ok) fail(response.status === 409 ? 'CONTROL_BUSY_OR_EXPIRED' : 'GATEWAY_HTTP_' + response.status);
    const value = await response.json();
    if (value.success === false || value.accepted === false) fail('GATEWAY_REJECTED');
    return value;
  }
  report(task, phase, detail) {
    task.phase = phase;
    if (detail !== undefined) task.detail = detail;
    const event = { session_id: task.sid, turn_id: task.turn, sequence: ++task.seq, session_title: task.title,
      phase, detail: task.detail, source: 'remote-mcp', display_backend: task.mode === 'foreground' ? 'physical' : 'root', total: task.total, completed: task.completed };
    const sent = task.events.catch(() => {}).then(() => task.api('/api/live/event', event));
    task.events = sent;
    return sent;
  }
  async begin(args, signal) {
    if (this.closed) fail('CLIENT_CLOSED');
    if (this.task) fail('TASK_ALREADY_ACTIVE');
    const owner = randomUUID();
    const task = { owner, sid: 'remote-' + owner, turn: randomUUID(), title: args.title, seq: 0, phase: 'running', mode: 'idle', detail: '', total: 0, completed: 0, events: Promise.resolve(), abort: new AbortController(), lastRenew: Date.now() };
    task.api = (route, body, options) => this.api(route, body, { ...options, controlId: owner });
    // Do not cancel the acquire response: an uncertain acquisition must be released.
    try { await task.api('/api/control/acquire', {}); }
    catch (error) {
      // This identity is unique to this process; a release cannot affect another client.
      await task.api('/api/control/release', {}).catch(() => {});
      throw error;
    }
    this.task = task;
    try {
      if (signal?.aborted || this.closed) fail('CANCELLED');
      await this.report(task, 'running', '远程 Agent 已连接');
      await task.api('/api/mode?mode=idle', {}, { signal });
      task.timer = setInterval(() => { void this.watch(task); }, 1000);
      task.timer.unref();
      return result({ success: true, session_id: task.sid, turn_id: task.turn, lease_seconds: 30 });
    } catch (error) { await this.finish(task, 'cancelled', '任务启动已中止'); throw error; }
  }
  async watch(task) {
    if (task.finishing || task.watching) return;
    task.watching = true;
    try {
      if (Date.now() - task.lastRenew >= 10000) {
        await task.api('/api/control/renew', {});
        if (task.finishing) return;
        task.lastRenew = Date.now();
        await this.report(task, task.phase);
      }
      const query = new URLSearchParams({ session_id: task.sid, turn_id: task.turn });
      const value = await task.api('/api/live/commands?' + query, undefined, { signal: task.abort.signal });
      if (value.command === 'stop') await this.finish(task, 'cancelled', '用户在手机上停止了任务');
    } catch {
      if (!task.finishing) await this.finish(task, 'failed', '远程连接已中断，停止手机控制');
    } finally { task.watching = false; }
  }
  finish(task, outcome, detail) {
    if (task.finishing) return task.finishing;
    clearInterval(task.timer);
    task.abort.abort(new Error('TASK_STOPPED'));
    task.finishing = (async () => {
      let notified = true, released = false;
      try { await this.report(task, outcome, detail); } catch { notified = false; }
      try { released = (await task.api('/api/control/release', {}, { timeoutMs: 10000 })).released === true; } catch {}
      if (this.task === task) this.task = null;
      return result({ success: true, outcome, notification_confirmed: notified, cleanup_pending: !released,
        ...(released ? {} : { detail: '等待手机端租约到期及在途操作结束后回收；不要自动重放动作。' }) });
    })();
    return task.finishing;
  }
  async ask(task, args, signal) {
    const { validateHostAnswer } = await import('./questions.js');
    const key = { request_id: randomUUID(), session_id: task.sid, turn_id: task.turn };
    const timeout = AbortSignal.timeout((args.timeout_seconds || 120) * 1000);
    const waitSignal = AbortSignal.any([signal, timeout]);
    const previous = task.phase;
    let settlement = { ...key, state: 'cancelled' };
    await this.report(task, 'waiting', args.questions[0].question);
    try {
      await task.api('/api/question', { ...key, questions: args.questions, bridge: true }, { signal: waitSignal });
      while (true) {
        const q = await task.api('/api/question?' + new URLSearchParams(key), undefined, { signal: waitSignal });
        if (q.state === 'submitted') {
          const answer = validateHostAnswer(args.questions, q);
          if (waitSignal.aborted) throw waitSignal.reason;
          settlement = { ...key, state: 'answered', ...answer, answer_source: 'island' };
          return result({ success: true, ...answer });
        }
        if (q.state === 'cancelled' || q.state === 'answered') fail('QUESTION_CLOSED');
        await delay(500, undefined, { signal: waitSignal });
      }
    } finally {
      // Deliver the answer without waiting for notification bookkeeping. Queue
      // settlement before restoring phase so the phone still accepts the answer.
      task.events = task.events.catch(() => {}).then(() => task.api('/api/question/settle', settlement));
      task.events.catch(() => {});
      if (!task.finishing) this.report(task, previous, settlement.state === 'answered' ? '已收到手机回答' : '提问已结束').catch(() => {});
    }
  }
  async call(name, args, options = {}) {
    if (!tools.some(t => t.name === name)) fail('UNKNOWN_TOOL');
    validate(name, args);
    if (options.signal?.aborted) throw options.signal.reason;
    if (name === 'mobile_task_begin') return this.begin(args, options.signal);
    if (readOnly.has(name)) return base.call(name, args, options);
    const task = this.task;
    if (!task || task.finishing) fail('TASK_NOT_ACTIVE');
    if (name === 'mobile_task_end') return this.finish(task, args.outcome, args.detail || '');
    const signal = AbortSignal.any([task.abort.signal, ...(options.signal ? [options.signal] : [])]);
    try {
      if (name === 'mobile_task_update') {
        const total = args.total ?? task.total, completed = args.completed ?? task.completed;
        if (total > 0 && completed > total) fail('INVALID_PROGRESS');
        task.total = total; task.completed = completed;
        await this.report(task, task.phase, args.detail);
        return result({ success: true });
      }
      if (name === 'mobile_ask') return await this.ask(task, args, signal);
      const value = await base.call(name, args, { signal, controlId: task.owner });
      if (task.abort.signal.aborted) throw task.abort.signal.reason;
      if (!value.isError && (name === 'mobile_mode' || name === 'mobile_stop')) {
        task.mode = name === 'mobile_mode' ? args.mode : 'idle';
        if (name === 'mobile_stop') await task.api('/api/mode?mode=idle', {}, { signal });
        await this.report(task, task.mode === 'idle' ? 'running' : task.mode, task.mode === 'idle' ? '屏幕控制已结束' : '正在操作手机');
      }
      return value;
    } catch (error) {
      if (signal.aborted) await this.finish(task, 'cancelled', '调用已取消');
      throw error;
    }
  }
  async close() {
    this.closed = true;
    if (this.task) await this.finish(this.task, 'cancelled', 'Agent 客户端已断开');
  }
}
module.exports = { RemoteClient, tools, validate };
