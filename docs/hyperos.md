# HyperOS / MIUI 超级岛适配说明（v0.9.0-alpha）

本文描述 `agent-mobile-use` 在 HyperOS（含 MIUI 系）上的「超级岛 / 焦点通知」适配。目标机型为 HyperOS 4 / Android 17 真机（用户报告），但**当前版本尚未在真机上验证**，见文末「验证状态」。

## 1. 默认状态与开关

- **默认关闭**。升级安装不会改变通知行为；不开启时三类通知（胶囊 / 完成 / 提问）保持普通通知。
- 开关位置：`agent_hook.apk` 控制台 →「系统特性偏好」→「超级岛通知（HyperOS）」。
- 该开关写入 SharedPreferences `agent_capsule_state` 的 `enable_super_island`（`HyperIsland.PREF_ENABLE`），非 HyperOS 机型上开关置灰不可点。
- **Android 16/17 注意**：这些系统上本应用的 `SharedPreferences` 实际存放在 `/data/misc/apexdata/<uuid>/prefs/com.agent.mobileuse/`（`<uuid>` 每机不同），而不是 `/data/data/com.agent.mobileuse/shared_prefs/`。用 root 往后者写开关不会生效，且应用运行中回写会覆盖外部改动；**请始终用控制台开关设置**。
- 即使开关开启，若系统未授权焦点通知（`canShowFocus=false`）或机型不满足条件，`HyperIsland` 的 attach 方法直接返回，通知退化为普通通知，不会崩溃。

## 2. 原理

HyperOS 焦点通知（超级岛的数据来源）通过普通 Notification 的 extras 投递：

| extra key | 类型 | 内容 |
| --- | --- | --- |
| `miui.focus.param` | String | 岛 JSON，根为 `{"param_v2":{...}}`，总长 ≤ 3072 字节 |
| `miui.focus.pics` | Bundle | 子键 `miui.focus.pic_<name>` → `Icon` |
| `miui.focus.actions` | Bundle | 子键 `miui.focus.action_<key>` → `Notification.Action` |

`param_v2` 关键字段：`business`、`islandProperty`(1 信息 / 2 操作)、`aodTitle`（息屏）、`param_island.islandTimeout`（秒）、`bigIslandArea.imageTextInfoLeft`、`smallIslandArea.picInfo`、`baseInfo`（焦点通知本体）；操作按钮通过 `actions` 引用 `miui.focus.action_<key>`。

岛 JSON 能被接受需要同时通过两层校验：

1. **SystemUI 权限闸门**：`miui.systemui.notification.NotificationSettingsManager#canShowFocus / #canCustomFocus`；
2. **签名白名单**：新版在焦点通知插件内为 `miui.systemui.notification.focus.SignatureChecker#checkSignatures`，另有 XMSF（`com.xiaomi.xmsf`）内 `com.xiaomi.xms.auth.AuthSession` 的签名校验链路。

未获小米邮件审批的第三方应用（含 `com.agent.mobileuse`）两层都不通过，岛 JSON 被丢弃并退化为普通通知。本适配用 LSPosed 在系统侧把两层闸门改为放行，属于**非常规路线**，风险见第 6 节。

## 3. 代码改动清单

