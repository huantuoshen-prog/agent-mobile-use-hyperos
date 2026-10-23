package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDSHAHomeSelection(t *testing.T) {
	for _, tc := range []struct {
		name, record, previous, want string
		legacy, stable               bool
	}{
		{name: "old installation", legacy: true, want: "legacy"},
		{name: "new stable installation", stable: true, want: "stable"},
		{name: "explicit stable with stale rootfs", legacy: true, stable: true, record: `{"version":1,"home":"STABLE"}`, want: "stable"},
		{name: "explicit legacy with staged stable", legacy: true, stable: true, record: `{"version":1,"home":"LEGACY"}`, want: "legacy"},
		{name: "recover previous selection", legacy: true, stable: true, previous: `{"version":1,"home":"STABLE"}`, want: "stable"},
		{name: "current overrides previous", legacy: true, stable: true, record: `{"version":1,"home":"LEGACY"}`, previous: `{"version":1,"home":"STABLE"}`, want: "legacy"},
		{name: "ambiguous homes", legacy: true, stable: true},
		{name: "not initialized"},
		{name: "missing selected stable", legacy: true, record: `{"version":1,"home":"STABLE"}`},
		{name: "unknown version", legacy: true, record: `{"version":2,"home":"LEGACY"}`},
		{name: "invalid record", legacy: true, record: `{`},
		{name: "invalid current does not use previous", stable: true, record: `{`, previous: `{"version":1,"home":"STABLE"}`},
		{name: "unknown home", legacy: true, record: `{"version":1,"home":"OTHER"}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			files := t.TempDir()
			legacy, stable := filepath.Join(files, "linux/ubuntu/root/.dsh"), filepath.Join(files, "user-data-v5/dsh")
			for path, enabled := range map[string]bool{legacy: tc.legacy, stable: tc.stable} {
				if enabled {
					if err := os.MkdirAll(path, 0700); err != nil {
						t.Fatal(err)
					}
				}
			}
			for suffix, body := range map[string]string{"": tc.record, ".previous": tc.previous} {
				if body != "" {
					if err := os.WriteFile(filepath.Join(files, "data-locations-v5.json"+suffix), []byte(body), 0600); err != nil {
						t.Fatal(err)
					}
				}
			}
			got, err := resolveDSHAHome(files)
			if tc.want == "" {
				if err == nil {
					t.Fatalf("unexpected home: %s", got)
				}
				return
			}
			want := legacy
			if tc.want == "stable" {
				want = stable
			}
			if err != nil || got != want {
				t.Fatalf("got %q, %v; want %q", got, err, want)
			}
		})
	}
}

func TestDSHAHomeRejectsExternalSelectedDirectory(t *testing.T) {
	files, external := t.TempDir(), t.TempDir()
	if err := os.MkdirAll(filepath.Join(files, "user-data-v5"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(external, filepath.Join(files, "user-data-v5/dsh")); err != nil {
		t.Skip(err)
	}
	if err := os.WriteFile(filepath.Join(files, "data-locations-v5.json"), []byte(`{"version":1,"home":"STABLE"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := resolveDSHAHome(files); err == nil {
		t.Fatal("accepted a home outside app-private files")
	}
}

func TestDSHAHomeRejectsSymlinkSelectionRecord(t *testing.T) {
	files := t.TempDir()
	record := filepath.Join(t.TempDir(), "selection.json")
	if err := os.WriteFile(record, []byte(`{"version":1,"home":"STABLE"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(record, filepath.Join(files, "data-locations-v5.json")); err != nil {
		t.Skip(err)
	}
	if _, err := resolveDSHAHome(files); err == nil {
		t.Fatal("accepted a redirected selection record")
	}
}
