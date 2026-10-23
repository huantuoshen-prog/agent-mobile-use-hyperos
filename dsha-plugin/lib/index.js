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
 * MCP stdio 桥接与宿主生命周期事件共用回环网关，读取应用私有 token。
 */
import * as McpClient from '@deepseek-ai/dsh-mcp-client';
import { fileURLToPath } from 'node:url';
import gateway from './gateway.cjs';
import version from './version.cjs';
import { randomUUID } from 'node:crypto';
import { installLifecycle } from './lifecycle.js';

export const name = 'dsh-mobile-use';
export const inject = ['tools'];

const PROMPT = [
  '【手机 Agent · 实况通知】dsh-mobile-use 提供 KernelSU 虚拟屏工具，3070 网关需要私有 token。',
  '任务开始、主屏控制、后台副屏、等待用户、完成、失败和停止会由真实任务事件自动更新超级岛。',
  '正常完成以 turn/end completed 为准；工具调用或子步骤结束不表示任务完成。无需手工发送完成提醒。',
  '先 mobile_status；后台操作用 mobile_start → mobile_mode(background) → mobile_ui_dump/mobile_screenshot → mobile_action。',
  '前台操作可使用 DSHA 的 dsha-android；它操作用户主屏。DSHA App 侧虚拟屏与 root 虚拟屏是两个后端，不混用帧和坐标。',
  '操作前后读取最新界面；切换、缩放或旋转后重新读屏；动作失败不自动换通道重放。',
  'mobile_notify 只用于补充用户可见进度，total/completed 必须来自真实任务清单，不能编造百分比。',
  '交互提问使用宿主 ask_user_question；插件自动转为等待回答岛，并在真实回答后恢复。mobile_question 仅投递问题通知。',
  '用户从岛请求停止后要取消当前任务；已提交的外部操作可能已经发生，先核对实际状态。',
  '结束屏幕使用后 mobile_mode(idle)，不用虚拟屏时 mobile_stop。超级岛开关关闭时保留普通通知，不反复重试。',
].join('\n');
export async function apply(ctx) {
  installLifecycle(ctx);
  const config = McpClient.Config({
    transport: 'stdio',
    serverName: 'mobile-use',
    command: process.execPath,
    args: [fileURLToPath(new URL('./server.cjs', import.meta.url))],
    failOnStartupError: false,
    toolCallTimeoutMs: 180000,
    reconnect: { enabled: false },
  });
  let child;
  ctx.effect(function* () {
    child = ctx.plugin(McpClient, config);
    yield child.dispose;
  }, 'dsh-mobile-use-tools');
  await child.await();
  const instance = randomUUID(), post = gateway.client();
  const register = () => {
    const count = ctx.tools.schemas().filter(t => t.name.startsWith('mcp__mobile-use__')).length;
    void post('/api/integration', { version: version.pluginVersion, instance_id: instance, tools_registered: count }).catch(() => {});
  };
  register();
  const healthTimer = setInterval(register, 30000);
  healthTimer.unref?.();
  ctx.on('dispose', () => clearInterval(healthTimer));

  // 首选：系统提示注入（标准配置）。apply 完成条件由 loader 等待，所以放在 MCP 就绪之后。
  ctx.inject(['systemPrompt'], promptCtx => {
    promptCtx.systemPrompt.section({ name: 'dsh:mobile-use-guide', order: 160, text: PROMPT });
  });
}
