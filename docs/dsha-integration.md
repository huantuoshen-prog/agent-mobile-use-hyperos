# DSHA 接入与本机网关

本项目面向使用 DSHA、KernelSU/LSPosed 的小米 Root 手机。模块提供后台虚拟副屏和超级岛任务通知，插件将本机网关注册为普通 MCP tools。

| 通路 | 能力 | 地址 |
| --- | --- | --- |
| DSHA dsha-android | 无障碍操作主屏 | 127.0.0.1:3090 |
| DSHA dsha-vscreen | 宿主 App 自带虚拟屏 | 127.0.0.1:3090 |
| 本项目 dsh-mobile-use | Root 虚拟屏、实况通知、超级岛 | 127.0.0.1:3070 |

DSH 0.2.0-rc.2 的 computer-use registry 只允许一个 provider，DSHA 已占用该位置。本项目注册普通工具，保持独立的屏幕状态、截图和坐标。

## 版本与安装

当前配套版本为模块 0.11.0-alpha + 插件 0.4.0，已验证 DSHA 加载、实际工具调用与岛内答案回传，以及远程 Agent 的多题、多选和自定义回答；宿主竞争回答和下载保存等剩余项目见验收记录。安装包以 [GitHub Releases](https://github.com/huantuoshen-prog/agent-mobile-use-hyperos/releases) 中的配套版本为准。

1. 在 KernelSU 安装模块，核对 LSPosed 已启用本模块及其作用域，重启并首次解锁。
2. 打开 DSHA，完成运行环境初始化。
3. 在 DSHA 自带插件管理器导入配套 ZIP，按提示重启 Web。配置、启动快照和链接由插件管理器维护。
4. 调用 mobile_status，执行只读后台副屏任务，结束后 mobile_mode(idle)、mobile_stop。

0.11 预发布版增加控制台“安装检查”和 mobile_diagnose 工具。DSHA 在模块启动后才初始化时，可点击“修复 DSHA 连接”重新交付凭据，然后重启 Web。更新采用手动检查、下载校验和管理器安装。

## 鉴权与问答

除只读 GET /api/status 外，3070 接口需要 Bearer token 或受控 WebView 的 HttpOnly cookie。网关只监听回环。模块将凭据交付至控制台私有文件和 DSHA guest /root/.dsh/mobile-use-token；0.11 预发布版根据 DSHA 数据位置记录选择实际数据目录，兼容旧 rootfs 和独立持久目录；目录冲突或记录无效会显示待处理。插件支持 MOBILE_USE_TOKEN_FILE，不使用模型 API key。

实况事件绑定 session_id、turn_id、sequence。问答再绑定 request_id，按钮再绑定题目 ID。旧轮次与重复提交受到校验。

0.10 同步提问等待状态，答案通过 DSHA 原有界面提交。0.11 预发布版通过 user-questions/request waterfall 返回真实答案：简短二选一可在展开超级岛后回答，多题逐题选择；更多选项、多选、长说明及自定义回答打开模块面板。原 DSHA 界面仍能回答，实际胜出的答案会被记录。

真正让 Agent 等待用户应调用宿主 ask_user_question。mobile_question 只投递通知。带 intent 的审核问题和超时后的 continued 问题保留 DSHA 原生处理路径。

源码依据、接口和待验收项目见 [0.11 开发记录](live-agent-0.11.md)。[0.10 历史验收](live-agent-0.10.md) 保留既有记录；0.11 的验证范围以开发记录为准。
