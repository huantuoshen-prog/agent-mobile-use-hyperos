# Issue #1：截图故障与任务收尾修复

记录日期：2026-10-26。关联 [Issue #1](https://github.com/huantuoshen-prog/agent-mobile-use-hyperos/issues/1)。

## 源码证据与范围

截图路径为远程 MCP → `/api/screenshot` → 网关中的系统 `screencap`。DSHA 插件注册状态不是该路径的前置条件。原实现的 SurfaceFlinger 查询和截图命令都没有进程截止时间。

远程客户端原来会在一次监视请求异常后执行 `finish`，以 `TASK_STOPPED` 取消截图等在途调用，并清空活动任务。后续 `mobile_task_end` 因此返回 `TASK_NOT_ACTIVE`。

模拟网关在截图等待期间对停止轮询返回一次 HTTP 503，可以复现旧实现的 `TASK_STOPPED`。此证据覆盖客户端中断机制；Issue 中熄屏现场首先失败的请求、系统冻结情况和启动 DSHA 后恢复的具体原因尚未取得真机日志。

## 修复行为

- SurfaceFlinger 查询和 `screencap` 共用 10 秒截止时间，并继承 HTTP 请求取消信号；截止后结束截图子进程，返回 HTTP 504 / `SCREENSHOT_TIMEOUT`。
- 截图接口失败保持单项工具错误，调用者可以继续读屏、操作或结束任务。
- 监视请求的超时、连接故障、HTTP 429/5xx 在已确认的租约有效期内允许恢复。失败续租不会延长本地租约；续租或通知暂时失败后仍检查手机停止命令。
- 鉴权失败、控制权被拒绝、已确认租约失效，以及停止命令通道连续失联 30 秒均终止任务。手机主动停止继续中断截图与等待回答。
- 当前客户端进程保留最近任务的收尾结果。自动结束和重复 `mobile_task_end` 均返回原始结果，包含会话、轮次、真实 outcome 和通知/清理确认；监视故障另有 `failure_code`。新任务会替换旧记录。
- 模块 App 解冻覆盖自身 UID 父组及实际 PID 子组。仅接受 `/proc/<pid>/cgroup` 中与模块 UID/PID 精确匹配的 cgroup v2 路径，保留 App 尚未启动时的 UID 父组解冻。

## 验证

- Node 24.14.0：`node --test dsha-plugin/test/*.test.*`，28 项通过。
- 同一截图/503 回归用例在基础提交 `3e2b304` 的远程客户端上失败，返回内容包含 `TASK_STOPPED`；修复实现通过。
- Go 1.22.12：Windows 交叉编译 Linux amd64 测试二进制，在 Linux x86_64 临时目录运行，40 项顶层测试通过。包含真实子进程超时回收、请求取消、JPEG 解码、无效帧边界及仅解冻模块自身 cgroup 的测试。
- `GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath` 及 `go vet ./...` 通过。
- 便携 MCP 打包脚本在隔离目录运行，ZIP 包含所需的共享运行文件。

## 2026-10-26 真机与 VPS 验收

环境：Redmi K90、Android 17 / HyperOS 4，模块及控制台 0.11.0-alpha，现有签名 APK / DEX 保持原样。候选网关直接替换模块中的网关二进制，手机及主机均保留原件备份；发布 ZIP 未更新。

本次捕获到模块 App `com.agent.mobileuse` 的 UID 父组 `cgroup.freeze=0`、PID 子组 `cgroup.freeze=1`。框架 `cmd activity unfreeze --sticky` 返回成功后子组仍为 1，前台服务启动及完成广播未得到 App 处理。补上进程子组解冻后，完成通知与取消通知恢复。该现场属于模块 App，DSHA App 的实际冻结状态未取得证据。

真机结果：

- 主屏持续为 `Dozing`，等待至少 31 秒后创建后台副屏，打开系统设置并读屏。两次 JPEG 截图为 201125 / 195051 字节，耗时 331 / 296 ms；已目视核对截图中的设置页面。
- 后续读屏、完成收尾、重复收尾通过。`notification_confirmed=true`、`cleanup_pending=false`，最终 `idle/stopped`、`active=false`。
- 系统通知记录包含模块完成通知 `id=2020`、绿色 `0xff30d158`，运行中胶囊通知 `id=10086` 已清除。
- 此时 `dsha_plugin=unknown`，截图仍成功。DSHA App PID 在测试前后相同；本次未停止 DSHA，也未验证其进程完全退出时的表现。

VPS 结果：

- 既有客户端已备份并更新，DSH 与 SSH 转发服务均保持 active，未重启 DSH 服务。使用实际 Node 24 和更新后的便携客户端执行 stdio MCP 协商，发现 17 个工具。
- 验收专用本机代理分别注入一次截图 HTTP 504 和一次监视 HTTP 503，其他请求转发真实手机。截图错误后任务更新、读屏和下一次 JPEG 截图成功。
- 经手机网关的停止入口中断一项等待中的截图；重复 `mobile_task_end` 均返回原始 `cancelled` 结果，后续操作返回 `TASK_NOT_ACTIVE`，最终 `idle/stopped`。
- 系统通知记录包含“任务已停止”，HyperOS 载荷 `business=agent_live_cancelled`、灰色 `0xffa0a6b0`。

Windows 已配置客户端同步更新并保留其原有完整 schema。现场原始日志、截图、部署回执和备份保存在主机私有目录；公开记录未包含 token、私钥或完整通知转储。

尚未发布 Release、未完成手机整机重启后的复验；Issue 原始现场最先失败的请求仍待日志确认。
