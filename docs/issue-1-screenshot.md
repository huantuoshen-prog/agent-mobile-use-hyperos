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

## 验证

- Node 24.14.0：`node --test dsha-plugin/test/*.test.*`，28 项通过。
- 同一截图/503 回归用例在基础提交 `3e2b304` 的远程客户端上失败，返回内容包含 `TASK_STOPPED`；修复实现通过。
- Go 1.22.12：Windows 交叉编译 Linux amd64 测试二进制，在 Linux x86_64 临时目录运行，37 项顶层测试通过。包含真实子进程超时回收、请求取消、JPEG 解码和无效帧边界测试。
- `GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath` 及 `go vet ./...` 通过。
- 便携 MCP 打包脚本在隔离目录运行，ZIP 包含所需的共享运行文件。

本次未安装手机构件、未发布 Release，尚未完成熄屏、DSHA 不存活及 SystemUI 终态真机验收。已发布 ZIP 的版本及内容保持原样；本修复需要更新远程客户端和手机网关后进行联合验收。
