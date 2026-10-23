'use strict';
/**
 * dsh-mobile-use MCP server（stdio, JSONL）。
 *
 * 只做一件事：把 agent-mobile-use 的本地网关 127.0.0.1:3070 包装成 MCP 工具。
 * 网关只监听回环，使用应用私有控制 token；本进程不监听端口。
 *
 * 工具语义与 DSHA 内置 android_* / android_vscreen_* 平行：
 *   - 这类工具操作的是 KernelSU 模块创建的“真虚拟屏”（DisplayManager VirtualDisplay）；
 *   - 需要在 KernelSU 里启用 agent_mobile_use 模块，并在控制台打开“超级岛通知”；
 *   - 屏幕内容与用户主屏隔离，通知会以超级岛形式呈现（HyperOS）。
 */

const BASE = process.env.MOBILE_USE_BASE || 'http://127.0.0.1:3070';
const MAX_IMAGE_BYTES = 16 * 1024 * 1024;
const DEFAULT_TIMEOUT_MS = 120000;
const { request: gatewayRequest } = require('./gateway.cjs');

const ACTIONS = ['observe', 'click', 'set_value', 'swipe', 'key', 'launch_app', 'wait'];

const definitions = [
  ['mobile_diagnose', '只读检查模块、DSHA插件、连接凭据与安装状态；不会操作手机屏幕。', {}],
  ['mobile_status', '读取网关与虚拟屏状态（模式、display_id、尺寸、会话、LSPosed 是否生效）。', {}],
  ['mobile_start', '创建/复用 root 虚拟屏并返回 display_id、尺寸、DPI。', { width: { type: 'integer', minimum: 16 }, height: { type: 'integer', minimum: 16 }, dpi: { type: 'integer', minimum: 1 }, orientation: { type: 'string', enum: ['portrait', 'landscape'] } }],
  ['mobile_stop', '关闭虚拟屏并回收。', {}],
  ['mobile_mode', '切换目标显示：foreground=用户主屏(display 0)，background=虚拟屏，idle=解除屏幕控制。切到 background 会把当前任务迁移到虚拟屏。', { mode: { type: 'string', enum: ['foreground', 'background', 'idle'] } }],
  ['mobile_screenshot', '截取当前目标显示的最新一帧（JPEG）。虚拟屏模式需要先 start + mode=background。', {}],
  ['mobile_ui_dump', '读取当前目标显示的无障碍控件树（扁平化文本，含坐标）。操作前先读，操作后再次验证。', { no_system_ui: { type: 'boolean' }, raw: { type: 'boolean' } }],
  ['mobile_action', '执行一次动作。click 用 coordinate；swipe 用 coordinate+end_coordinate；set_value 用 text；key 用 key/文本键名；launch_app 用 package；wait 用 duration_ms。多数动作会顺带返回操作后的 UI 树。', { action: { type: 'string', enum: ACTIONS }, coordinate: { type: 'array', items: { type: 'integer' }, minItems: 2, maxItems: 2 }, end_coordinate: { type: 'array', items: { type: 'integer' }, minItems: 2, maxItems: 2 }, target: { type: 'string' }, duration_ms: { type: 'integer', minimum: 0 }, text: { type: 'string' }, key: { type: 'string' }, package: { type: 'string' }, activity: { type: 'string' }, no_system_ui: { type: 'boolean' } }],
  ['mobile_apps', '列出已安装应用（可给 query 过滤）。', { query: { type: 'string' } }],
  ['mobile_notify', '在手机上发任务通知；虚拟屏模式下会走 HyperOS 超级岛（需在控制台开启）。total/completed 用于进度与完成态。', { title: { type: 'string' }, session_title: { type: 'string' }, content: { type: 'string' }, session_id: { type: 'string' }, total: { type: 'integer', minimum: 0 }, completed: { type: 'integer', minimum: 0 }, is_completed: { type: 'boolean' } }],
  ['mobile_question', '投递问题通知，不创建可回答的 DSH 请求。真正交互提问使用宿主 ask_user_question，插件会自动同步等待回答状态与问题通知。', { request_id: { type: 'string' }, session_id: { type: 'string' }, questions: { type: 'array', items: { type: 'object' } } }],
  ['mobile_question_cancel', '取消一条未回答的提问。', { request_id: { type: 'string' } }],
  ['mobile_live_status', '读取手机 Agent 实况状态、任务进度与当前通知阶段。', {}],
  ['mobile_resize', '调整 root 虚拟屏尺寸与 DPI；输入后重新读屏再使用坐标。', { width: { type: 'integer', minimum: 16 }, height: { type: 'integer', minimum: 16 }, dpi: { type: 'integer', minimum: 1 } }],
  ['mobile_audio_status', '读取虚拟屏应用静音状态。', {}],
  ['mobile_audio_toggle', '设置虚拟屏应用静音，退出时恢复本模块修改前的设置。', { enabled: { type: 'boolean' } }],
];

