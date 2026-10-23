package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func putPluginFixture(t *testing.T, home, relative string, value any) {
	t.Helper()
	f := filepath.Join(home, relative)
	if err := os.MkdirAll(filepath.Dir(f), 0700); err != nil {
		t.Fatal(err)
	}
	b, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(f, b, 0600); err != nil {
		t.Fatal(err)
	}
}

func TestDSHAPluginRegistrationDiagnostics(t *testing.T) {
	for _, tc := range []struct {
		name, link, status, receiptVersion, want string
		disabled, custom                         bool
	}{
		{name: "absolute guest link", link: dshaGuestPlugin, want: "pass"},
		{name: "relative guest link", link: "../../../plugin-src/dsh-mobile-use", want: "pass"},
		{name: "missing link", want: "fail"},
		{name: "wrong guest link", link: "/root/.dsh/wrong", want: "fail"},
		{name: "disabled", disabled: true, want: "pending"},
		{name: "failed activation", status: "failed", receiptVersion: pluginVersion, want: "fail"},
		{name: "changed activation", status: "changed", receiptVersion: pluginVersion, want: "fail"},
		{name: "old receipt", status: "failed", receiptVersion: "0.0.0", link: dshaGuestPlugin, want: "pass"},
		{name: "custom dependency", custom: true, want: "unknown"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			files := t.TempDir()
			home := filepath.Join(files, "user-data-v5/dsh")
			putPluginFixture(t, home, "plugin-src/dsh-mobile-use/package.json", map[string]string{"name": dshaPluginName, "version": pluginVersion})
			bundles := []string{dshaPluginName}
			if tc.disabled {
				bundles = nil
			}
			dependency := "link:" + dshaGuestPlugin
			if tc.custom {
				dependency = "file:/somewhere"
			}
			putPluginFixture(t, home, "profiles/web/package.json", map[string]any{"dependencies": map[string]string{dshaPluginName: dependency}, "dsh": map[string]any{"profile": map[string]any{"bundles": bundles}}})
			if tc.status != "" {
				putPluginFixture(t, home, "plugin-activations.json", map[string]any{"format": 1, "entries": map[string]any{dshaPluginName: map[string]string{"status": tc.status, "version": tc.receiptVersion}}})
			}
			if err := os.MkdirAll(filepath.Join(home, "profiles/web/node_modules"), 0700); err != nil {
				t.Fatal(err)
			}
			if tc.link != "" {
				if err := os.Symlink(tc.link, filepath.Join(home, "profiles/web/node_modules", dshaPluginName)); err != nil {
					t.Fatal(err)
				}
			}
			checks := diagnoseDSHAPlugin(files)
			if len(checks) != 2 || checks[0].State != "pass" || checks[1].State != tc.want {
				t.Fatalf("unexpected diagnostics: %+v", checks)
			}
		})
	}
}

func TestDSHAPluginDiagnosticsUseSelectedHome(t *testing.T) {
	files := t.TempDir()
	putPluginFixture(t, filepath.Join(files, "linux/ubuntu/root/.dsh"), "plugin-src/dsh-mobile-use/package.json", map[string]string{"name": dshaPluginName, "version": pluginVersion})
	if err := os.MkdirAll(filepath.Join(files, "user-data-v5/dsh"), 0700); err != nil {
		t.Fatal(err)
	}
	putPluginFixture(t, files, "data-locations-v5.json", map[string]any{"version": 1, "home": "STABLE"})
	checks := diagnoseDSHAPlugin(files)
	if len(checks) != 1 || checks[0].State != "pending" {
		t.Fatalf("stale rootfs accepted: %+v", checks)
	}
	putPluginFixture(t, filepath.Join(files, "user-data-v5/dsh"), "plugin-src/dsh-mobile-use/package.json", map[string]string{"name": dshaPluginName, "version": "0.0.0"})
	if checks = diagnoseDSHAPlugin(files); checks[0].State != "fail" {
		t.Fatalf("version mismatch accepted: %+v", checks)
	}
}
