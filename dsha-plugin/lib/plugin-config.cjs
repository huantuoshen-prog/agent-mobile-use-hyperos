'use strict';
const path = require('node:path');

// A path may be passed to the MCP subprocess; the token itself never is.
function resolvePluginConfig(input = {}, env = process.env) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('INVALID_MOBILE_USE_CONFIG');
  const mode = input.mode || env.MOBILE_USE_MODE || 'dsha';
  if (mode !== 'dsha' && mode !== 'remote') throw new Error('INVALID_MOBILE_USE_MODE');
  const home = env.HOME || env.USERPROFILE || '/root';
  const endpoint = input.endpoint || env.MOBILE_USE_BASE || (mode === 'remote' ? 'http://127.0.0.1:23070' : 'http://127.0.0.1:3070');
  const tokenFile = input.tokenFile || env.MOBILE_USE_TOKEN_FILE || (mode === 'remote'
    ? path.join(home, '.config', 'agent-mobile-use', 'token')
    : path.join(home, '.dsh', 'mobile-use-token'));
  if (typeof endpoint !== 'string' || typeof tokenFile !== 'string') throw new Error('INVALID_MOBILE_USE_CONFIG');
  return { mode, endpoint, tokenFile };
}

module.exports = { resolvePluginConfig };