| 文件 | 改动 |
| --- | --- |
| `agent-hook-apk/src/com/agent/mobileuse/HyperIsland.java` | 新增。ROM 探测（`ro.mi.os.version.name` / `ro.miui.ui.version.name` / `Build.MANUFACTURER`）、能力探测（`persist.sys.feature.island`、`canShowFocus`、`notification_focus_protocol`）、三类通知挂岛、岛内按钮 PendingIntent |
| `.../IslandPayloadBuilder.java` | 新增。纯 Java + `org.json` 构造岛 JSON，3072 字节上限（超限先丢 actions，再超返回 null），不依赖 Android 类，可在 JVM 单测 |
| `.../GlowService.java` | `promoteToForeground()` 在 `startForeground` 前调用 `HyperIsland.attachCapsule(...)` |
| `.../NotifyReceiver.java` | 新增 `ACTION_ISLAND` 广播（关闭/切后台/清除/忽略岛内按钮）；完成通知在 `notify()` 前调用 `HyperIsland.attachCompleted(...)` |
| `.../QuestionActivity.java` | 提问通知在 `notify()` 前调用 `HyperIsland.attachQuestion(...)`；`createCyberQuestionBitmap` 改 public 复用图标 |
| `.../HookEntry.java` | 新增 `isHyperOsRom()`；HyperOS 下跳过 OnePlus Action Button hook；`hookHyperOsSystemUI()` 解锁上述闸门并放行本应用通知；插件 ClassLoader 二次挂钩；`hookXmsf()` 解锁 XMSF 校验 |
| `agent-hook-apk/res/values/strings.xml` | `xposedscope` 增加 `com.xiaomi.xmsf` |
| `ksu-module/customize.sh` | LSPosed scope 表增加 `com.xiaomi.xmsf` 一行 |
| `ksu-module/bin/run_daemon.sh` | `BOOTCLASSPATH` 三级动态发现（`/proc/1/environ` → `init.environ.rc` → 存在性过滤候选列表），不再硬编码 ColorOS/QCOM jar |
| `AndroidManifest.xml` / `module.prop` | 版本 0.9.0-alpha / versionCode 900 |

ColorOS 流体云路径（`hookSystemUI` 的 Oplus 分支）保持原样：`isHyperOsRom()` 为 true 时才切到 HyperOS 分支并提前返回。

## 4. 开启步骤

1. 安装 v0.9.0-alpha KSU 模块并重启（LSPosed 作用域应包含 `android`、`system`、`com.android.systemui`、`com.agent.mobileuse`、`com.xiaomi.xmsf`）。
2. 打开 `agent_hook.apk` 控制台，确认「超级岛」状态行：
   - `已开启 (protocol N)`：可上岛；
   - `系统未授权焦点通知`：闸门未解锁（检查 LSPosed 作用域 / 重启 / 看 logcat）；
   - `已关闭`：开关未开；
   - `非 HyperOS`：ROM 不匹配。
3. 打开「超级岛通知（HyperOS）」开关。
4. 触发一次任务通知（胶囊常驻通知 / 完成通知 / 提问通知），观察状态栏超级岛。

建议先在**备用机**或能进 recovery/安全模式的设备上验证，因为 hook 失败可能导致 SystemUI 崩溃循环（见第 6 节）。

## 5. 真机验证命令

```bash
# 1. 超级岛特性是否开启
adb shell getprop persist.sys.feature.island

# 2. 焦点通知协议版本（1=OS1, 2=OS2, 3=OS3+）
adb shell settings get system notification_focus_protocol

# 3. 本应用焦点通知权限（true/false）。
#    注意：`content call` 以 shell(uid 2000) 执行会报 "Package is not owned by uid 2000"，
#    需以应用自身身份或 root 触发；最直接的办法是看 logcat 里 HyperIsland 的
#    `canShowFocus(com.agent.mobileuse) = ...` 输出（本适配已内置该日志）。
adb shell logcat -d -s AgentHyperIsland | grep canShowFocus

# 4. 通知里是否真的带上了岛 JSON，以及 NMS 是否识别为焦点通知
adb shell dumpsys notification --noredact | grep -A2 miui.focus.param
adb shell dumpsys notification --noredact | grep -o 'focusType=[A-Za-z]*' | sort | uniq -c
#    期望出现 focusType=PARAMS（普通通知为 focusType=null）

# 5. 悬停/展开岛时 SystemUI 的焦点通知日志
adb logcat -d | grep -iE "focus|dynamicisland|island" | tail -n 80

# 6. LSPosed 是否把 SystemUI 钩子装上
adb logcat -d | grep -E "AgentMobileUseHook.*(canShowFocus|checkSignatures|PluginFactory|AuthSession)"

# 7. 模块是否启用、作用域是否正确
adb shell su -c "sqlite3 /data/adb/lspd/config/modules_config.db \
  'select * from scope where module_pkg_name=\"com.agent.mobileuse\";'"

# 8. HyperOS 系统版本
adb shell getprop ro.mi.os.version.name
```

判定标准：第 3 条返回 `canShowFocus=true`、第 4 条能看到 `miui.focus.param=...`、第 5 条出现本应用通知进入焦点/岛渲染的日志，三者同时成立才算上岛成功。仅第 4 条成立而第 5 条无渲染日志，多半是岛 JSON 字段不被当前 ROM 接受。

