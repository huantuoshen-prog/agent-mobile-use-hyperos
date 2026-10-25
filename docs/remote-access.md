# 电脑、NAS 与 VPS 远程接入（0.11）

0.11 提供独立 MCP stdio 客户端 `agent-mobile-use-remote 0.1.0-alpha`，需配套 `0.11.0-alpha` 手机模块。0.10 不包含控制租约接口；早期 0.11 候选应更新到本次配套构件。已通过真实手机的局域网 SSH 控制、副屏截图、手机答题和租约失联回收；VPS 经既有 Tailscale 子网路由的完整模型验收已通过，NAS 独立 Agent 接入仍待验收。

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

- 手机：0.11 配套 KSU 模块、Root；超级岛功能还需要受支持的 HyperOS、LSPosed 作用域、通知权限和控制中心开关。
- 电脑/NAS：Node.js 22 或以上、支持 stdio MCP 的 Agent、OpenSSH 客户端。
- 手机与电脑/NAS 之间有 SSH 通道。可以在同一 Tailscale 私网内使用普通 OpenSSH。Android 的 Tailscale 应用本身不提供 Tailscale SSH 服务端；手机上仍需 SSH 服务端，或采用下面的手机主动反向连接。
- 手机 SSH 组件可使用已安装的可信实现；需要安装时从 [Termux 官方说明](https://github.com/termux/termux-app#installation) 选择安装来源，在 Termux 安装 `openssh`。连接前设置密钥认证、核验主机指纹，并为该进程配置必要的后台运行权限。测试机已验证 Termux OpenSSH 的局域网连接；Android 17 的 Termux 还需允许“访问本地网络”，缺少该权限时即使端口已监听也会连接超时。
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

NAS 的 SSH 策略需允许对应转发。转发端口明确绑定回环地址；无需把 3070 或 23070 发布到公网。SSH 断线后需重新建立通道，手机端旧任务会按租约回收；客户端不会自动重新执行屏幕动作。

## 可选：重启后启动既有 Termux SSH

本轮模块新增 `bin/start_remote_ssh.sh`，只启动已经配置好的 Termux OpenSSH；默认关闭。专用配置固定为 Termux home 下 `.ssh/mobile-use/sshd_config`，其中 `PidFile` 应指向同目录 `sshd.pid`。建议配置独立公钥文件、仅公钥认证、只允许转发到 `127.0.0.1:3070`，并明确绑定需要的私网地址。配置样例的关键字段如下，host key 路径按 Termux 的实际安装路径填写：

```text
Port 8022
ListenAddress 127.0.0.1
ListenAddress <手机实际私网IP>
PidFile /data/data/com.termux/files/home/.ssh/mobile-use/sshd.pid
AuthorizedKeysFile /data/data/com.termux/files/home/.ssh/mobile-use/authorized_keys
HostKey /data/data/com.termux/files/usr/etc/ssh/ssh_host_ed25519_key
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
AuthenticationMethods publickey
AllowTcpForwarding local
PermitOpen 127.0.0.1:3070
AllowAgentForwarding no
X11Forwarding no
PermitTTY no
ForceCommand /system/bin/false
```

以上专用实例只提供端口转发，不能执行登录 shell 或读取 token；凭据应先经 ADB/其他已授权通道交付。文档后面的 `ssh phone 'su ...'` 凭据示例仅适用于已有普通 SSH 登录权限的通道。

验证专用配置和私网地址后，在手机 root shell 创建 `/data/adb/agent_mobile_use/remote-ssh-enabled` 空文件，并设置为 `0600`；运行 `/data/adb/modules/agent_mobile_use/bin/start_remote_ssh.sh` 验证。启用后模块在开机首次解锁后调用该入口，日志在同一私有目录 `remote-ssh.log`。删除这个开关会停用下次开机启动；当前已运行的 SSH 进程需单独停止。模块不生成密钥、不修改既有 SSH 配置，也不安装 Termux。DHCP 地址变化后应更新专用配置，再重启该 SSH 实例。该入口执行前恢复专用目录的 App SELinux 标签。

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

客户端 ZIP 为 `agent-mobile-use-remote-0.1.0-alpha.zip`。解压到固定目录，包含 `package.json`、`LICENSE`、`README.md` 与 `lib/`；运行无需 `npm install`。当前仓库可执行 `python tools/sync-version.py`、`python tools/package-remote.py` 生成本地包。完整发布时其 SHA-256 与模块、DSHA 插件一起写入配套 manifest 和校验文件。

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
- 监视请求遇到超时、连接故障或 HTTP 429/5xx，会在当前已确认租约内继续检查；鉴权失败、控制权被拒绝、租约过期或停止命令通道连续失联 30 秒会结束任务。实况通知临时失败后仍会检查手机停止命令。
- 截图通过网关调用系统 `screencap`，不需要 DSHA App 常驻。SurfaceFlinger 查询和截图命令共用 10 秒截止；超时返回 `SCREENSHOT_TIMEOUT`（HTTP 504），该次工具失败后任务仍可读屏、操作和收尾。手机停止或客户端取消仍会中断截图。
- 手机停止只中断当前手机控制会话，无法保证终止外部 Agent 的全部推理或其他工具。后续屏幕调用要求重新显式开始任务。
- 正常结束及 stdio 退出会释放控制、切换 idle、停止副屏并恢复模块记录的音频设置。进程被强杀或网络中断时，由手机检查租约过期并清理。在途操作必须结束后才交给下一控制者；已执行的操作无法撤销。
- `cleanup_pending: true` 表示客户端尚未确认手机回收，需等待后读取状态；不要把它报告为已回收。
- `mobile_task_end` 在自动停止后或重复调用时返回最近一次任务的原始结果，包含 `session_id`、`turn_id`、`outcome`、`detail` 和通知/回收确认。监视故障导致结束时另有 `failure_code`；回收待确认时另有 `cleanup_detail`。调用者传入的新 outcome 不会覆盖已结束任务的结果；显式开始下一任务后，收尾结果归属于新任务。记录仅保存在当前客户端进程内。
- HyperOS 的模块 App PID 子组可独立于 UID 父组被冻结；网关会按模块自身 UID/PID 解冻对应 cgroup v2 节点，使任务通知和完成广播可被 App 处理。
- `CONTROL_BUSY_OR_EXPIRED`：另一控制者、仍活动的本机 DSHA 实况任务、过期会话或清理过程占用手机。先结束已有任务；过期后等待回收，再显式重试任务开始。
- `TASK_NOT_ACTIVE`：屏幕工具调用时没有活动任务；`mobile_task_end` 调用时当前进程也没有保留的收尾结果。
- `GATEWAY_HTTP_404` 出现在任务开始：通常是手机仍运行缺少租约接口的旧构件，核对候选包 SHA-256。
- `GATEWAY_HTTP_401`：核对凭据文件；更换或重装模块后的凭据可能不同。
- 连接失败：先核验 `ssh phone` / `ssh nas` 和本地转发监听，再检查手机网关。不要把旧无线 ADB 端口当作 SSH 端口。

## 验证边界与依据

主机测试运行真实 Node 子进程和 JSON-RPC stdio，覆盖工具声明、任务状态、图像返回、手机问答、取消、停止、心跳、退出清理及两个客户端竞争；网关测试单独覆盖租约所有权、失效、在途操作与手机回答入口。模拟网关结果不代表 Android/SystemUI 已验收。

2026-10-24/25 真机记录：先经 ADB 转发 SSH 验证副屏创建、设置页读屏、截图与岛内答案返回电脑；授予 Termux 本地网络权限后，电脑经局域网 SSH 完成相同副屏操作和正常回收。强杀 MCP 客户端后，手机约 31.9 秒自动回到 idle/stopped。DSHA 原生 Web 的加载故障经修复插件文件和链接的 App SELinux 标签后恢复；插件已标记 loaded，真实 DSHA 调用 `mobile_status` 并收到岛内回答“继续验收”。

2026-10-25 VPS 完整模型验收：11 次手机工具调用均成功，覆盖任务开始、后台副屏、系统设置读屏、JPEG 截图、进度、手机原始答案“验收通过”、completed 通知及最终 idle/stopped。完成接口返回 notification_confirmed=true、cleanup_pending=false，持久化调用记录与实时状态复查一致。

2026-10-25 恢复验收：首次解锁后手机 SSH 自动启动，VPS 的既有转发服务自动重连；Wi-Fi 中断约 56 秒再接回原地址后也恢复，DSH 主进程保持运行。锁屏副屏修正后，主屏锁定且息屏时已验证设置页读屏、截图与正常回收。

2026-10-26 Issue #1 修复候选：主屏持续 `Dozing` 时两次截图、后续读屏、重复收尾及完成通知通过；VPS 的实际 stdio 客户端在验收代理注入截图 504、监视 503 后仍可继续读屏截图，手机网关停止后可查询原始取消结果，取消通知载荷已核对。候选尚未发布，范围及 App 进程冻结现场见 [Issue #1 验收记录](issue-1-screenshot.md)。

仍待真机验证：NAS 独立 Agent 接入、更长时间后台存活、全部复杂答题与通知停止组合。其他 Wi-Fi/DHCP 地址变化仍按上文调整 SSH 绑定地址。公开发布状态以 Release 和配套 manifest 为准。

协议及传输依据：[MCP stdio](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)、[OpenSSH 的 -L/-R](https://man.openbsd.org/ssh)、[Tailscale SSH 支持平台](https://tailscale.com/docs/features/tailscale-ssh)。

## VPS 上的 DSH

有 Tailscale 子网路由时，先在 VPS 验证到手机 Wi-Fi 地址的路由及 SSH TCP 连通性。已发布子网路由、节点在线和 ping 成功各自不足以证明手机 SSH 可达；VPS 应能实际读取手机 SSH 服务响应。随后使用方案 A，从 VPS 本机建立到手机网关的 SSH 转发。

DSH 使用 `@deepseek-ai/dsh-mcp-client`，配置写到实际运行 profile 的 `cordis.patch.yml`。保留已有 patch 条目，追加一个 `insert` 操作；以下路径均需替换为实际绝对路径：

```yaml
- insert:
    - id: mcp-phone
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: phone
        transport: stdio
        command: /absolute/path/to/node
        args:
          - /absolute/path/phone-mcp/client/lib/remote-cli.cjs
          - --endpoint
          - http://127.0.0.1:23070
          - --token-file
          - /absolute/private/path/gateway-token
        toolCallTimeoutMs: 180000
        failOnStartupError: false
        reconnect:
          enabled: false
```

Node 可使用 DSH 部署自带的 22+ 运行时；系统 PATH 中的 Node 可能是另一版本。客户端、token、SSH 私钥和隧道应由实际运行 DSH 的账户读取/启动；token 和私钥权限为 `0600`。VPS 使用自己的手机 SSH 密钥，将公钥加入手机专用授权文件，保留已有公钥。转发成功后启动/重载 DSH，在工具列表确认 `mcp__phone__mobile_status` 等 17 个工具。

VPS Agent 用 `mobile_task_begin` / `mobile_task_update` / `mobile_task_end` 报告手机实况，用 `mobile_ask` 将问题交给手机；宿主自身的其他提问入口仍按宿主处理。任务超过默认 120 秒答题等待时，还需相应提高 DSH 的调用超时。

本轮已用实际 DSH `0.2.1-rc.2` 的 `assertSupportedJsonSchema` 验证全部 17 个工具。修复前进度和提问工具因 minimum/maximum/minItems/maxItems 被拒绝；现统一转换为 DSH 支持的 schema 子集，范围继续由执行端校验。Node 回归测试同时验证负进度和超出答题等待范围不会提交到手机。
