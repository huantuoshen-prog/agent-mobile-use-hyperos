package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
)

const dshaPluginName = "dsh-mobile-use"
const dshaGuestPlugin = "/root/.dsh/plugin-src/" + dshaPluginName

// Read only the native manager's known metadata, without returning its contents
// or editing registration, activation receipts, symlinks, or disabled markers.
func readPluginDiagnostic(home, relative string, value any) error {
	file := filepath.Join(home, relative)
	if !privateDescendant(home, file) {
		return fmt.Errorf("metadata unavailable")
	}
	st, err := os.Lstat(file)
	if err != nil || !st.Mode().IsRegular() || st.Size() > 1024*1024 {
		return fmt.Errorf("metadata unavailable")
	}
	f, err := os.Open(file)
	if err != nil {
		return err
	}
	defer f.Close()
	b, err := io.ReadAll(io.LimitReader(f, 1024*1024+1))
	if err != nil || len(b) > 1024*1024 {
		return fmt.Errorf("metadata unavailable")
	}
	return json.Unmarshal(b, value)
}

func diagnoseDSHAPlugin(files string) []DiagnosticCheck {
	checks := []DiagnosticCheck{}
	add := func(id, state, detail, next string) {
		checks = append(checks, DiagnosticCheck{id, state, detail, next})
	}
	home, err := resolveDSHAHome(files)
	if err != nil {
		add("dsha_plugin_files", "unknown", "当前数据目录不可用，尚未检查插件文件", "先在 DSHA 确认运行环境和数据位置")
		return checks
	}
	var pkg struct{ Name, Version string }
	if readPluginDiagnostic(home, "plugin-src/"+dshaPluginName+"/package.json", &pkg) != nil || pkg.Name != dshaPluginName {
		add("dsha_plugin_files", "pending", "当前数据目录未找到插件管理器安装的配套插件", "在 DSHA 原生插件管理器导入配套 ZIP；保留现有数据")
		return checks
	}
	if pkg.Version != pluginVersion {
		add("dsha_plugin_files", "fail", "已安装插件与当前模块要求的版本不匹配", "在 DSHA 导入配套插件 "+pluginVersion)
	} else {
		add("dsha_plugin_files", "pass", "当前数据目录已安装配套插件 "+pluginVersion, "")
	}
	var profile struct {
		Dependencies map[string]string `json:"dependencies"`
		DSH          struct {
			Profile struct {
				Bundles []string `json:"bundles"`
			} `json:"profile"`
		} `json:"dsh"`
	}
	if readPluginDiagnostic(home, "profiles/web/package.json", &profile) != nil {
		add("dsha_plugin_profile", "unknown", "DSHA Web 配置尚未初始化或不可读取", "在 DSHA 原生启动页检查配置与启动日志")
		return checks
	}
	enabled := false
	for _, name := range profile.DSH.Profile.Bundles {
		if name == dshaPluginName {
			enabled = true
			break
		}
	}
	var activations struct {
		Format  int                                         `json:"format"`
		Entries map[string]struct{ Version, Status string } `json:"entries"`
	}
	if readPluginDiagnostic(home, "plugin-activations.json", &activations) == nil && activations.Format == 1 {
		receipt := activations.Entries[dshaPluginName]
		if receipt.Version == pkg.Version && (receipt.Status == "failed" || receipt.Status == "changed") {
			add("dsha_plugin_profile", "fail", "DSHA 已记录当前插件加载失败或内容变化", "在 DSHA 原生恢复页查看启动日志，经插件管理器修复后重新启用并启动 Web")
			return checks
		}
	}
	if !enabled {
		add("dsha_plugin_profile", "pending", "插件未在当前 DSHA Web 配置中启用", "在 DSHA 原生插件管理器检查并启用，然后重启 Web")
		return checks
	}
	if profile.Dependencies[dshaPluginName] != "link:"+dshaGuestPlugin {
		add("dsha_plugin_profile", "unknown", "当前插件使用自定义安装位置", "从 DSHA 启动日志确认加载结果，或经原生插件管理器导入配套 ZIP")
		return checks
	}
	link := filepath.Join(home, "profiles/web/node_modules", dshaPluginName)
	if !privateDescendant(home, filepath.Dir(link)) {
		add("dsha_plugin_profile", "fail", "已启用插件的加载目录不可用", "在 DSHA 原生插件管理器重新导入配套 ZIP")
		return checks
	}
	target, err := os.Readlink(link)
	if err != nil {
		state := "unknown"
		if _, statErr := os.Lstat(link); os.IsNotExist(statErr) {
			state = "fail"
		}
		add("dsha_plugin_profile", state, "已启用插件的加载链接缺失或形式未确认", "在 DSHA 原生插件管理器检查安装，然后核对启动日志")
		return checks
	}
	// Absolute /root links belong to the guest namespace. The Android host
	// cannot stat them directly when /root/.dsh is mounted from the stable home.
	if !path.IsAbs(target) {
		target = path.Join("/root/.dsh/profiles/web/node_modules", target)
	}
	if path.Clean(target) != dshaGuestPlugin {
		add("dsha_plugin_profile", "fail", "插件加载链接与当前注册目录不一致", "经 DSHA 原生插件管理器重新导入；不要手改链接")
		return checks
	}
	add("dsha_plugin_profile", "pass", "插件已启用，Web 加载链接与注册目录一致", "启动 Web 后确认工具注册")
	return checks
}
