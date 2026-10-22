# dsh-mobile-use 0.3.0（DSHA 插件）

面向使用 DSHA 且手机已 root 的用户，把 agent_mobile_use 0.10.0 的后台虚拟副屏接入 DSHA，并从宿主真实任务事件同步小米超级岛状态。支持 DSH 0.2.0-rc.2；注册普通 MCP tools。

## 状态与工具

运行中、前台控制、后台副屏、等待回答、完成、失败、停止分别使用不同标题、图标和颜色。完成状态由 turn/end completed 触发；todo 清单只提供实际进度。

| 工具（前缀 mcp__mobile-use__） | 作用 |
| --- | --- |
| mobile_status / mobile_live_status | 网关、显示和任务实况 |
| mobile_start / mobile_stop | 创建、回收 root 虚拟屏 |
| mobile_mode | foreground / background / idle |
| mobile_resize | 调整虚拟屏尺寸与 DPI |
| mobile_screenshot / mobile_ui_dump | 当前目标显示的截图、控件树 |
| mobile_action | observe / click / set_value / swipe / key / launch_app / wait |
| mobile_apps | 应用列表 |
| mobile_audio_status / mobile_audio_toggle | 查询、设置副屏静音 |
| mobile_notify | 补充进度说明 |
| mobile_question / mobile_question_cancel | 投递、取消问题通知 |

dsha-android 的主屏与 dsha-vscreen 的 App 虚拟屏保留。root 虚拟屏是独立后端，切换后必须重新读屏。真正可回答的问题使用宿主 ask_user_question；插件同步等待与恢复状态。

## 安装

先安装配套模块，在控制台启用超级岛。在 DSHA 的插件管理器中导入 `dsh-mobile-use-0.3.0.zip`，按界面提示确认安装。模块副屏不要求开启 DSHA 自带副屏或 DSHA Root Shell。

按 DSHA 的提示重启 Web。插件管理器负责 profile、启动快照和符号链接。旧版 0.9 网关不包含自动实况接口，需要配套升级。

## 验证与回滚

检查插件已加载、十五个工具已出现，先执行 mobile_status，再验证真实任务开始、屏幕控制、回答问题和结束。后台测试顺序：mobile_start → mobile_mode(background) → mobile_ui_dump → mobile_action → mobile_screenshot → mobile_mode(idle) → mobile_stop。

回滚通过插件管理器的 rollback，删除通过 delete dsh-mobile-use；使用前先查看 dsha-plugin 命令帮助。控制台 App 的签名迁移与插件回滚分别处理。

## 本机控制通道

网关监听 127.0.0.1:3070。除只读 /api/status 外，接口需要私有 token。模块将其交付到 guest /root/.dsh/mobile-use-token，插件也支持 MOBILE_USE_TOKEN_FILE；不读取模型 API key。

工具参数声明兼容 DSH 的 JSON Schema 子集，数值范围和坐标长度由桥接执行端检查。响应体下载也受超时保护；取消排队动作后不会发送该动作。

状态协议、签名与验收边界见完整源码库中的 docs/live-agent-0.10.md。
