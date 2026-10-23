# 0.11 候选版：岛内答题与安装升级

状态：本地候选源码与签名构件已生成。测试手机安装过前一轮候选，重启后的模块版本及哈希已核对；DSHA Web 的插件目录加载失败尚未修复，岛内问答尚待真机验收。手机当前离开局域网，本轮新增修改尚未安装；尚未发布。版本配对为模块 `0.11.0-alpha`、插件 `0.4.0`、网关协议 `2`。生成源为根目录 `release-config.json`。

## 超级岛与宿主接口依据

参考 [HyperIsland](https://github.com/1812z/HyperIsland) 的渲染源码，目录位于 `android/app/src/main/kotlin/io/github/hyperisland/xposed/template/renderer/`：

- `ImageTextWithRightTextButtonRenderer.kt` 的 actions 模板提供一个右侧文字按钮。
- `ImageTextWithButtonsRenderer.kt` 的 `iconTextInfo` 与 `textButton` 提供两个文字选项。
- `IslandRenderer.kt` 的 `fixTextButtonJson` 将按钮 action 指向 `miui.focus.*` 资源键。

其他模型参考 [HyperIsland ToolKit](https://github.com/D4vidDf/HyperIsland-ToolKit)。本项目沿用自己的 Java 载荷构造层。官方入口为 [小米超级岛指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131)。开源模板用于交叉核对协议，具体 ROM 的显示与点击行为仍需设备测试。

展开模板使用 `iconTextInfo` 和最多两个 `textButton`，每个 action 在通知 extras 顶层和 `miui.focus.actions` 中提供对应 `Notification.Action`。折叠态保留原有图标胶囊，问题和选项描述放入展开区。选项过长、描述超出展开载荷或有完整 detail 时，进入模块面板显示全部内容。

已从测试手机实际安装包只读核对 `@deepseek-ai/dsh-user-questions@0.2.0-rc.2`：

- `user-questions/request` 为带 next 的 waterfall，输入包含 questions、agent、signal，定时请求还有 wait.callId。
- 正常返回格式为 `{answers:[{id,selected:[选项原文],custom?}]}`，必须保留原问题 ID。
- `ctx.userQuestions.answer(agent, callId, answer)` 面向 continued 问题；本版使用当前等待中的 waterfall 返回路径。
- DSHA Web 等待使用子 AbortSignal。任一入口胜出后取消另一个等待，不取消 Agent 的整轮信号。

## 用户操作与边界

1. Agent 通过宿主 `ask_user_question` 提问，实况状态进入 waiting。
2. 展开超级岛。简短二选一可以直接点选；多题显示当前题序号，全部回答后统一提交。旧题按钮无法推进下一题。
3. 多选、长问题或自定义回答点击“选择答案”，在模块自己的面板操作。
4. 提交后显示“等待 Agent 接收”。插件取得答案后返回宿主，实况恢复原阶段；最终任务完成仍由 turn/end 确认。

带 intent 的审核问题由 DSHA 显示完整审核上下文。前台定时等待超时、任务结束、宿主重启或网关重启后，旧问题不可通过岛继续提交。continued 迟到回答尚未接入，可在 DSHA 原生界面处理。

## 问答接口

以下接口使用现有本机鉴权。身份键为 `request_id + session_id + turn_id`。

| 请求 | 用途 |
| --- | --- |
| `POST /api/question` | 注册问题并更新现有等待通知；bridge=true 必须属于当前 waiting 轮次 |
| `GET /api/question?...` | 读取 open/submitted/answered/cancelled、当前 step 和已选 drafts |
| `POST /api/question/select` | 提交当前 question_id + option_index；推进下一题，最后一题转 submitted |
| `POST /api/question/answer` | 面板提交完整 answers，校验题目、选项和多选约束 |
| `POST /api/question/settle` | 宿主记录实际胜出的 answers、answer_source 并关闭按钮，或取消问题 |
| `POST /api/question/cancel` | 取消请求；桥接请求必须携带完整身份键 |

问题通知刷新不改变任务身份与阶段。网关保存短期内存记录，模块私有偏好保存当前显示内容；点击按钮时重新核对网关状态。`notification=queued` 只代表投递排队。

## 安装检查与手动更新

控制台“安装检查”读取 Root、架构、待重启状态、DSHA 安装、私有 token 权限与归属、LSPosed 配置、控制台 Hook、通知权限、超级岛开关和插件注册。实际副屏操作与 SystemUI 效果单独列为待验收。

`mobile_diagnose` 返回网关报告；`vd doctor` 提供离线只读检查。新插件每 30 秒报告版本和注册的 16 个工具，90 秒未收到后标记为无法确认。报告不输出 token、模型密钥或会话正文。

“修复 DSHA 连接”向固定 App 私有路径重新交付当前 token，保留 DSHA 数据与配置。目标目录解析后必须位于对应 App 的私有目录内。

更新需手动点击检查：读取仓库 `updates/alpha.json`，显示模块与配套插件，按用户选择下载到保存位置。文件先下载到应用私有缓存，通过 SHA-256 后才打开保存位置选择器；保存前复核缓存，校验失败、中断和超出大小限制时删除临时文件。安装分别由 KernelSU 与 DSHA 插件管理器执行；模块更新后重启并首次解锁，再导入插件并重启 Web。

## 发布流程

1. 修改 `release-config.json`，运行 `python tools/sync-version.py` 生成各组件版本。
2. 使用固定私钥构建。`release-certificate.sha256` 固定公开证书指纹；本地打包与 CI 校验一致性。
3. `tools/build-windows.py` 生成模块 ZIP 和内部 `binaries.sha256`。安装前检查 ARM64、KernelSU、LSPosed 和关键二进制哈希。
4. 运行 `tools/package-release.py --java <java> --apksigner <apksigner.jar>`，生成插件 ZIP、manifest、SHA256SUMS，索引暂存于 `.build-windows/alpha.json`。
5. 真机验收后上传配套 Release 构件，核验远端哈希，再将暂存索引发布为 `updates/alpha.json`。索引不可先指向不存在的 Release。

旧发布 ZIP 保留原字节。与上版公开证书一致满足同签名覆盖的前提，实际安装仍需验证。密钥和设备备份不进入仓库。

## 验收记录

本地主机覆盖问答路由、身份、并发提交、逐题选择、宿主胜出记录、子信号取消、双按钮资源键和原七状态载荷。以下设备项目仍待执行：

- [x] KernelSU 覆盖安装，原 App UID、5 份偏好文件及通知权限保留，LSPosed 路径和作用域正确；待重启模块的全部构件哈希已核对。
- [ ] DSHA 插件管理器导入 0.4.0，Web 重启后确认 16 个工具。
- [ ] 安装检查页截图、凭据修复、手动更新及校验失败路径。
- [ ] 真实模型提问，岛内双按钮点击后 Agent 收到对应原文；检查折叠宽度和展开内容。
- [ ] 连续两题、面板多选/自定义、原生界面抢先回答、旧按钮、取消和超时。
- [ ] 前台及后台副屏只读任务，结束后 idle/stopped，通知和实况无残留。
- [ ] 重启并首次解锁后检查版本、凭据、插件接入及真实模型调用。

2026-10-22 安装记录：模块通过 KernelSU 原生安装流程写入待重启目录，插件通过 DSHA 插件管理器的预览、摘要确认和安装流程升级到 0.4.0。旧模块、控制台数据、偏好与旧插件已保存在私有备份目录。24 项 Go 顶层测试在手机 ARM64 环境通过；真实 Agent 与 SystemUI 问答验收尚未完成。

设备发现并修复：模块附带的 SQLite 可执行文件只接受数据库和 SQL，不支持 `-readonly`，也不输出查询行。诊断和安装检查改为 `PRAGMA query_only=ON` 加条件表达式，通过退出码确认必要作用域与启用状态。真机已验证真条件退出 0、假条件退出 1；修正版安装输出已确认作用域完整。未更改其他 LSPosed 模块记录。

0.10 历史设备记录保留在 `live-agent-0.10.md`。

2026-10-23 继续排查：重启后候选模块的 APK、网关和 DEX 哈希已核对，网关保持 stopped/idle。DSHA Web 启动仍报告找不到 dsh-mobile-use 目录，随后自动停用该插件；16 个工具及超级岛答题尚未通过真机验收。源码显示 DSHA 可将 user-data-v5/dsh 映射到 /root/.dsh，调试辅助脚本此前未带此映射；是否属于当前设备的故障原因，需核对数据位置记录与实际启动参数。旧无线调试地址连接超时，等待新地址后继续。

## 2026-10-23 本地开发验证

- 凭据交付与诊断共用 DSHA 数据目录选择逻辑：读取 `data-locations-v5.json`，缺失时检查 `.previous`；按 `LEGACY` / `STABLE` 选择旧 rootfs 目录或 `user-data-v5/dsh`。没有记录时遵循 DSHA 的目录存在性规则。记录损坏、目录冲突或越出 App 私有目录时停止交付，并在安装检查中说明。模块不迁移 DSHA 数据。
- 插件包补充源码仓库与 DSH 兼容版本声明，供 DSHA 导入预览显示；配置由集中版本文件生成。
- 手动更新调整为先下载和校验，再选择保存位置；选择器期间保存缓存身份，恢复后保存前重新校验。失败不弹出保存选择器，不产生下载失败的目标 ZIP。
- CI 签名阶段同时打包模块、配套插件、manifest、校验和及暂存更新索引。在线索引仍只在验收、上传并核对 Release 构件后发布。
- 本地主机验证：12 项 Node 测试、27 项 Go 顶层测试（含 13 个数据目录选择场景）、Go vet、82 项 Java 原载荷检查、七状态检查、双按钮检查，以及下载成功/哈希错误/大小限制/中断/缓存变更检查通过。Android 全量源码编译、持久证书签名、包内构件哈希校验通过。
- 当前生成包比手机已安装候选包含更多修改。主机测试没有验证 DSHA Web 启动恢复、SystemUI 点击回传或 Android 保存选择器交互；这些项目继续保留待验收状态。超时 continued 问题仍由 DSHA 原界面处理。

恢复连接后的顺序：核对手机的数据位置记录和实际 Web 进程绑定；经 DSHA 原生插件管理器在有效数据根导入配套 ZIP，确认 Web 启动和 16 个工具；安装本轮候选并重启；执行超级岛二选一、多题、面板和原界面抢先回答测试；最后关闭副屏并核对 idle/stopped。只读数据根探针已在本地准备，排查不需要删除 DSHA 数据或重装运行环境。
