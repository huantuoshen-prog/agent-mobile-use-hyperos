#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');

function configure(argv) {
  let endpoint = process.env.MOBILE_USE_BASE || 'http://127.0.0.1:23070';
  let tokenFile = process.env.MOBILE_USE_TOKEN_FILE;
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--endpoint' && argv[i + 1]) endpoint = argv[++i];
    else if (argv[i] === '--token-file' && argv[i + 1]) tokenFile = argv[++i];
    else throw new Error('USAGE: node remote-cli.cjs --endpoint http://127.0.0.1:23070 --token-file /private/path/mobile-use-token');
  }
  const url = new URL(endpoint);
  if (url.protocol !== 'http:' || !['127.0.0.1', '[::1]'].includes(url.hostname) || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error('ENDPOINT_REQUIRES_LOOPBACK_SSH_FORWARD');
  if (!tokenFile || !path.isAbsolute(tokenFile)) throw new Error('TOKEN_FILE_REQUIRES_ABSOLUTE_PATH');
  let stat;
  try { stat = fs.statSync(tokenFile); }
  catch { throw new Error('TOKEN_FILE_UNAVAILABLE'); }
  if (!stat.isFile() || stat.size > 512 || (process.platform !== 'win32' && (stat.mode & 0o077))) throw new Error('TOKEN_FILE_MUST_BE_PRIVATE');
  let token;
  try { token = fs.readFileSync(tokenFile, 'utf8').trim(); }
  catch { throw new Error('TOKEN_FILE_UNAVAILABLE'); }
  if (!/^[0-9a-f]{64}$/i.test(token)) throw new Error('INVALID_TOKEN_FILE');
  process.env.MOBILE_USE_BASE = url.origin;
  process.env.MOBILE_USE_TOKEN_FILE = tokenFile;
  return url.origin;
}

if (require.main === module) {
  try {
    const endpoint = configure(process.argv.slice(2));
    // Load after configuration: the shared tools capture their endpoint at import.
    const { RemoteClient, tools } = require('./remote.cjs');
    const remote = new RemoteClient(endpoint);
    require('./server.cjs').startServer({ tools, call: remote.call.bind(remote), onClose: () => remote.close(),
      serverInfo: { name: 'agent-mobile-use', version: require('./version.cjs').remoteVersion },
      instructions: '先 mobile_task_begin，再 start/mode、读屏和操作；用 mobile_task_update 报告实际进度，mobile_ask 等待手机回答，整轮结束调用 mobile_task_end。手机停止会中断本控制会话；客户端断开会回收副屏。动作超时结果可能未知，先读屏核验，勿自动重放。',
      errorHint: 'Check the SSH forward, private token file and candidate module. CONTROL_BUSY_OR_EXPIRED requires ending the other controller or waiting for cleanup; TASK_NOT_ACTIVE requires a new mobile_task_begin.',
    });
  } catch (error) {
    process.stderr.write((/^[A-Z_]+$/.test(error.message) || error.message.startsWith('USAGE:') ? error.message : 'REMOTE_CLIENT_CONFIGURATION_ERROR') + '\n');
    process.exitCode = 1;
  }
}
module.exports = { configure };
