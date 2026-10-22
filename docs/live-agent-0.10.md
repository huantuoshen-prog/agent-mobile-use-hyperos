# 手机 Agent 实况通知（0.10.0-alpha）

## 状态与来源

| 状态 | 用户看到的标题 | 图标 / 颜色 | 事件来源 |
| --- | --- | --- | --- |
| running | 任务进行中 | 终端 / 青绿 | DSH turn/start、普通工具执行 |
| foreground | 前台控制 | 眼睛 / 蓝 | DSHA 主屏操作、root foreground 模式 |
| background | 后台副屏 | 双窗口 / 紫、外圈光效 | DSHA 虚拟屏或 root background 模式 |
| waiting | 等待回答 | 问号 / 琥珀 | 真实 user-questions/request |
| completed | 任务完成 | 勾 / 绿 | turn/end reason=completed |
| failed | 执行失败 | 叉 / 红 | turn/end 阻塞、错误等非正常结束 |
| cancelled | 任务已停止 | 停止方框 / 灰 | 用户中断、会话关闭 |

todo 清单只提供实际已完成数量，不用工具次数或生成时间估算百分比。清单全部完成仍须等待整轮结束；子模型调用进入 idle 不触发完成通知。通知中的详细文字来自阶段说明和最终正文摘要，不转发隐藏推理或凭据。

## DSHA 接入

安装 `release/dsh-mobile-use-0.3.0.zip`，使用 DSHA 自带插件管理器导入并重启 Web。插件监听宿主事件，不依赖模型主动记住发送开始/结束通知。原有 `dsha-android`、`dsha-vscreen` 保留，root 虚拟屏作为普通 MCP tools 接入，不占用已有 computer-use provider。

工具：mobile_status、mobile_start、mobile_stop、mobile_mode（含 idle）、mobile_screenshot、mobile_ui_dump、mobile_action、mobile_apps、mobile_notify、mobile_question、mobile_question_cancel、mobile_live_status、mobile_resize、mobile_audio_status、mobile_audio_toggle。

交互问题使用宿主 `ask_user_question`。等待事件会更新黄岛，真实答案由 DSHA 原有用户输入服务返回；等待结束后恢复此前阶段。`mobile_question` 是显式通知接口，不创建宿主可回答请求。

“查看”通知带 DSHA 已有 `open_web=true` 入口，交由 DSHA 检查 Web 就绪及鉴权。后台 root 副屏通知仍可打开本机 3070 监控页。当前 DSHA 原生入口打开现有会话页面，不额外承诺多会话精确定位。

任务事件的 display_backend 区分 physical、dsha、root。DSHA 副屏通知进入宿主会话；root 副屏进入模块监控。控制台默认快捷入口在 DSHA 已安装时打开宿主；显式 3070 监控入口继续使用模块 WebView。Android 14 及以上的快捷设置磁贴使用 PendingIntent 启动接口。

## 本机控制通道

- 3070 保持回环监听。除只读 `/api/status` 外，需要 Bearer token 或受控 WebView 的 HttpOnly cookie。
- token 持久文件：`/data/adb/agent_mobile_use/gateway-token`，root 私有；模块启动时交付副本到控制台 App 私有 files 和 DSHA guest `/root/.dsh/mobile-use-token`。
- 插件读取 `MOBILE_USE_TOKEN_FILE` 指定路径，默认上述 guest 路径；不读取或传递模型 API key。原有 DSH secret 接口也受此鉴权保护。
- 显式生命周期事件包含 session_id、turn_id、sequence，重复/过时事件不会回滚已结束状态；心跳停止后释放运行态。
- 停止按钮发送绑定当前轮次的请求，插件调用已安装 DSH 的 `agent.cancel({kind:'user'})`。终态确认前保留停止意图；已经提交给设备或外部服务的动作需要核对实际结果。
- API 返回 `notification=queued` 表示接受投递，不把它写成 SystemUI 已显示。实际显示需核对系统通知与截图。

## 构建与签名

`tools/build-windows.py` 编译 Hook APK、两份 Java DEX、Linux/ARM64 网关，再从同一组产物打包模块。Android 脚本与 module.prop 打包为 LF。Linux CI 使用 `tools/build-runtime-ci.sh`，不再直接沿用旧 Go/DEX 文件。

需要固定保存的 keystore 和 `KEYSTORE_PASSWORD` 环境变量；不得把密钥放入源码或发布包。CI 需要 `MODULE_KEYSTORE_BASE64`、`MODULE_KEYSTORE_PASSWORD` 两个 Secrets。本地私钥路径由使用者管理，发布记录仅保存公开证书指纹。

