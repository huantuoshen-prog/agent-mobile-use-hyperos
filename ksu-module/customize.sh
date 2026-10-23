SKIPUNZIP=0

[ "$ARCH" = "arm64" ] || abort "当前发布包仅支持 ARM64。"
[ "${KSU:-}" = "true" ] || abort "当前版本需要 KernelSU 模块环境。"
[ -f /data/adb/lspd/config/modules_config.db ] || abort "请先安装并启用 LSPosed，再安装本模块。"
[ -f "$MODPATH/binaries.sha256" ] || abort "发布包缺少构件校验文件。"
(cd "$MODPATH" && sha256sum -c binaries.sha256) || abort "包内构件校验失败，安装已停止。"

MODULE_VERSION=$(sed -n 's/^version=//p' "$MODPATH/module.prop" | tr -d '\r')
ui_print "**********************************************"
ui_print "* Agent Mobile Sandbox & Tools v$MODULE_VERSION *"
ui_print "*               作者：酸小明                 *"
ui_print "**********************************************"

ui_print "- 正在安装 LSPosed 跨屏路由与输入法隔离补丁 (v$MODULE_VERSION)..."
ui_print "- 检测到 HyperOS/MIUI 时将启用超级岛(焦点通知)适配与 XMSF 校验解锁"
NOTIFICATION_WAS_GRANTED=0
if dumpsys package com.agent.mobileuse 2>/dev/null | grep -q 'android.permission.POST_NOTIFICATIONS: granted=true'; then
    NOTIFICATION_WAS_GRANTED=1
fi
if PM_RESULT=$(pm install -r "$MODPATH/apk/agent_hook.apk" 2>&1); then
    ui_print "- Hook 补丁安装成功"
    pm grant com.agent.mobileuse android.permission.RECORD_AUDIO >/dev/null 2>&1
    if [ "$NOTIFICATION_WAS_GRANTED" = 1 ]; then
        pm grant com.agent.mobileuse android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
    fi
else
    ui_print "$PM_RESULT"
    abort "APK 覆盖安装失败：请核对签名，模块安装已停止；未删除 App 数据。"
fi

# Configure LSPosed scope DB
LSP_DB="/data/adb/lspd/config/modules_config.db"
SQLITE_BIN="$MODPATH/bin/sqlite3"
chmod 755 "$SQLITE_BIN" 2>/dev/null

if [ -f "$LSP_DB" ] && [ -x "$SQLITE_BIN" ]; then
    ui_print "- 正在自动配置 LSPosed 模块作用域..."
    APK_PATH=$(pm path com.agent.mobileuse 2>/dev/null | head -n 1 | cut -d':' -f2)
    if [ -n "$APK_PATH" ]; then
        "$SQLITE_BIN" "$LSP_DB" "INSERT OR IGNORE INTO modules (module_pkg_name, apk_path) VALUES ('com.agent.mobileuse', '$APK_PATH'); UPDATE modules SET apk_path='$APK_PATH' WHERE module_pkg_name='com.agent.mobileuse';" 2>/dev/null
        "$SQLITE_BIN" "$LSP_DB" "INSERT OR IGNORE INTO modules_state (module_pkg_name, user_id, enabled) VALUES ('com.agent.mobileuse', 0, 1); UPDATE modules_state SET enabled=1 WHERE module_pkg_name='com.agent.mobileuse' AND user_id=0;" 2>/dev/null
        "$SQLITE_BIN" "$LSP_DB" "INSERT OR IGNORE INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES ('com.agent.mobileuse', 'android', 0);" 2>/dev/null
        "$SQLITE_BIN" "$LSP_DB" "INSERT OR IGNORE INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES ('com.agent.mobileuse', 'system', 0);" 2>/dev/null
        "$SQLITE_BIN" "$LSP_DB" "INSERT OR IGNORE INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES ('com.agent.mobileuse', 'com.android.systemui', 0);" 2>/dev/null
        "$SQLITE_BIN" "$LSP_DB" "INSERT OR IGNORE INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES ('com.agent.mobileuse', 'com.agent.mobileuse', 0);" 2>/dev/null
        # HyperOS/MIUI: XMSF verifies the focus-notification (super island) whitelist.
        "$SQLITE_BIN" "$LSP_DB" "INSERT OR IGNORE INTO scope (module_pkg_name, app_pkg_name, user_id) VALUES ('com.agent.mobileuse', 'com.xiaomi.xmsf', 0);" 2>/dev/null
        # Bundled sqlite executor has no result-row output. Check its exit code
        # with a read-only conditional expression, including the enabled flag.
        if "$SQLITE_BIN" "$LSP_DB" "PRAGMA query_only=ON; SELECT CASE WHEN EXISTS(SELECT 1 FROM modules_state WHERE module_pkg_name='com.agent.mobileuse' AND user_id=0 AND enabled=1) AND (SELECT count(DISTINCT app_pkg_name) FROM scope WHERE module_pkg_name='com.agent.mobileuse' AND user_id=0 AND app_pkg_name IN ('android','com.android.systemui','com.agent.mobileuse','com.xiaomi.xmsf'))=4 THEN 1 ELSE abs(-9223372036854775808) END;" >/dev/null 2>&1; then
            ui_print "- LSPosed 必要作用域已配置。重启后在控制中心检查实际生效状态。"
        else
            ui_print "! LSPosed 自动配置未确认，请手动启用本模块并核对作用域。"
        fi
    fi
fi

ui_print "- 设置可执行权限..."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/vd" 0 0 0755
set_perm "$MODPATH/bin/run_daemon.sh" 0 0 0755
set_perm "$MODPATH/bin/vd_server" 0 0 0755
set_perm "$MODPATH/bin/sqlite3" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755

# Instant activation without waiting for reboot
cp -f "$MODPATH/bin/agent_vd.dex" /data/local/tmp/agent_vd.dex
cp -f "$MODPATH/bin/agent_tools.dex" /data/local/tmp/agent_tools.dex
cp -f "$MODPATH/bin/run_daemon.sh" /data/local/tmp/run_daemon.sh
chmod 755 /data/local/tmp/run_daemon.sh

ui_print "**********************************************"
ui_print "* 安装完成！                                 *"
ui_print "* 随时在终端执行 vd tree / vd tap / vd type  *"
ui_print "* 浏览器访问 http://127.0.0.1:3070 查看监控  *"
ui_print "**********************************************"