## 6. 故障恢复（SystemUI 崩溃循环 / 岛不显示）

本适配 hook 了 SystemUI 的权限方法。类/方法不存在时 hook 会失败并记录日志（`AgentMobileUseHook` 前缀），不会崩溃；但若 ROM 对相同方法有其他语义依赖，理论上可能异常。出现 SystemUI 崩溃循环时：

1. **LSPosed 安全模式**：关机再开机时长按音量上/下（不同 ROM 组合不同）进入，或在 LSPosed 管理器里禁用本模块；
2. **KernelSU/APatch 管理器**：进入模块页禁用 `agent_mobile_use` 并重启；
3. **recovery / adb root**：
   ```bash
   adb shell su -c "rm -rf /data/adb/modules/agent_mobile_use"
   adb shell su -c "sqlite3 /data/adb/lspd/config/modules_config.db \
     'delete from scope where module_pkg_name=\"com.agent.mobileuse\";'"
   adb reboot
   ```
4. 只想关掉超级岛、保留其他功能：关闭控制台里的「超级岛通知（HyperOS）」开关（hook 仍在，但不再挂岛 JSON）；
5. 想彻底回到 v0.8.7-alpha 行为：卸载本模块后重新刷入旧包。

## 7. 已知限制与未验证项

- **已在真机验证（2026-10-19，HyperOS 4 / Android 17，机型 2510DRK44C）**：胶囊、任务完成、提问三类通知均产生 `focusType=PARAMS` 并在状态栏超级岛渲染（截图见第 9 节）；`canShowFocus` 经 LSPosed 闸门后返回 true；XMSF `AuthSession#b`、SystemUI `canShowFocus/canCustomFocus/checkSignatures` 钩子均已在 LSPosed 日志确认挂载。
- **虚拟屏/显示隔离（2026-10-19 补测通过）**：`/api/start` 创建 `AgentVirtualDisplay`（display 2，1152×2496，dpi 480）；`/api/mode?mode=background` 把设置页迁移到 display 2 并截帧成功（HTTP 200）；`POST /api/action {"action":"launch_app","package":"com.android.settings"}` 在 display 2 冷启动成功并返回 24 个 UI 节点（`ok display=2 ... total=24 retries=1`）。即：system_server 那批 wm/IME hook 确实没挂上（日志 `ClassNotFoundException`），但基础副屏能力不依赖它们。
- **仍未验证**：展开态大岛（`bigIslandArea`）的完整布局、岛内按钮（停止/切后台）点按效果；以及缺少 wm hook 时个别需要 `canBeLaunchedOnDisplay` 强校验的跨屏启动场景。
- **编译校验口径**：已验证 `javac --release 8` 对 `android-28.jar`（targetSdk 28）与 `android-34.jar` 均零错误；仓库自带 `build.sh` 使用的是 `android-23` 平台包，而代码中 `TileService` 需要 API 24+，该 jar 本身无法编译本仓库（既有问题，本次未改动构建脚本）。
- **字段名歧义**：官方指南示例写作 `miui.focus.paramtextInfo`（疑似 markdown 粘连），模板库与社区实现用 `textInfo`；本实现按 `textInfo` 输出，若真机不显示需 A/B 对照。
- **协议字段**：`notification_focus_protocol` 只表示协议版本，不授予权限；本实现默认按 protocol 3 输出。
- **Android 17 钩子漂移**：虚拟屏相关的 `Display.canHostTasks`、`IMMS.computeImeDisplayIdForTarget` 等签名在 android-36 已发生 AOSP 漂移（IMMS 变为 static 且多一参），HyperOS 4 上可能部分 hook 不生效，日志会给出失败原因；该风险不影响通知层。
- **XMSF 内部实现**：`AuthSession#b`/字段 `a`/回调 `h()` 来自社区逆向实现（HyperIsland 等），不同 XMSF 版本可能改名；失败时仅记录日志。
- **后台通知限制**：HyperOS 对后台应用发本地通知默认拒绝（官方文档 pId=1626），本模块靠前台服务常驻通知规避；完成/提问通知若被系统拦截，属系统策略而非本适配缺陷。
- **AI 键**：小米 AI 键无第三方映射 API、不产生标准 KeyEvent，HyperOS 上不注册 OnePlus Action Button hook。
- **截屏黑屏**：`FLAG_SECURE` 窗口在 HyperOS 上仍会被截成黑屏，属系统安全策略。

