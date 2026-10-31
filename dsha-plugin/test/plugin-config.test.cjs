'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { resolvePluginConfig } = require('../lib/plugin-config.cjs');

test('DSHA installation keeps its existing local endpoint and token location', () => {
  assert.deepEqual(resolvePluginConfig({}, { HOME: '/guest' }), {
    mode: 'dsha', endpoint: 'http://127.0.0.1:3070', tokenFile: path.join('/guest', '.dsh', 'mobile-use-token'),
  });
});

test('a generic DSH profile can select a private token and forwarded endpoint', () => {
  const env = { HOME: '/home/agent', MOBILE_USE_MODE: 'remote', MOBILE_USE_BASE: 'http://127.0.0.1:23071', MOBILE_USE_TOKEN_FILE: '/private/env-token' };
  assert.deepEqual(resolvePluginConfig({ endpoint: 'http://127.0.0.1:23072', tokenFile: '/private/profile-token' }, env), {
    mode: 'remote', endpoint: 'http://127.0.0.1:23072', tokenFile: '/private/profile-token',
  });
  assert.equal(resolvePluginConfig({ mode: 'remote' }, { HOME: '/home/agent' }).tokenFile, path.join('/home/agent', '.config', 'agent-mobile-use', 'token'));
  assert.throws(() => resolvePluginConfig({ mode: 'unexpected' }, {}), /INVALID_MOBILE_USE_MODE/);
});
