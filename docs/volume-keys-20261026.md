# 副屏运行期间实体音量键失效

日期：2026-10-26。环境：Redmi K90、Android 17 / HyperOS 4，模块 0.11.0-alpha 加当前修复候选。

## 现场与定位

用户报告副屏开启期间实体音量键无效，音量仍可通过其他方式调整，退出副屏后按键恢复。

ADB `input keyevent` 在副屏创建前后均能改变音量，不能用于替代实体按键验收。用户实际按键时，`getevent` 记录到 `KEY_VOLUMEUP` / `KEY_VOLUMEDOWN` 的 DOWN / UP 事件，媒体音量保持为 0；全局输入焦点为主屏 0。

读屏进程通过 `UiAutomation.connect(0)` 连接。Android 默认模式会暂停其他无障碍服务；`FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` 允许既有服务继续运行。该标志从 API 24 提供，模块运行环境为 API 26 及以上。依据：[Android UiAutomation](https://developer.android.google.cn/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)、[AOSP 实现](https://android.googlesource.com/platform/frameworks/base.git/+/master/core/java/android/app/UiAutomation.java)。

## 同一副屏的对照

整个对照过程保持同一副屏 PID 6878、display ID 17，未销毁或重建副屏。

| 状态 | UiAutomation | 其他无障碍服务 | 内核按键注入后的媒体音量 |
| --- | --- | --- | --- |
| 原读屏进程运行 | 运行 | Bound services 为空 | 0 → 0 |
| 仅停止原读屏进程 | 停止 | 恢复绑定 | 0 → 10 |
| 修复后的读屏进程运行 | 运行 | 保持绑定 | 10 → 20 |

内核对照使用已识别的音量键设备节点发送一次短按事件，经过 InputReader 的硬件事件路径。用户随后再次按实体音量键，确认恢复正常。

## 修复与交付

`ToolMain` 使用 `UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` 连接，删除会恢复默认独占模式的无参数连接回退。读屏服务、其他无障碍服务及虚拟副屏可以同时运行。

- JDK 17 / Android SDK 37 的 Java 编译及 D8（min API 26）生成 DEX 通过。
- 已更新手机模块目录与临时运行目录中的 `agent_tools.dex`，原件及原校验清单均已备份。
- 新读屏进程读取真实副屏控件树成功，其他无障碍服务保持绑定，用户实体按键验收通过。
- 模块 APK、虚拟屏守护进程及网关保持原构件；模块二进制校验清单同步更新并通过核验。
- 诊断结束后已回收副屏，并恢复测试前的媒体音量 0、铃声音量 5。

本次未验证其他 ROM 的实体音量键行为；正式 Release 尚未更新。