## 8. 回滚

- 关闭开关 = 降级普通通知；
- 禁用/卸载模块 = 完全移除 hook；
- 仓库内可 `git checkout -- <file>` 回到 v0.8.7-alpha 的对应文件。

## 9. 真机实测记录（2026-10-19）

设备：`2510DRK44C` / Android 17（SDK 37）/ HyperOS OS4.0 V816，KernelSU + LSPosed，`persist.sys.feature.island=1`、`notification_focus_protocol=3`。

| 步骤 | 结果 |
| --- | --- |
| 刷入 v0.9.0-alpha KSU 模块并重启 | 模块生效，LSPosed scope 5 行齐全，`AgentMobileUseHook` 日志确认 SystemUI `canShowFocus/canCustomFocus/checkSignatures`、XMSF `AuthSession#b` 挂载成功 |
| 控制台打开「超级岛通知（HyperOS）」 | 开关写入成功，`canShowFocus(com.agent.mobileuse) = true` |
| `am start-foreground-service ... -a START_FOREGROUND` | 胶囊通知 `focusType=PARAMS`，状态栏超级岛显示「前台接管」（眼睛图标） |
| `am broadcast -a ...ACTION_NOTIFY --ei total 8 --ei completed 8` | 完成通知 `focusType=PARAMS`，超级岛显示绿色 ✓ + 会话名 |
| `am start -n .../.QuestionActivity --es request_id ... --es data '{"questions":[...]}'` | 提问通知 `focusType=PARAMS`，超级岛右侧显示琥珀色「?」 |
| `dumpsys notification --noredact` | 三条通知 extras 均含 `miui.focus.param=String`、`miui.focus.pics=Bundle`、`miui.focus.actions=Bundle`，JSON 长度约 900 字节 |
| 重启后复验（`START_FOREGROUND`） | 仍 `canShowFocus=true`、`Super island capsule attached (902 chars)`、`focusType=PARAMS`，岛正常显示 |
| `POST /api/start` | `{"status":"running","display_id":2,"width":1152,"height":2496,"dpi":480}`；`dumpsys display` 出现 `AgentVirtualDisplay`（owner android, uid 0） |
| `POST /api/mode?mode=foreground` + `/api/screenshot` | HTTP 200，205244 字节（截本机屏） |
| `POST /api/mode?mode=background` + `/api/screenshot` | 迁移 `com.android.settings/com.android.settings.MainSettings`（task 1324）到 display 2，HTTP 200，203724 字节（1152×2496，实际 JPEG 数据） |
| `POST /api/action {"action":"launch_app","package":"com.android.settings"}` | display 2 冷启动成功，UI dump 返回 `total=24 retries=1`，截图可见设置页在副屏渲染 |

**遗留问题（未解决，如实记录）**

- `android`/`system` 作用域下 `com.android.server.wm.*`（LogicalDisplay、ActivityTaskSupervisor、Task、RootWindowContainer 等）与 `InputMethodManagerService` 的 hook 报 `ClassNotFoundException`（即使加了 `lpparam.classLoader → Thread.contextClassLoader → Class.forName` 三级回退）；`WindowStateAnimator` 的截屏排除 hook 则挂载成功。即显示隔离那批 hook 在 HyperOS 4 上未真正挂载，但当次真机实测虚拟屏创建/迁移/启动/截屏/UI dump 全部可用，说明不是阻塞项；根因疑为 LSPosed 在 system_server 中给模块的 ClassLoader 与 wm 类实际加载器不一致，可在后续版本用延迟重试或 `ClassLoader.getSystemClassLoader()` 继续排查。
- 展开态大岛、岛内按钮（停止/切后台）尚未点按验证。
- 本例中 root 直接写 `/data/data/com.agent.mobileuse/shared_prefs/` 的开关会被应用运行中的回写覆盖，最终改用控制台开关，见第 1 节说明。