// DSH 0.2 的工具 schema 子集不支持 minimum/minItems/maxItems；范围由 validate 强制执行。
function schemaForDsh(properties) {
  return JSON.parse(JSON.stringify(properties, (key, value) =>
    ['minimum', 'minItems', 'maxItems'].includes(key) ? undefined : value));
}

const REQUIRED = {
  mobile_mode: ['mode'],
  mobile_action: ['action'],
  mobile_question: ['request_id', 'questions'],
  mobile_question_cancel: ['request_id'],
  mobile_audio_toggle: ['enabled'],
};

const tools = definitions.map(([name, description, properties]) => ({
  name,
  description,
  inputSchema: { type: 'object', properties: schemaForDsh(properties), required: REQUIRED[name] || [], additionalProperties: false },
}));

function bad(message) {
  const e = new Error(message);
  e.code = message;
  return e;
}

function validate(name, args) {
  if (!args || typeof args !== 'object' || Array.isArray(args)) throw bad('INVALID_ARGUMENTS');
  const def = definitions.find((d) => d[0] === name);
  if (!def) throw bad('UNKNOWN_TOOL');
  const fields = def[2];
  for (const key of Object.keys(args)) {
    if (!Object.hasOwn(fields, key)) throw bad('UNKNOWN_ARGUMENT');
  }
  for (const key of REQUIRED[name] || []) {
    if (!Object.hasOwn(args, key)) throw bad('INVALID_' + key);
  }
  if (args.action !== undefined && !ACTIONS.includes(args.action)) throw bad('INVALID_action');
  for (const key of ['coordinate', 'end_coordinate']) {
    if (!Object.hasOwn(args, key)) continue;
    const v = args[key];
    if (!Array.isArray(v) || v.length !== 2 || !v.every((n) => Number.isInteger(n) && n >= 0)) throw bad('INVALID_' + key);
  }
  for (const key of ['duration_ms', 'total', 'completed']) {
    if (!Object.hasOwn(args, key)) continue;
    if (!Number.isInteger(args[key]) || args[key] < 0) throw bad('INVALID_' + key);
  }
  for (const key of ['width', 'height', 'dpi']) {
    if (Object.hasOwn(args, key) && (!Number.isInteger(args[key]) || args[key] < (key === 'dpi' ? 1 : 16) || args[key] > 16384)) throw bad('INVALID_' + key);
  }
  for (const [key, field] of Object.entries(fields)) {
    if (!Object.hasOwn(args, key)) continue;
    if (field.type === 'string' && typeof args[key] !== 'string') throw bad('INVALID_' + key);
    if (field.type === 'boolean' && typeof args[key] !== 'boolean') throw bad('INVALID_' + key);
    if (field.enum && !field.enum.includes(args[key])) throw bad('INVALID_' + key);
  }
  if (args.mode !== undefined && fields.mode.enum && !fields.mode.enum.includes(args.mode)) throw bad('INVALID_mode');
  if (args.questions !== undefined && !Array.isArray(args.questions)) throw bad('INVALID_questions');
  if (args.action === 'click' && !Object.hasOwn(args, 'coordinate') && !args.target) throw bad('INVALID_coordinate');
  if (args.action === 'swipe' && (!Object.hasOwn(args, 'coordinate') || !Object.hasOwn(args, 'end_coordinate'))) throw bad('INVALID_coordinate');
  if (args.action === 'launch_app' && !args.package) throw bad('INVALID_package');
  if (args.action === 'set_value' && !Object.hasOwn(args, 'text')) throw bad('INVALID_text');
  if (args.action === 'key' && !args.key && !args.text) throw bad('INVALID_key');
}

