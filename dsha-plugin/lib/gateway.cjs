'use strict';
const fs = require('node:fs');
const path = require('node:path');

function tokenFile() {
  return process.env.MOBILE_USE_TOKEN_FILE || path.join(process.env.HOME || process.env.USERPROFILE || '/root', '.dsh', 'mobile-use-token');
}

// 不记录 token，不自动重放已经提交的屏幕动作。
async function request(url, options = {}) {
  const target = new URL(url);
  if (target.protocol !== 'http:' || !['127.0.0.1', '[::1]'].includes(target.hostname) || target.username || target.password) {
    throw new Error('ENDPOINT_REQUIRES_LOOPBACK_SSH_FORWARD');
  }
  const controller = new AbortController();
  const timeoutMs = options.timeoutMs || 120000;
  const timer = setTimeout(() => controller.abort(new DOMException('Gateway timeout', 'TimeoutError')), timeoutMs);
  const signal = options.signal ? AbortSignal.any([options.signal, controller.signal]) : controller.signal;
  const headers = new Headers(options.headers);
  if (options.controlId) headers.set('X-Mobile-Use-Control', options.controlId);
  try {
    const token = fs.readFileSync(options.tokenFile || tokenFile(), 'utf8').trim();
    if (token) headers.set('Authorization', 'Bearer ' + token);
  } catch {}
  try {
    const response = await fetch(url, { ...options, headers, signal, redirect: 'error' });
    const maxBytes = options.maxBytes || 16 * 1024 * 1024;
    const chunks = [];
    let total = 0;
    if (response.body) {
      for await (const chunk of response.body) {
        total += chunk.length;
        if (total > maxBytes) {
          controller.abort();
          throw new Error('GATEWAY_RESPONSE_TOO_LARGE');
        }
        chunks.push(chunk);
      }
    }
    return new Response(Buffer.concat(chunks), { status: response.status, statusText: response.statusText, headers: response.headers });
  } finally {
    clearTimeout(timer);
  }
}

function client(base = process.env.MOBILE_USE_BASE || 'http://127.0.0.1:3070', defaults = {}) {
  return async function post(route, body, options = {}) {
    const response = await request(base + route, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      timeoutMs: 2500,
      ...defaults,
      ...options,
    });
    const value = await response.json();
    if (!response.ok || value.success === false) throw new Error(value.message || 'GATEWAY_UNAVAILABLE');
    return value;
  };
}

module.exports = { request, client, tokenFile };
