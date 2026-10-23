# 电脑与 NAS 远程接入（开发候选）

本轮新增独立 MCP stdio 客户端 `agent-mobile-use-remote 0.1.0-alpha`，需配套本轮构建的 `0.11.0-alpha` 手机模块。公开的 `0.10.0-alpha` 不包含控制租约接口；早期 0.11 候选也需更新。当前仅通过主机协议和打包测试，尚未完成手机跨网络验收。

项目：https://github.com/huantuoshen-prog/agent-mobile-use-hyperos

```text
电脑或 NAS 上的 Agent
    → 本机 MCP 子进程（Node.js）
    → 本机 127.0.0.1:23070
    → SSH 隧道（可通过 Tailscale 私网连接）
    → 手机 127.0.0.1:3070
    → Root 前台/虚拟副屏操作 + 超级岛实况/答题
```

手机网关继续监听回环地址。远程 Agent 使用模块的 Root 能力和通知接口，手机无需运行 DSHA Agent。DSHA 本机插件仍可单独使用；同一时间只安排一个控制任务。

远程任务通知的“查看”和完成通知点击进入模块控制台；答题进入模块答题页或岛内按钮。远程 Agent 的对话记录留在运行该 Agent 的电脑/NAS。

## 需要准备

- 手机：本轮候选 KSU 模块、Root；超级岛功能还需要受支持的 HyperOS、LSPosed 作用域、通知权限和控制中心开关。
- 电脑/NAS：Node.js 22 或以上、支持 stdio MCP 的 Agent、OpenSSH 客户端。
- 手机与电脑/NAS 之间有 SSH 通道。可以在同一 Tailscale 私网内使用普通 OpenSSH。Android 的 Tailscale 应用本身不提供 Tailscale SSH 服务端；手机上仍需 SSH 服务端，或采用下面的手机主动反向连接。
- 手机 SSH 组件可使用已安装的可信实现；需要安装时从 [Termux 官方说明](https://github.com/termux/termux-app#installation) 选择安装来源，在 Termux 安装 `openssh`。连接前设置密钥认证、核验主机指纹，并为该进程配置必要的后台运行权限。当前测试手机的 SSH 组件、端口和后台保活尚未核验。
- 将手机 `/data/adb/agent_mobile_use/gateway-token` 通过已认证通道保存为电脑/NAS 的私有文件。读取该手机路径需要已授权的 root。凭据不要写进 MCP 配置、命令参数、共享盘或聊天记录。客户端只接收凭据文件路径。

## 方案 A：电脑/NAS 连接手机 SSH

在运行 Agent 的电脑/NAS 配好 SSH 别名 `phone`。别名包含手机的 Tailscale 地址、实际 SSH 端口、用户名和私钥路径；各项值需按实际设备填写。先验证 `ssh phone` 能登录。

随后运行并保持隧道：

```sh
ssh -NT -L 127.0.0.1:23070:127.0.0.1:3070 -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=3 phone
```

每台电脑/NAS 都可以运行自己的本地转发。手机端控制租约拒绝第二个活动控制任务。

## 方案 B：手机主动连接 NAS

在手机 SSH 客户端配置并验证 `nas` 别名，指向 NAS 的 Tailscale 地址和 SSH 服务。手机执行：

```sh
ssh -NT -R 127.0.0.1:23070:127.0.0.1:3070 -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=3 nas
```

此时 NAS 上的 Agent 连接 NAS 自己的 `127.0.0.1:23070`。电脑上的 Agent 若要经该 NAS 接入，在电脑再运行：

```sh
ssh -NT -L 127.0.0.1:23070:127.0.0.1:23070 -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 -o ServerAliveCountMax=3 nas
```

NAS 的 SSH 策略需允许对应转发。转发端口明确绑定回环地址；无需把 3070 或 23070 发布到公网。SSH 断线后需重新建立通道，手机端旧任务会按租约回收；客户端不会自动重新执行屏幕动作。本轮未安装任何 SSH 自启动服务。

## 保存连接凭据

Linux/NAS 可在已授权 SSH 身份下直接保存到私有目录。以下 `su` 调用须已在手机上获得授权：

```sh
umask 077
mkdir -p "$HOME/.config/agent-mobile-use"
ssh phone 'su -c "cat /data/adb/agent_mobile_use/gateway-token"' > "$HOME/.config/agent-mobile-use/token"
chmod 600 "$HOME/.config/agent-mobile-use/token"
```

Windows 请把凭据保存为 UTF-8 文本文件，并通过文件属性中的“安全”设置将读取权限限定到运行 Agent 的账户和系统管理员。客户端在 Unix 检查 group/other 权限位；Windows ACL 需部署时核验。只有反向隧道时，可通过已授权 ADB 或手机端加密文件传输交付同一凭据。不要更改手机原始 token；DSHA 和控制台仍在使用它。

## 安装客户端与配置 MCP

候选 ZIP 为 `agent-mobile-use-remote-0.1.0-alpha.zip`。解压到固定目录，包含 `package.json`、`LICENSE`、`README.md` 与 `lib/`；运行无需 `npm install`。当前仓库可执行 `python tools/sync-version.py`、`python tools/package-remote.py` 生成本地包。完整发布时其 SHA-256 与模块、DSHA 插件一起写入配套 manifest 和校验文件。

MCP 配置示例（具体宿主的配置入口以其说明为准）：

```json
{
  "mcpServers": {
    "phone": {
      "command": "node",
      "args": [
        "/absolute/path/agent-mobile-use-remote/lib/remote-cli.cjs",
        "--endpoint", "http://127.0.0.1:23070",
        "--token-file", "/absolute/private/path/mobile-use-token"
      ]
    }
  }
}
```

Windows 的路径可写成 `C:/Tools/agent-mobile-use-remote/lib/remote-cli.cjs`；Node 不在宿主 PATH 时用 `node.exe` 的完整路径。源码运行入口为 `dsha-plugin/lib/remote-cli.cjs`。也可通过 `MOBILE_USE_BASE` 和 `MOBILE_USE_TOKEN_FILE` 指定相同配置。客户端目前只接受数字回环 HTTP 地址，经 SSH 加密传输；不提供远程 HTTP MCP 服务。

## 17 个工具与任务流程

| 用途 | 工具 |
| --- | --- |
| 任务及手机答题 | `mobile_task_begin`、`mobile_task_update`、`mobile_task_end`、`mobile_ask` |
| 状态诊断 | `mobile_status`、`mobile_diagnose`、`mobile_live_status` |
| 屏幕控制 | `mobile_start`、`mobile_mode`、`mobile_stop`、`mobile_resize` |
| 观察与操作 | `mobile_screenshot`、`mobile_ui_dump`、`mobile_action`、`mobile_apps` |
| 音频 | `mobile_audio_status`、`mobile_audio_toggle` |

`mobile_diagnose` 仍包含 DSHA 检查项；远程接入时 DSHA 缺失本身不妨碍模块 Root 能力。控制网关身份、Root、虚拟屏及通知状态仍需分别检查。

交给 Agent 的验收提示：

```text
使用 phone MCP 控制我的 Root 手机，只做系统设置界面的读取和截图。
先调用 mobile_status，再 mobile_task_begin(title="远程副屏验收")。
创建 mobile_start，切换 mobile_mode(mode="background")，用 mobile_action(action="launch_app", package="com.android.settings") 打开设置。
通过 mobile_ui_dump 或 mobile_screenshot 验证实际界面，用 mobile_task_update 报告进度。
调用 mobile_ask，问题 id="continue"，question="继续读取设置页面？"，options=[{"label":"继续"},{"label":"结束"}]。
等待手机选择后按照答案继续，不修改系统设置。
整轮结束必须调用 mobile_task_end，outcome 根据实际结果填写 completed、failed 或 cancelled。
如果手机停止了任务，不自动创建新任务继续操作；调用超时先核验状态，不自动重放点击。
```

通用 MCP 客户端无法观察宿主所有模型事件，因此完成态取决于 Agent 调用 `mobile_task_end`。`mobile_task_update` 只更新说明及进度；前台、后台阶段由成功的 `mobile_mode` 调用驱动。任务完成进度计数本身不会触发完成态。

`mobile_ask` 默认等待 120 秒，可设 `timeout_seconds` 为 5–600 秒。手机返回原始问题 ID、选项原文和自定义回答；超时、宿主取消或客户端退出后旧问题关闭。双按钮与答题面板复用手机模块。系统权限、支付授权及 DSHA intent 审核仍须走各自确认流程。

## 断线、停止与排障

- 每次任务使用新的随机控制身份，旧任务的延迟请求无法取得新任务的控制权；手机端租约 30 秒，客户端每 10 秒续期并发送任务心跳，每秒检查手机停止命令。
- 手机停止只中断当前手机控制会话，无法保证终止外部 Agent 的全部推理或其他工具。后续屏幕调用要求重新显式开始任务。
- 正常结束及 stdio 退出会释放控制、切换 idle、停止副屏并恢复模块记录的音频设置。进程被强杀或网络中断时，由手机检查租约过期并清理。在途操作必须结束后才交给下一控制者；已执行的操作无法撤销。
- `cleanup_pending: true` 表示客户端尚未确认手机回收，需等待后读取状态；不要把它报告为已回收。
- `CONTROL_BUSY_OR_EXPIRED`：另一控制者、仍活动的本机 DSHA 实况任务、过期会话或清理过程占用手机。先结束已有任务；过期后等待回收，再显式重试任务开始。
- `TASK_NOT_ACTIVE`：当前进程没有活动任务，或任务已被手机停止。
- `GATEWAY_HTTP_404` 出现在任务开始：通常是手机仍运行缺少租约接口的旧构件，核对候选包 SHA-256。
- `GATEWAY_HTTP_401`：核对凭据文件；更换或重装模块后的凭据可能不同。
- 连接失败：先核验 `ssh phone` / `ssh nas` 和本地转发监听，再检查手机网关。不要把旧无线 ADB 端口当作 SSH 端口。

## 验证边界与依据

主机测试运行真实 Node 子进程和 JSON-RPC stdio，覆盖工具声明、任务状态、图像返回、手机问答、取消、停止、心跳、退出清理及两个客户端竞争；网关测试单独覆盖租约所有权、失效、在途操作与手机回答入口。模拟网关结果不代表 Android/SystemUI 已验收。

待真机验证：Tailscale 跨网可达、手机 SSH 后台存活、重启恢复、实际前台/副屏切换、超级岛展开答题、手机停止与断线后的 idle/stopped。旧 DSHA Web 插件目录加载故障仍独立待排查。

协议及传输依据：[MCP stdio](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[OpenSSH 的 -L/-R](https://man.openbsd.org/ssh)、[Tailscale SSH 支持平台](https://tailscale.com/docs/features/tailscale-ssh)。
