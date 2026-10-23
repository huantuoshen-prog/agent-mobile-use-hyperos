import { randomUUID } from 'node:crypto';
import gateway from './gateway.cjs';
import { answerFromIsland } from './questions.js';

const TERMINAL = new Set(['completed', 'failed', 'cancelled']);
export function classifyTool(name, args = {}, current = 'running') {
  const n = String(name || '');
  if (typeof args === 'string') { try { args = JSON.parse(args); } catch { args = {}; } }
  const command = String(args?.command || args?.cmd || args?.script || '');
  if (/android_vscreen|dsha.vscreen/i.test(n) || /\/app\/vscreen\//.test(command)) return 'background';
  if (/mobile[_-](?:mode|action|ui_dump|screenshot)|mcp__mobile-use/i.test(n)) {
    if (args.mode === 'foreground') return 'foreground';
    if (args.mode === 'background') return 'background';
    return current;
  }
  if (/android_(?:tap|swipe|input|key|launch|screenshot|ui)|dsha.android/i.test(n) || /\/app\/ui\/(?:tap|input|swipe|key|screenshot|dump)/.test(command)) return 'foreground';
  return current === 'waiting' ? 'running' : current;
}

function sessionId(session) { return String(session?.id || session?.meta?.id || ''); }
function titleOf(session, ctx) {
  try { return String(ctx.sessionTitle?.get?.(session)?.title || session?.title || session?.meta?.title || '手机 Agent'); }
  catch { return '手机 Agent'; }
}
function lastText(event) {
  return (event?.data?.message?.content || []).filter(b => b?.type === 'text').map(b => b.text || '').join(' ').replace(/\s+/g, ' ').trim().slice(0, 160);
}
function displayBackend(name, args = {}, previous = '') {
  if (typeof args === 'string') { try { args = JSON.parse(args); } catch { args = {}; } }
  const command = String(args?.command || args?.cmd || args?.script || '');
  if (/android_vscreen|dsha.vscreen/i.test(name || '') || /\/app\/vscreen\//.test(command)) return 'dsha';
  if (/mobile[_-](?:mode|action|ui_dump|screenshot)/.test(name || '')) return args.mode === 'foreground' ? 'physical' : args.mode === 'idle' ? '' : args.mode === 'background' ? 'root' : previous;
  if (classifyTool(name, args) === 'foreground') return 'physical';
  return previous;
}

// 使用已持久化的 turn/end 判断整轮完成；agent/status idle 可能只是一段模型调用结束。
export function installLifecycle(ctx, { post = gateway.client(), now = Date.now, uuid = randomUUID, intervalMs = 1500 } = {}) {
  const tasks = new Map();
  const controller = new AbortController();
  let disposed = false;
  const report = (task, phase, detail = '', extra = {}) => {
    if (disposed) return Promise.resolve();
    task.phase = phase;
    task.detail = detail;
    const payload = { session_id: task.id, turn_id: task.turnId, sequence: ++task.sequence, session_title: task.title,
      phase, detail, total: task.total || 0, completed: task.completed || 0, source: 'dsha', display_backend: task.backend || '', ...extra };
    task.queue = task.queue.catch(() => {}).then(() => post('/api/live/event', payload, { signal: controller.signal })).catch(() => {});
    return task.queue;
  };
  const begin = session => {
    const id = sessionId(session);
    if (!id) return null;
    let task = tasks.get(id);
    if (task && !TERMINAL.has(task.phase)) return task;
    const preceding = task?.queue || Promise.resolve();
    task = { id, turnId: uuid(), sequence: 0, phase: 'running', title: titleOf(session, ctx), queue: preceding,
      total: 0, completed: 0, agent: null, lastMessage: '', lastHeartbeat: now(), polling: false };
    tasks.set(id, task);
    void report(task, 'running', '正在处理任务');
    return task;
  };

  ctx.on('agent/status', ({ agent, status }) => {
    if (status !== 'running') return;
    const task = begin(agent?.session || { id: agent?.id });
    if (task) task.agent = agent;
  });
  ctx.on('session/event', (session, event) => {
    if (disposed) return;
    const id = sessionId(session);
    if (event?.type === 'turn/start') { begin(session); return; }
    const task = tasks.get(id);
    if (!task || TERMINAL.has(task.phase)) return;
    if (event?.type === 'session/title') {
      task.title = String(event.data?.title || task.title);
      void report(task, task.phase, task.detail);
    } else if (event?.type === 'tool/call') {
      const phase = classifyTool(event.data?.name, event.data?.arguments, task.phase);
      task.backend = displayBackend(event.data?.name, event.data?.arguments, task.backend);
      const label = phase === 'foreground' ? '正在操作手机主屏' : phase === 'background' ? '正在使用后台虚拟屏' : '正在执行工具';
      void report(task, phase, label);
    } else if (event?.type === 'assistant/message') {
      task.lastMessage = lastText(event);
    } else if (event?.type === 'turn/end') {
      const reason = event.data?.reason?.kind || 'unknown';
      const phase = reason === 'completed' ? 'completed' : /aborted|interrupted|cancelled/.test(reason) ? 'cancelled' : 'failed';
      const detail = phase === 'completed' ? (task.lastMessage || '本轮任务已完成') : phase === 'cancelled' ? '任务已停止' : '任务未完成，请查看会话';
      void report(task, phase, detail);
    }
  });
  ctx.on('agent/error', ({ agent }) => {
    const task = tasks.get(sessionId(agent?.session) || String(agent?.id || ''));
    if (task && !TERMINAL.has(task.phase)) {
      task.hadError = true;
      void report(task, task.phase, '遇到错误，正在处理');
    }
  });
  ctx.on('session/disposed', session => {
    const task = tasks.get(sessionId(session));
    if (task && !TERMINAL.has(task.phase)) void report(task, task.hadError ? 'failed' : 'cancelled', '会话已关闭');
  });
  ctx.on('user-questions/request', async (request, next) => {
    const session = request?.session || request?.agent?.session || { id: request?.agent?.id };
    const task = tasks.get(sessionId(session)) || begin(session);
    const requestId = uuid();
    const key = { request_id: requestId, session_id: task?.id || '', turn_id: task?.turnId || '' };
    const previous = task?.phase || 'running';
    let bridged = false;
    if (task) {
      if (request?.agent) task.agent = request.agent;
      await report(task, 'waiting', '等待你的回答');
      const questions = request?.questions || [];
      const bridge = questions.length > 0 && questions.every(q => q.id && !q.intent);
      const result = await post('/api/question', { ...key, call_id: request?.wait?.callId || '', bridge, questions }, { signal: controller.signal }).catch(() => null);
      bridged = bridge && result?.bridge === true;
    }
    try {
      if (bridged && typeof next === 'function') return await answerFromIsland({ request, next, post, key, signal: controller.signal });
      return typeof next === 'function' ? await next() : undefined;
    }
    finally {
      if (task) {
        if (!bridged) await post('/api/question/cancel', key, { signal: controller.signal }).catch(() => {});
        if (!TERMINAL.has(task.phase)) await report(task, previous, '继续处理任务');
      }
    }
  }, { prepend: true });

  ctx.on('tools/result', (exec, result) => {
    if (result?.isError) return;
    const task = tasks.get(sessionId(exec?.agent?.session) || String(exec?.agent?.id || ''));
    if (!task || TERMINAL.has(task.phase)) return;
    try {
      const raw = exec.arguments ?? exec.args ?? exec.input ?? {};
      const args = typeof raw === 'string' ? JSON.parse(raw) : raw;
      if (/mobile_stop$|vscreen_close$/.test(exec?.name || '') || (/mobile_mode$/.test(exec?.name || '') && args.mode === 'idle')) {
        task.backend = '';
        void report(task, 'running', '已结束屏幕控制，继续处理任务');
        return;
      }
      if (!/todo_write|update_plan/.test(exec?.name || '')) return;
      const items = args.todos || args.plan;
      if (!Array.isArray(items) || !items.length) return;
      task.total = items.length;
      task.completed = items.filter(x => x.status === 'completed').length;
      void report(task, task.phase, task.detail);
    } catch {}
  });

  const timer = setInterval(() => {
    for (const [id, task] of tasks) {
      if (TERMINAL.has(task.phase)) { if (now() - task.lastHeartbeat > 60000) tasks.delete(id); continue; }
      if (now() - task.lastHeartbeat > 15000) {
        task.lastHeartbeat = now();
        void report(task, task.phase, task.detail);
      }
      if (task.polling) continue;
      task.polling = true;
      void post('/api/live/commands?session_id=' + encodeURIComponent(id) + '&turn_id=' + encodeURIComponent(task.turnId), undefined,
        { signal: controller.signal }).then(value => {
        if (value.command === 'stop' && !TERMINAL.has(task.phase) && !task.stopRequested) {
          if (typeof task.agent?.cancel === 'function') {
            task.agent.cancel({ kind: 'user' });
            task.stopRequested = true;
          } else if (!task.stopPending) {
            task.stopPending = true;
            void report(task, 'waiting', '请在会话中停止任务');
          }
        }
      }).catch(() => {}).finally(() => { task.polling = false; });
    }
  }, intervalMs);
  timer.unref?.();
  const dispose = () => {
    clearInterval(timer);
    for (const task of tasks.values()) {
      if (!TERMINAL.has(task.phase)) void post('/api/live/event', { session_id: task.id, turn_id: task.turnId,
        sequence: ++task.sequence, phase: 'cancelled', detail: '运行环境已关闭', source: 'dsha' }, { timeoutMs: 1000 }).catch(() => {});
    }
    disposed = true;
    controller.abort();
    tasks.clear();
  };
  ctx.on('dispose', dispose);
  return { tasks, report, dispose };
}