async function request(url, options = {}) {
  return gatewayRequest(url, { timeoutMs: DEFAULT_TIMEOUT_MS, ...options });
}

async function readJson(response) {
  const text = await response.text();
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    if (!response.ok) throw bad(response.status === 401 ? 'GATEWAY_UNAUTHORIZED' : 'GATEWAY_HTTP_' + response.status);
    throw bad('INVALID_GATEWAY_RESPONSE');
  }
  const ok = response.ok && (value.success === undefined || value.success === true);
  if (!ok) {
    return {
      content: [{ type: 'text', text: JSON.stringify({ ok: false, status: response.status, message: value.message || text.slice(0, 500) }) }],
      isError: true,
    };
  }
  return { content: [{ type: 'text', text: JSON.stringify(value) }], isError: false };
}

async function readJsonBody(response) {
  return readJson(response);
}

async function screenshot(options = {}) {
  const response = await request(BASE + '/api/screenshot', options);
  if (!response.ok) {
    const text = (await response.text()).trim();
    return { content: [{ type: 'text', text: text || 'screenshot unavailable (status ' + response.status + '); call mobile_start + mobile_mode(background) first' }], isError: true };
  }
  const buffer = Buffer.from(await response.arrayBuffer());
  if (buffer.length > MAX_IMAGE_BYTES) throw bad('SCREENSHOT_TOO_LARGE');
  let mimeType = 'image/jpeg';
  if (buffer.length >= 8 && buffer.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) mimeType = 'image/png';
  else if (buffer.length < 3 || buffer[0] !== 0xff || buffer[1] !== 0xd8) throw bad('SCREENSHOT_NOT_IMAGE');
  return {
    content: [
      { type: 'text', text: JSON.stringify({ ok: true, bytes: buffer.length, mimeType }) },
      { type: 'image', mimeType, data: buffer.toString('base64') },
    ],
    isError: false,
  };
}

async function call(name, args, options = {}) {
  validate(name, args);
  const request = (url, extra = {}) => gatewayRequest(url, { timeoutMs: DEFAULT_TIMEOUT_MS, ...extra, signal: options.signal });
  if (options.signal?.aborted) throw options.signal.reason || bad('CANCELLED');
  if (name === 'mobile_screenshot') return screenshot(options);

  if (name === 'mobile_diagnose') return readJson(await request(BASE + '/api/diagnostics'));
  if (name === 'mobile_status') return readJson(await request(BASE + '/api/status'));
  if (name === 'mobile_start') return readJson(await request(BASE + '/api/start', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(args) }));
  if (name === 'mobile_stop') return readJson(await request(BASE + '/api/stop', { method: 'POST' }));
  if (name === 'mobile_live_status') return readJson(await request(BASE + '/api/live/status'));
  if (name === 'mobile_audio_status') return readJson(await request(BASE + '/api/audio/status'));
  if (name === 'mobile_resize') return readJson(await request(BASE + '/api/resize', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(args) }));
  if (name === 'mobile_audio_toggle') return readJson(await request(BASE + '/api/audio/toggle', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(args) }));

  if (name === 'mobile_mode') {
    const url = BASE + '/api/mode?mode=' + encodeURIComponent(args.mode);
    return readJson(await request(url, { method: 'POST' }));
  }

  if (name === 'mobile_ui_dump') {
    const params = new URLSearchParams();
    if (args.no_system_ui) params.set('no_system_ui', '1');
    if (args.raw) params.set('raw', '1');
    const url = BASE + '/api/dump_ui' + (params.size ? '?' + params : '');
    const response = await request(url);
    if (args.raw) return { content: [{ type: 'text', text: await response.text() }], isError: !response.ok };
    return readJson(response);
  }

  if (name === 'mobile_action') {
    const body = {
      action: args.action,
      coordinate: args.coordinate,
      end_coordinate: args.end_coordinate,
      duration_ms: args.duration_ms,
      text: args.text,
      key: args.key,
      package: args.package,
      activity: args.activity,
      no_system_ui: args.no_system_ui,
    };
    if (args.target !== undefined) body.target = args.target;
    for (const key of Object.keys(body)) if (body[key] === undefined) delete body[key];
    return readJson(await request(BASE + '/api/action', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }));
  }

  if (name === 'mobile_apps') {
    const url = BASE + '/api/apps' + (args.query ? '?query=' + encodeURIComponent(args.query) : '');
    return readJson(await request(url));
  }

  if (name === 'mobile_notify') {
    return readJson(await request(BASE + '/api/notify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(args),
    }));
  }

  if (name === 'mobile_question') {
    return readJson(await request(BASE + '/api/question', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(args),
    }));
  }

  if (name === 'mobile_question_cancel') {
    return readJson(await request(BASE + '/api/question/cancel', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ request_id: args.request_id }),
    }));
  }

  throw bad('UNKNOWN_TOOL');
}

