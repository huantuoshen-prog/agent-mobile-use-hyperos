# 通用 DSH 宿主接入

当前开发源码的 `dsh-mobile-use` bundle 支持两种模式：

| 模式 | 宿主 | 工具和任务状态 |
| --- | --- | --- |
| `dsha` | DSHA App 内的 DSH guest；默认模式 | 16 个工具；跟随宿主事件同步状态和答题 |
| `remote` | 通用 DSH profile，可运行在电脑、NAS、VPS 或能运行 DSH 的其他环境 | 17 个工具；通过显式任务工具持有手机控制租约 |

手机需要运行兼容的 `agent_mobile_use` Root 模块和网关。超级岛需要受支持的设备及模块配置；其他系统的具体适配状态见[模块文档](https://github.com/huantuoshen-prog/agent-mobile-use-hyperos/blob/main/docs/hyperos.md)。通用模式不要求手机安装或运行 DSHA。不同宿主的 Node.js 运行时需为 22 或以上。

此功能尚未包含在 v0.11.0-alpha 的 `dsh-mobile-use-0.4.0.zip` 中。以下使用当前开发源码打包；生成的开发包版本号仍为 `0.4.0`，应与正式发布包区分保存。`engines.dsh` 声明 `0.2.0-rc.2` 和 `0.2.1-rc.2` 至 `0.2.1-rc.5` 的已列版本。这五个版本已在 Windows 的真实 DSH 进程和模拟网关中验证插件注册、鉴权与控制调用；其他版本需要单独验证。

## 手机网关与凭据

手机网关监听 `127.0.0.1:3070`。DSH 与手机不在同一网络命名空间时，先建立连接手机的 SSH 本地转发；反向连接等方案见[远程接入](https://github.com/huantuoshen-prog/agent-mobile-use-hyperos/blob/main/docs/remote-access.md)：

```sh
ssh -NT -L 127.0.0.1:23070:127.0.0.1:3070 \
  -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 \
  -o ServerAliveCountMax=3 phone
```

`phone` 是已配置并核验主机指纹的 SSH 别名。把手机 `/data/adb/agent_mobile_use/gateway-token` 经已授权的私有通道交给运行 DSH 的账户。下面的示例要求该 SSH 账户有 shell 登录和手机 root 读取权限；仅允许端口转发的 SSH 账户不能执行此命令。

```sh
umask 077
mkdir -p "$HOME/.config/agent-mobile-use"
ssh phone 'su -c "cat /data/adb/agent_mobile_use/gateway-token"' > "$HOME/.config/agent-mobile-use/token"
chmod 600 "$HOME/.config/agent-mobile-use/token"
```

同机宿主可通过已授权的 root 通道将凭据复制到运行 DSH 的账户私有目录；隔离容器内的路径需指向容器实际可读的私有文件。文件内容是手机网关现有 token，不修改手机原始文件。Linux 等 Unix 系统要求文件权限不允许组和其他用户访问；Windows 需将文件 ACL 限制到运行账户和系统管理员。客户端启动时校验绝对路径、权限和 token 格式，读取路径无需写入密钥内容。

通用模式只允许数字回环 HTTP 地址，例如 SSH 转发后的 `http://127.0.0.1:23070`，同网络命名空间内的手机网关可用 `http://127.0.0.1:3070`。网关及 token 不应直接暴露到公网。

## 安装和配置 DSH bundle

在源码仓库根目录打包后，安装到实际运行的 profile。示例使用 `web`；其他 profile 替换为相应名称。

```sh
npm pack ./dsha-plugin
dsh plugin --profile web add ./dsh-mobile-use-0.4.0.tgz
```

保留 profile 已有的 patch，在 `$DSH_HOME/profiles/web/cordis.patch.yml` 的顶层数组中追加以下条目，并把路径换成运行 DSH 的账户可读的**绝对路径**：

```yaml
- id: dsh-mobile-use
  config:
    mode: remote
    endpoint: http://127.0.0.1:23070
    tokenFile: /home/agent/.config/agent-mobile-use/token
```

Windows 路径示例为 `C:/Users/agent/.config/agent-mobile-use/token`。`dsh --profile web --dump-config` 可核对合成后的条目；按宿主方式重启或重载 profile。此配置只有文件路径，没有 token 本文。

也可为 DSH 进程设置 `MOBILE_USE_MODE=remote`、`MOBILE_USE_BASE` 和 `MOBILE_USE_TOKEN_FILE`。profile 配置字段优先于对应环境变量。默认远程地址为 `http://127.0.0.1:23070`，默认凭据路径为运行账户的 `$HOME/.config/agent-mobile-use/token`。DSH 会过滤子进程的部分环境变量；bundle 会显式把地址和凭据路径传递给 MCP 子进程。

保留默认配置时，DSHA 模式沿用本机 `http://127.0.0.1:3070` 和 `$HOME/.dsh/mobile-use-token`。模块只向其控制台和受支持的 DSHA App 私有目录自动交付 token；通用宿主的文件由上述授权步骤交付。

## 调用与诊断

通用模式加载后应出现 17 个 `mcp__mobile-use__` 工具。先调用 `mobile_status`，再以 `mobile_task_begin` 开始手机控制；后台流程为 `mobile_start` → `mobile_mode(background)` → 读屏、截图或操作。用 `mobile_ask` 等待手机答题，任务结束调用 `mobile_task_end`。控制租约一次只允许一个活动控制者；客户端退出或失联后由手机端回收。通用模式不会自动把宿主其他任务事件或 `ask_user_question` 映射到手机。

| 现象 | 检查 |
| --- | --- |
| 插件未出现或未注册工具 | 检查 profile 中的 bundle、`mode`、DSH 版本和启动日志；确认使用的是开发包 |
| `TOKEN_FILE_REQUIRES_ABSOLUTE_PATH` | 将 `tokenFile` 或 `MOBILE_USE_TOKEN_FILE` 改为绝对路径 |
| `TOKEN_FILE_UNAVAILABLE` | 确认文件存在且运行 DSH 的账户可以读取 |
| `TOKEN_FILE_MUST_BE_PRIVATE` / `INVALID_TOKEN_FILE` | 检查文件权限、大小和内容；不要输出 token 到日志或对话 |
| `ENDPOINT_REQUIRES_LOOPBACK_SSH_FORWARD` | 配置数字回环地址并检查 SSH 转发 |
| `GATEWAY_HTTP_401` | 核对手机当前 token 与该私有文件；重新安装模块后 token 可能改变 |
| `CONTROL_BUSY_OR_EXPIRED` | 结束其他控制任务或等待原租约回收后再开始 |
| `mobile_diagnose` 显示 DSHA 缺失 | 该项是手机侧 DSHA 诊断；通用模式还需分别检查网关、Root、虚拟屏及通知 |

其他支持 MCP stdio 的 Agent 可使用[独立 MCP 客户端](https://github.com/huantuoshen-prog/agent-mobile-use-hyperos/blob/main/docs/remote-access.md)，其手机任务工具和租约与 DSH 通用模式相同。
