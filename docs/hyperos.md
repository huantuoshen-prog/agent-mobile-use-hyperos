# HyperOS / MIUI 超级岛适配说明（v0.9.0-alpha）

本文描述 `agent-mobile-use` 在 HyperOS（含 MIUI 系）上的「超级岛 / 焦点通知」适配。目标机型为 HyperOS 4 / Android 17 真机（用户报告），但**当前版本尚未在真机上验证**，见文末「验证状态」。

## 1. 默认状态与开关

- **默认关闭**。升级安装不会改变通知行为；不开启时三类通知（胶囊 / 完成 / 提问）保持普通通知。
- 开关位置：`agent_hook.apk` 控制台 →「系统特性偏好」→「超级岛通知（HyperOS）」。
- 该开关写入 SharedPreferences `agent_capsule_state` 的 `enable_super_island`（`HyperIsland.PREF_ENABLE`），非 HyperOS 机型上开关置灰不可点。
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

# 3. 本应用焦点通知权限（true/false）
adb shell content call --uri content://miui.statusbar.notification.public \
  --method canShowFocus --arg com.agent.mobileuse --extra package:s:com.agent.mobileuse

# 4. 通知里是否真的带上了岛 JSON
adb shell dumpsys notification --noredact | grep -A2 miui.focus.param

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

- **未实机验证**：当前交付只做了 javac 编译校验与 JVM 单测（`IslandPayloadSelftest`），没有 HyperOS 4 真机运行记录；上岛效果、字段接受度、XMSF 类名均需真机确认。
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