// ── MCP stdio plumbing ──────────────────────────────────────────────────────
function send(message) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...message }) + '\n');
}

function startServer() {
  const active = new Map();
  let buffer = '';
  let queue = Promise.resolve();
  let ended = false;
  let pending = 0;

  async function receive(message) {
    const { id, method, params = {} } = message;
    if (method === 'notifications/cancelled') {
      if (active.has(params.requestId)) active.get(params.requestId).abort();
      return;
    }
    if (id === undefined) return;
    const controller = active.get(id);
    if (ended || controller?.signal.aborted) {
      send({ id, error: { code: -32800, message: 'Request cancelled before execution' } });
      return;
    }
    try {
      let result;
      if (method === 'initialize') {
        result = {
          protocolVersion: params.protocolVersion || '2025-03-26',
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: 'dsh-mobile-use', version: require('./version.cjs').pluginVersion },
          instructions:
            '这些工具操作 agent-mobile-use 的 root 虚拟屏网关（127.0.0.1:3070）。顺序：mobile_start → mobile_mode(background) → mobile_ui_dump/mobile_screenshot → mobile_action。虚拟屏需要 KernelSU 模块；超级岛需要控制台开关。每次动作后重新读屏验证，别凭记忆连点。',
        };
      } else if (method === 'ping') {
        result = {};
      } else if (method === 'tools/list') {
        result = { tools };
      } else if (method === 'tools/call') {
        result = await call(params.name, params.arguments || {}, { signal: controller?.signal });
      } else {
        send({ id, error: { code: -32601, message: 'Method not found' } });
        return;
      }
      send({ id, result });
    } catch (error) {
      if (controller?.signal.aborted) {
        send({ id, error: { code: -32800, message: 'Request cancelled; submitted device actions may already have run' } });
        return;
      }
      const detail = error && (error.name === 'AbortError' || error.name === 'TimeoutError')
        ? 'GATEWAY_TIMEOUT'
        : (error && /^[A-Z][A-Za-z0-9_]{0,60}$/.test(error.message || '') ? error.message : 'GATEWAY_UNAVAILABLE');
      send({
        id,
        result: {
          content: [{ type: 'text', text: 'mobile-use operation unavailable: ' + detail + '. Check that the KernelSU module is running and 127.0.0.1:3070 answers.' }],
          isError: true,
        },
      });
    } finally {
      active.delete(id);
    }
  }

  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    buffer += chunk;
    if (buffer.length > 1024 * 1024) {
      process.stderr.write('MCP_INPUT_LIMIT\n');
      process.exit(1);
    }
    let end;
    while ((end = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, end);
      buffer = buffer.slice(end + 1);
      if (!line.trim()) continue;
      let value;
      try {
        value = JSON.parse(line);
      } catch {
        send({ id: null, error: { code: -32700, message: 'Parse error' } });
        continue;
      }
      if (value.method === 'notifications/cancelled') void receive(value);
      else if (pending >= 32) send({ id: value.id ?? null, error: { code: -32000, message: 'Request queue full' } });
      else {
        if (value.id !== undefined) active.set(value.id, new AbortController());
        pending++;
        queue = queue.then(() => receive(value)).finally(() => pending--);
      }
    }
  });
  process.stdin.on('end', () => {
    ended = true;
    for (const controller of active.values()) controller.abort();
  });
  process.stdout.on('error', (error) => {
    if (error.code === 'EPIPE') process.exit(0);
  });
}

if (require.main === module) startServer();

module.exports = { definitions, tools, validate, call };