旧版 cloudbuild 产物曾使用不同临时签名，不能假定能覆盖更新。签名不兼容时，安装脚本停止；数据备份、签名迁移和恢复须单独核验，不能通过清除 DSHA 数据解决。

## 验证命令

```sh
node --test dsha-plugin/test/gateway.test.cjs dsha-plugin/test/lifecycle.test.mjs
cd vd-server-go && go test ./...
```

Go 在 Android/Linux 目标运行；Windows 主机需交叉编译测试二进制到 ARM64 再运行。Java 的 IslandPayloadSelftest、AgentLiveSelftest 使用真实生成类、org.json 测试库和编译用 Android jar 执行。

真机验收覆盖：DSHA 实际加载插件、真实开始/结束事件、前台/副屏操作、问题回答、停止回执、七种通知的 SystemUI 数据和截图、设置保留与重新启动。只通过主机测试不能宣称这些步骤全部完成。

2026-10-21 真机部署与验收：

- KSU 0.10.0-alpha、控制台 APK 和 DSHA 插件 0.3.0 已安装。用户确认手机具备核心签名破解；本机 `pm install -r` 实际成功，控制台 UID 与四份原始偏好保持。该结果不能用于推断普通手机可跨签名覆盖。
- 正式 DSHA Web 已加载十五工具插件；真实模型调用 mobile_status、真实 ask_user_question 等待/提交答案、最终 ACCEPTANCE_OK 和完成通知均已验证。真实通知停止按钮成功调用宿主取消并收到 cancelled 通知。
- 七种状态均取得系统通知记录；前台蓝眼睛、后台紫双窗口、等待琥珀问号已目视核对。等待态初版因独立问题通知与实况胶囊重复，占满状态栏；现在按 session/turn 复用单条等待通知，真实宿主复测为单个居中胶囊，时间、信号、电量可见。
- HyperOS 会将 Android promoted live update 转成系统模板，覆盖自定义岛样式；本机使用显式 miui.focus.param。动作对象同时写入嵌套 Bundle 与 extras 顶层，JSON 的 action 引用资源键；actionIntent 仅用于 Intent URI。基础文字按钮只保留停止，会话入口由整张通知承载。
- root 副屏实际创建、启动系统设置、读树、截图、应用枚举、缩放与回收通过。请求 800×1400 时硬件对齐后为 800×1392，resize 返回与实际 status 一致。验收后恢复 stopped / idle / target -1，音频开关恢复。
- Android 17 首次解锁前 CE 目录不可写：开机服务等待首次解锁，token 交付时恢复 App 私有目录和文件的 SELinux 标签。SELinux 保持 enforcing。
- 本机覆装会撤销通知运行时权限，安装脚本保留并恢复已授予的 POST_NOTIFICATIONS。LSPosed 路径、启用状态及四个必要作用域已核验；更新只修改本模块记录，保留其他配置。
- 最新 19 项 Go 顶层测试、7 项 Node 测试、Java 82 项载荷检查及七状态检查通过。

最新版本已再次重启，首次解锁后网关自动启动、token 副本与 0600 权限及所属 UID、已安装文件 hash、通知权限、四项个性化设置、LSPosed 路径与作用域均已核验。

模块副屏通过 DSHA 的 mobile-use 插件调用 KSU 网关，不依赖 DSHA 的 Root Shell 开关和原生副屏权限。额外检查的 DSHA build170 自带副屏在用户确认后返回 VSCREEN_NoSuchMethodException；该宿主独立能力未在本模块仓库内修复。为检查该路径临时开启的 DSHA Root Shell 已恢复关闭，无障碍授权未变更。

## 依据与复用

- [小米官方开发指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)：本地原生通知 extras、param_v2、岛模板及取消接口。
- [Android 悬浮窗触摸穿透规则](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE)：Android 12+ 使用系统允许的遮挡透明度，边框层 alpha 不高于 0.79。
- [Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)：持续活动通知与平台推广能力。完成/失败结果使用短时结果通知。
- [HyperIsland](https://github.com/1812z/HyperIsland)：MIT；作为协议和系统差异的交叉参考，本实现沿用已有 Java payload 构造层，系统放行限定到本应用。
- DSHA build170 的 dsh-task-notifier、dsh-status-overlay 和实际安装的 DSH 0.2.0-rc.2：核对 turn/end、工具事件、user-questions 和 Agent.cancel 语义。

HyperOS 以外的 ColorOS、AOSP/OneUI 和其他 Android 版本需要分别验收；当前手机已知为 HyperOS 4 / Android 17。

重启后再次通过真实 DSHA 模型调用 mobile_status，最终回复 POST_REBOOT_OK，网关为 stopped / idle / target -1，实况任务为 inactive。
