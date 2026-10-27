package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestAudioModeFromOutput(t *testing.T) {
	for out, want := range map[string]string{
		"PLAY_AUDIO: ignore; time=123":            "ignore",
		"PLAY_AUDIO: foreground":                  "foreground",
		"PLAY_AUDIO: default":                     "allow",
		"No operations.":                          "allow",
		"No operations.\nDefault mode: allow":     "allow",
		"No operations.\nDefault mode: ignore":    "ignore",
		"No operations.\nDefault mode: default":   "allow",
		"PLAY_AUDIO: ignore\nDefault mode: allow": "ignore",
	} {
		got, ok := audioModeFromOutput(out)
		if !ok || got != want {
			t.Fatalf("%q: %q %v", out, got, ok)
		}
	}
	if _, ok := audioModeFromOutput("permission denied"); ok {
		t.Fatal("unknown state converted to allow")
	}
	if _, ok := audioModeFromOutput("No operations.\nDefault mode: unknown"); ok {
		t.Fatal("unknown default mode converted to allow")
	}
}

// Model the real get/set/query-op interaction while exercising the on-disk
// journal and production mute/restore functions together.
func fakeAudioAppOps(t *testing.T, modes map[string]string) {
	t.Helper()
	oldCommand, oldPath := runAudioCommand, audioJournalPath
	oldOriginal, oldMuted := originalAudioModes, mutedPackages
	audioJournalPath = filepath.Join(t.TempDir(), "audio-restore.json")
	originalAudioModes, mutedPackages = make(map[string]string), make(map[string]int)
	t.Cleanup(func() {
		runAudioCommand, audioJournalPath = oldCommand, oldPath
		originalAudioModes, mutedPackages = oldOriginal, oldMuted
	})
	runAudioCommand = func(args ...string) ([]byte, error) {
		if len(args) < 6 || args[0] != "appops" || args[2] != "--user" {
			return nil, fmt.Errorf("unexpected command: %v", args)
		}
		key := args[3] + ":" + args[4]
		switch args[1] {
		case "get":
			if modes[key] == "" {
				return []byte("No operations.\nDefault mode: allow"), nil
			}
			return []byte("PLAY_AUDIO: " + modes[key]), nil
		case "set":
			modes[key] = args[6]
			return nil, nil
		case "query-op":
			var packages []string
			for key, mode := range modes {
				parts := strings.SplitN(key, ":", 2)
				if parts[0] == args[3] && mode == args[5] {
					packages = append(packages, parts[1])
				}
			}
			return []byte(strings.Join(packages, "\n")), nil
		}
		return nil, fmt.Errorf("unexpected command: %v", args)
	}
}

func TestAudioMuteRestoreLifecycle(t *testing.T) {
	for _, initial := range []string{"", "default", "allow", "ignore", "deny", "foreground"} {
		t.Run("original="+initial, func(t *testing.T) {
			key := "0:com.test.player"
			modes := map[string]string{key: initial}
			fakeAudioAppOps(t, modes)
			want := initial
			if want == "" || want == "default" {
				want = "allow"
			}
			setPackageMuted("com.test.player", 0, true)
			if modes[key] != "ignore" || originalAudioModes[key] != want {
				t.Fatalf("mute failed: modes=%v journal=%v", modes, originalAudioModes)
			}
			b, err := os.ReadFile(audioJournalPath)
			if err != nil || !strings.Contains(string(b), want) {
				t.Fatalf("original mode not persisted: %s %v", b, err)
			}
			setPackageMuted("com.test.player", 0, false)
			if modes[key] != want || len(originalAudioModes) != 0 || len(mutedPackages) != 0 {
				t.Fatalf("restore failed: modes=%v journal=%v muted=%v", modes, originalAudioModes, mutedPackages)
			}
			b, err = os.ReadFile(audioJournalPath)
			if err != nil || string(b) != "{}" {
				t.Fatalf("restored journal not cleared: %s %v", b, err)
			}
		})
	}
}

func TestAudioStartupRepairsLegacyJournalAndUntrackedDefaults(t *testing.T) {
	modes := map[string]string{
		"0:com.test.recorded":   "ignore",
		"0:com.test.untracked":  "default",
		"999:com.test.clone":    "default",
		"0:com.test.muted":      "ignore",
		"0:com.test.denied":     "deny",
		"0:com.test.foreground": "foreground",
	}
	fakeAudioAppOps(t, modes)
	legacy, _ := json.Marshal(map[string]string{"0:com.test.recorded": "default"})
	if err := os.WriteFile(audioJournalPath, legacy, 0600); err != nil {
		t.Fatal(err)
	}
	restoreAudioState([]int{0, 999})
	want := map[string]string{
		"0:com.test.recorded":   "allow",
		"0:com.test.untracked":  "allow",
		"999:com.test.clone":    "allow",
		"0:com.test.muted":      "ignore",
		"0:com.test.denied":     "deny",
		"0:com.test.foreground": "foreground",
	}
	if !reflect.DeepEqual(modes, want) {
		t.Fatalf("legacy recovery mismatch: got %v, want %v", modes, want)
	}
	if len(originalAudioModes) != 0 {
		t.Fatalf("restored journal still tracked: %v", originalAudioModes)
	}
}

func TestParseUserIDsFromOutput(t *testing.T) {
	sample := `Users:
	UserInfo{0:机主:4c13} running
	UserInfo{999:MultiApp:4001010} running
`
	got := parseUserIDsFromOutput(sample)
	want := []int{0, 999}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("parseUserIDsFromOutput mismatch: got %v, want %v", got, want)
	}

	emptySample := "No users"
	gotDefault := parseUserIDsFromOutput(emptySample)
	if len(gotDefault) != 1 || gotDefault[0] != 0 {
		t.Fatalf("expected fallback [0], got %v", gotDefault)
	}
}

func TestMutedPackagesTracking(t *testing.T) {
	audioMu.Lock()
	mutedPackages = make(map[string]int)
	audioMu.Unlock()

	if len(mutedPackages) != 0 {
		t.Fatal("expected empty mutedPackages initially")
	}

	// Verify state mutation under lock
	audioMu.Lock()
	mutedPackages["com.test.app"] = 0
	mutedPackages["com.test.clone"] = 999
	audioMu.Unlock()

	audioMu.Lock()
	count := len(mutedPackages)
	u0 := mutedPackages["com.test.app"]
	u999 := mutedPackages["com.test.clone"]
	mutedPackages = make(map[string]int)
	audioMu.Unlock()

	if count != 2 || u0 != 0 || u999 != 999 {
		t.Fatalf("mutedPackages tracking error: count=%d, u0=%d, u999=%d", count, u0, u999)
	}
}
