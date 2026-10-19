# dsh-mobile-use（DSHA 插件）

把 [agent-mobile-use](../README.md) 的本地网关 `http://127.0.0.1:3070` 包装成 DSHA 可用的 DSH 工具。

- 宿主：DSHA（`com.dsh.client`）内置的 DSH Web 运行时
- 形态：Cordis 插件 + MCP stdio server（不监听端口、不引入新鉴权）
- 依赖的网关：KernelSU 模块 `agent_mobile_use`（`vd_server`，只监听 `127.0.0.1:3070`）
- 工具对外名：`mcp__mobile-use__<tool>`

## 与 DSHA 内置能力的关系

| 能力 | 内置 `dsha-android`（:3090） | 内置 `dsha-vscreen`（:3090） | 本插件（:3070） |
| --- | --- | --- | --- |
| 操作屏幕 | 主屏（无障碍，无需 root） | App 侧虚拟屏 | DisplayManager 真虚拟屏（root） |
| 虚拟屏 | 无 | 有（实验） | 有，且与主屏隔离、IME 隔离 |
| 通知 | 普通通知 | 普通通知 | HyperOS 超级岛（胶囊/完成/提问） |
| 依赖 | 无障碍服务 | ADB/Shizuku/Root 任一 | KernelSU/LSPosed 模块 |

`@deepseek-ai/dsh-computer-use` 的 provider 在全进程只能注册一个，DSHA 已被
`dsha-computer-use-android` 占用，因此本插件**只注册普通 tools**，不注册
computer-use provider。

## 工具

| 工具 | 作用 |
| --- | --- |
| `mobile_status` | 网关状态：mode、display_id、尺寸、会话、LSPosed 是否生效 |
| `mobile_start` / `mobile_stop` | 创建 / 回收 root 虚拟屏 |
| `mobile_mode` | `foreground`（主屏 display 0） / `background`（虚拟屏，迁移当前任务） |
| `mobile_screenshot` | 截取当前目标显示（JPEG，MCP image content） |
| `mobile_ui_dump` | 读无障碍控件树（`no_system_ui`/`raw` 可选） |
| `mobile_action` | `observe/click/set_value/swipe/key/launch_app/wait` |
| `mobile_apps` | 列出已安装应用（`query` 过滤） |
| `mobile_notify` | 发任务通知；虚拟屏模式下走超级岛（`total/completed/is_completed`） |
| `mobile_question` | 投递交互提问卡片到设备（回答不经网关回传） |
| `mobile_question_cancel` | 取消未回答的提问 |

## 安装

前提：设备已装 KernelSU/LSPosed 模块 `agent_mobile_use`，且
`curl http://127.0.0.1:3070/api/status` 在设备上可用（DSHA 侧通过设备 shell 执行）。

推荐用 DSHA 自带插件管理器：

```sh
# 在 DSHA 的设备 shell 里
dsha-plugin import /sdcard/Download/dsh-mobile-use-0.1.0.zip
# 或从目录安装
dsha-plugin install /root/dsh-mobile-use
```

装完必须重启 DSHA Web（App 内「进入对话」触发一次新的 `STARTUP_STAGE`），
插件才会被加载。

手工安装（等价于 `import` 做的事）：

1. 把本目录复制到 guest `/root/dsh-mobile-use`
2. `ln -s ../../usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules /root/dsh-mobile-use/node_modules`
3. `/root/.dsh/profiles/web/package.json` 增加
   `"dsh-mobile-use": "link:/root/dsh-mobile-use"`（dependencies）与 bundles 条目
4. 启动快照 `startup-config-snapshots/<uuid>/0` 同步添加同两条
5. 重启 DSHA Web

## 验证

1. 加载：`grep -a dsh-mobile-use /root/dsh-web.log`，应出现
   `[DSHA_STARTUP] ... "type":"loading","plugin":"dsh-mobile-use"` 与 `"type":"loaded"`。
2. 工具出现：DSHA 会话的工具列表里有 `mcp__mobile-use__mobile_status`。
3. 端到端：
   - `mcp__mobile-use__mobile_status` → `{"status":"stopped", ...}`
   - `mcp__mobile-use__mobile_start` → `display_id`
   - `mcp__mobile-use__mobile_mode {"mode":"background"}`
   - `mcp__mobile-use__mobile_action {"action":"launch_app","package":"com.android.settings"}`
   - `mcp__mobile-use__mobile_screenshot` → image
4. 超级岛：控制台开「超级岛通知（HyperOS）」后，
   `mcp__mobile-use__mobile_notify` 会在状态栏出现胶囊岛。

## 回滚

```sh
# 恢复 profile 与启动快照备份（安装时生成的 .bak-*），删除：
rm -rf /root/dsh-mobile-use
rm -f  /root/.dsh/profiles/web/node_modules/dsh-mobile-use
```

再重启 DSHA Web。

## 安全边界

- 网关 `3070` **无鉴权**，只监听回环；本插件不改变这一点。
- 不要把 3070 暴露到局域网，也不要在其上叠加宽松 CORS 的网页调用。
- 虚拟屏需要 root 模块；插件本身不申请任何新权限。
