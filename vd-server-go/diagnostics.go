package main

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"
)

type DiagnosticCheck struct {
	ID     string `json:"id"`
	State  string `json:"state"`
	Detail string `json:"detail"`
	Next   string `json:"next,omitempty"`
}
type DiagnosticReport struct {
	Schema   int               `json:"schema_version"`
	Module   string            `json:"module_version"`
	Plugin   string            `json:"expected_plugin_version"`
	Protocol int               `json:"protocol_version"`
	Checks   []DiagnosticCheck `json:"checks"`
}

var integration struct {
	sync.Mutex
	Version  string
	Instance string
	Tools    int
	Seen     time.Time
}

func diagnosticCommand(name string, args ...string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, name, args...).Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}
func diagnoseToken(path, owner string) DiagnosticCheck {
	c := DiagnosticCheck{ID: "dsha_token", State: "fail", Detail: "DSHA 连接凭据不可用", Next: "初始化 DSHA 后点击修复连接"}
	st, err := os.Stat(owner)
	if err != nil {
		c.State = "pending"
		c.Detail = "DSHA 尚未安装或初始化"
		return c
	}
	if _, err = os.Stat(filepath.Dir(path)); err != nil {
		c.State = "pending"
		c.Detail = "请先打开 DSHA，完成运行环境初始化"
		return c
	}
	if !privateDescendant(owner, filepath.Dir(path)) {
		c.Detail = "DSHA 凭据目录超出应用私有范围"
		return c
	}
	file, err := os.Lstat(path)
	if err != nil {
		return c
	}
	a, e1 := os.ReadFile(path)
	b, e2 := os.ReadFile(filepath.Join(privateStateDir, "gateway-token"))
	fileOwner, ok1 := file.Sys().(*syscall.Stat_t)
	appOwner, ok2 := st.Sys().(*syscall.Stat_t)
	if e1 == nil && e2 == nil && len(a) >= 32 && subtle.ConstantTimeCompare(a, b) == 1 && file.Mode().Perm() == 0600 && file.Mode()&os.ModeSymlink == 0 && ok1 && ok2 && fileOwner.Uid == appOwner.Uid {
		c.State = "pass"
		c.Detail = "私有凭据已交付，内容和权限匹配"
		c.Next = ""
	}
	return c
}
func diagnoseDSHAToken() DiagnosticCheck {
	path, err := dshaTokenPath()
	if err != nil {
		return DiagnosticCheck{ID: "dsha_token", State: "pending", Detail: err.Error(), Next: "在 DSHA 中确认运行环境和数据位置后重试"}
	}
	return diagnoseToken(path, dshaAppDir)
}
func diagnostics(online bool) DiagnosticReport {
	d := DiagnosticReport{Schema: 1, Module: moduleVersion, Plugin: pluginVersion, Protocol: gatewayProtocol, Checks: []DiagnosticCheck{}}
	add := func(id, state, detail, next string) {
		d.Checks = append(d.Checks, DiagnosticCheck{id, state, detail, next})
	}
	if os.Geteuid() == 0 {
		add("root", "pass", "模块运行于 root 环境", "")
	} else {
		add("root", "fail", "未获得模块运行权限", "从 KernelSU 检查模块")
	}
	abi := diagnosticCommand("/system/bin/getprop", "ro.product.cpu.abi")
	if abi == "arm64-v8a" {
		add("architecture", "pass", abi, "")
	} else {
		add("architecture", "unknown", abi, "当前发布构件支持 ARM64")
	}
	add("system", "info", diagnosticCommand("/system/bin/getprop", "ro.build.version.release")+" / "+diagnosticCommand("/system/bin/getprop", "ro.mi.os.version.name"), "")
	module := "/data/adb/modules/agent_mobile_use"
	if _, err := os.Stat("/data/adb/ksu"); err == nil {
		add("kernelsu", "pass", "KernelSU 数据目录存在", "")
	} else {
		add("kernelsu", "unknown", "未检测到 KernelSU 数据目录", "在 Root 管理器确认模块兼容性")
	}
	if _, err := os.Stat("/data/adb/modules_update/agent_mobile_use/module.prop"); err == nil {
		add("reboot", "pending", "模块更新等待重启", "重启并首次解锁后重新检查")
	}
	if online {
		add("gateway", "pass", "本机网关已响应", "")
	} else {
		add("gateway", "unknown", "离线诊断模式", "从控制中心重新检查网关")
	}
	installed := diagnosticCommand("/system/bin/dumpsys", "package", "com.dsh.client")
	version := regexp.MustCompile(`versionName=([^\s]+)`).FindStringSubmatch(installed)
	if len(version) > 1 {
		add("dsha_app", "pass", "DSHA "+version[1], "")
	} else {
		add("dsha_app", "pending", "未检测到 DSHA", "安装并初始化 DSHA")
	}
	d.Checks = append(d.Checks, diagnoseDSHAToken())
	db := "/data/adb/lspd/config/modules_config.db"
	good := false
	if _, err := os.Stat(db); err == nil {
		// The bundled executor takes only DB + SQL and has no row callback or
		// -readonly flag. Enforce read-only SQL and use its exit code. SQLite's
		// integer-overflow expression fails only when the predicate is false.
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		sql := "PRAGMA query_only=ON; SELECT CASE WHEN EXISTS(SELECT 1 FROM modules_state WHERE module_pkg_name='com.agent.mobileuse' AND user_id=0 AND enabled=1) AND (SELECT count(DISTINCT app_pkg_name) FROM scope WHERE module_pkg_name='com.agent.mobileuse' AND user_id=0 AND app_pkg_name IN ('android','com.android.systemui','com.agent.mobileuse','com.xiaomi.xmsf'))=4 THEN 1 ELSE abs(-9223372036854775808) END;"
		good = exec.CommandContext(ctx, filepath.Join(module, "bin/sqlite3"), db, sql).Run() == nil
		cancel()
	}
	if good {
		add("lsposed_scope", "pass", "模块已启用，必要作用域配置完整", "实际 Hook 生效情况请在控制中心检查")
	} else {
		add("lsposed_scope", "pending", "作用域配置未确认完整", "在 LSPosed 启用本模块并核对作用域，然后重启")
	}
	integration.Lock()
	v, tools, seen := integration.Version, integration.Tools, integration.Seen
	integration.Unlock()
	if seen.IsZero() || time.Since(seen) > 90*time.Second {
		add("dsha_plugin", "unknown", "尚未收到插件注册确认或连接已过期", "导入配套插件并重启 DSHA Web")
	} else if v != pluginVersion || tools != 16 {
		add("dsha_plugin", "fail", "插件版本或工具数量不匹配", "安装配套插件 "+pluginVersion)
	} else {
		add("dsha_plugin", "pass", "插件 "+v+" 已注册 16 个工具", "")
	}
	add("acceptance", "pending", "实际副屏与超级岛效果需要验收", "让 DSHA 调用 mobile_status 并执行只读副屏任务")
	return d
}
func registerDiagnostics(mux *http.ServeMux) {
	mux.HandleFunc("/api/diagnostics", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "GET" {
			http.Error(w, "GET required", 405)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(diagnostics(true))
	})
	mux.HandleFunc("/api/integration", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			http.Error(w, "POST required", 405)
			return
		}
		var p struct {
			Version  string `json:"version"`
			Instance string `json:"instance_id"`
			Tools    int    `json:"tools_registered"`
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048)).Decode(&p) != nil || len(p.Version) > 40 || p.Version == "" || p.Instance == "" || len(p.Instance) > 100 || p.Tools < 0 || p.Tools > 100 {
			http.Error(w, "invalid registration", 400)
			return
		}
		integration.Lock()
		integration.Version = p.Version
		integration.Instance = p.Instance
		integration.Tools = p.Tools
		integration.Seen = time.Now()
		integration.Unlock()
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"success":true}`))
	})
	mux.HandleFunc("/api/integration/repair", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			http.Error(w, "POST required", 405)
			return
		}
		// Uses the already initialized token and fixed app-private destinations only.
		deliverGatewayToken([]byte(gatewayToken))
		check := diagnoseDSHAToken()
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"success": check.State == "pass", "check": check})
	})
}
