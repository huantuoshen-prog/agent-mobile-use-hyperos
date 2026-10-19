# DSHA 对接：把 3070 网关接进 DSHA 的 DSH 会话

DSHA（`com.dsh.client`）是 Android 上的 DSH 宿主，内部跑一个 DSH Web 运行时（guest rootfs + proot），
自带 App 桥 `127.0.0.1:3090` 和两套设备能力：`dsha-android`（无障碍操作主屏）与
`dsha-vscreen`（App 侧虚拟屏）。本项目提供的是另一条通路：KernelSU/LSPosed 模块 +
`vd_server` 根虚拟屏 + HyperOS 超级岛，监听 `127.0.0.1:3070`。

本目录下的 `dsha-plugin/` 就是连接两边的插件：一个 Cordis 插件 + MCP stdio server，
把 3070 的能力注册成 DSH 工具（`mcp__mobile-use__*`）。

## 为什么是普通 tools，而不是 computer-use provider

`@deepseek-ai/dsh-computer-use@0.2.0-rc.2` 的 `ComputerUseRegistry.register(name)`
在同一进程只允许一个 provider（重复注册抛
`computer use provider "<name>" is already registered`）。DSHA 内置的
`dsh-computer-use-android` 已占用该位置，因此 `dsh-mobile-use` 只注册普通 `tools`，
与内置能力平行存在、互不替换。

## 两条通路怎么选

| 场景 | 用哪条 |
| --- | --- |
| 让 Agent 操作手机主屏、不需要 root | `dsha-android`（:3090） |
| 想在 App 内用 DSHA 自带实验虚拟屏 | `dsha-vscreen`（:3090） |
| 想要与主屏完全隔离的 root 虚拟屏、后台跑任务 | `dsh-mobile-use`（:3070） |
| 想要 HyperOS 超级岛提示（胶囊/完成/提问） | `dsh-mobile-use`（:3070） |

## 安装与验证

见 [`dsha-plugin/README.md`](../dsha-plugin/README.md)。要点：

1. 设备上 3070 网关可用（KernelSU 模块已启用）。
2. `dsha-plugin import <zip>`（或手工放入 guest `/root/dsh-mobile-use` 并写 profile）。
3. 重启 DSHA Web（App 内点「进入对话」）。
4. 会话里调用 `mcp__mobile-use__mobile_status` 确认工具已加载。

## 已验证与未验证

已验证（Windows 侧、对真机网关）：

- MCP stdio 握手：`initialize` 返回 `serverInfo dsh-mobile-use/0.1.0`
- `tools/list` 返回 11 个工具
- `mobile_status` 返回真实状态 JSON；`mobile_ui_dump` 返回控件树；
  `mobile_screenshot` 返回 JPEG（MCP image content）
- 参数校验：非法 mode/多余参数/缺必填均以 `isError` 拒绝
- 插件目录、`cordis.patch.yml`、profile/启动快照写入格式与 DSHA 内置插件一致

未验证：

- DSHA Web 重启后的「注册插件 / 加载」阶段（设备当时处于锁屏，无法在 App 内点「进入对话」）
- 在 DSHA 会话里真实调用 11 个工具

## 边界

- 3070 无鉴权、只监听回环：插件不改变这一点，也不应把 3070 暴露到 LAN。
- root 虚拟屏需要模块正常加载；`system_server` 的 wm/IME hook 在 HyperOS 4 上
  仍有 `ClassNotFoundException`（不阻塞虚拟屏的现有能力），详见
  [hyperos.md](hyperos.md)。
- 提问回答不经 3070 回传：`/api/question` 只负责把问题投到设备。
