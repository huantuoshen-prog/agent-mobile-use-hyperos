/**
 * dsh-mobile-use —— 把 agent-mobile-use 的 root 虚拟屏网关（127.0.0.1:3070）
 * 暴露成 DSH 工具，并用系统提示告诉模型什么时候该用它们（含超级岛）。
 *
 * 与 DSHA 内置的两套设备能力的关系：
 *   - dsha-android（:3090 无障碍桥）：操作当前主屏，无需 root。
 *   - dsha-vscreen（:3090 原生虚拟屏）：App 侧虚拟屏工具。
 *   - dsh-mobile-use（本项目 :3070）：KernelSU/LSPosed 模块提供的
 *     DisplayManager 真虚拟屏 + SystemUI hook + HyperOS 超级岛通知。
 *
 * 本插件只做 MCP stdio 桥接，所有真实动作都发给 3070；3070 只监听回环，
 * 插件不引入任何额外鉴权或网络暴露。
 */
import * as McpClient from '@deepseek-ai/dsh-mcp-client';
import { fileURLToPath } from 'node:url';

export const name = 'dsh-mobile-use';
export const inject = ['tools'];

const PROMPT = [
  '【移动端虚拟屏与超级岛 · dsh-mobile-use】本机已装 agent-mobile-use（KernelSU/LSPosed 模块），',
  '它提供独立虚拟屏 + HyperOS 超级岛通知，工具名是 mcp__mobile-use__mobile_*。',
  '',
  '■ 什么时候用它，而不是 dsha-android（:3090 无障碍主屏）：',
  '  - 需要在后台跑手机任务、不打断用户当前屏幕时：用本插件的虚拟屏（background）。',
  '  - 只需要在主屏点一下、看当前界面时：用 dsha-android 的 /app/ui/* 更省事。',
  '  - 用户要求“任务进度/完成提醒上岛”“在状态栏看到你在干活”时：必须用本插件的 mobile_notify / mobile_question。',
  '',
  '■ 标准顺序：',
  '  1) mobile_status 先看状态；stopped 就先 mobile_start（创建虚拟屏）。',
  '  2) mobile_mode：foreground=用户主屏，background=虚拟屏（会把当前任务迁过去）。',
  '     截图/读屏之前必须切到非 idle，idle 模式截图会 404。',
  '  3) mobile_ui_dump 读当前界面 → 决定动作 → mobile_action（click/swipe/set_value/key/launch_app/wait）→ 再 dump 验证。',
  '  4) 进度与完成：mobile_notify（title/session_title/content/session_id，完成时 is_completed=true）→ 手机上出现超级岛/完成通知。',
  '  5) 需要用户拍板：mobile_question（questions=[{header,question}]）→ 用户从岛/通知进 App 回答；回答不经网关回传，',
  '     所以提问后要告诉用户“请在手机上回答”。',
  '  6) 收尾：mobile_stop 关虚拟屏。',
  '',
  '■ 超级岛的前置条件（重要）：',
  '  - 需要在手机桌面 App「Agent Mobile Use → 控制台」里打开「超级岛通知（HyperOS）」开关；',
  '  - 没打开时 mobile_notify 仍会成功，但只是普通通知、不出岛；不要反复重试工具，直接告诉用户去控制台打开该开关；',
  '  - 超级岛依赖 HyperOS 的焦点通知校验，本模块已用 LSPosed hook 放行；若岛不出现，先让用户确认开关状态。',
  '',
  '■ 边界：',
  '  - 3070 只监听回环且无鉴权，禁止把它暴露到局域网；',
  '  - 动作要基于最近一次 dump 的坐标，别凭记忆连点；',
  '  - 与用户交流一律用中文。',
].join('\n');

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

  // 首选：系统提示注入（标准配置）。apply 完成条件由 loader 等待，所以放在 MCP 就绪之后。
  ctx.inject(['systemPrompt'], promptCtx => {
    promptCtx.systemPrompt.section({ name: 'dsh:mobile-use-guide', order: 160, text: PROMPT });
  });
}
