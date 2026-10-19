/**
 * dsh-mobile-use —— 把 agent-mobile-use 的 root 虚拟屏网关（127.0.0.1:3070）
 * 暴露成 DSH 工具。
 *
 * 与 DSHA 内置的两套设备能力的关系：
 *   - dsha-android（:3090 无障碍桥）：操作当前主屏，无需 root。
 *   - dsha-vscreen（:3090 原生虚拟屏）：App 侧虚拟屏工具。
 *   - dsh-mobile-use（本项目 :3070）：KernelSU/LSPosed 模块提供的
 *     DisplayManager 真虚拟屏 + SystemUI hook + 超级岛通知。
 *
 * 本插件只做 MCP stdio 桥接，所有真实动作都发给 3070；3070 只监听回环，
 * 插件不引入任何额外鉴权或网络暴露。
 */
import * as McpClient from '@deepseek-ai/dsh-mcp-client';
import { fileURLToPath } from 'node:url';

export const name = 'dsh-mobile-use';
export const inject = ['tools'];

export async function apply(ctx) {
  const config = McpClient.Config({
    transport: 'stdio',
    serverName: 'mobile-use',
    command: process.execPath,
    args: [fileURLToPath(new URL('./server.cjs', import.meta.url))],
    failOnStartupError: true,
    toolCallTimeoutMs: 180000,
    reconnect: { enabled: false },
  });
  let child;
  ctx.effect(function* () {
    child = ctx.plugin(McpClient, config);
    yield child.dispose;
  }, 'dsh-mobile-use-tools');
  await child.await();
}
