package main

import (
	"encoding/json"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
)

var (
	audioMu            sync.Mutex
	vdAudioMuteEnabled = true
	// mutedPackages tracks package -> userId for apps silenced on the virtual display
	mutedPackages = make(map[string]int)
)

func isAudioMuteEnabled() bool {
	audioMu.Lock()
	defer audioMu.Unlock()
	return vdAudioMuteEnabled
}

func setAudioMuteEnabled(enabled bool) {
	audioMu.Lock()
	vdAudioMuteEnabled = enabled
	audioMu.Unlock()
	if !enabled {
		unmuteAllAudio()
	} else {
		// ponytail: immediate check on active VD; routine sweep will maintain it
		st := getStatus()
		if st.DisplayID > 0 && getCurrentMode() == "background" {
			reconcileVdAudio(st.DisplayID)
		}
	}
}

var originalAudioModes = make(map[string]string)
var audioJournalPath = filepath.Join(privateStateDir, "audio-restore.json")
var runAudioCommand = func(args ...string) ([]byte, error) {
	return exec.Command("/system/bin/cmd", args...).Output()
}

// PLAY_AUDIO's effective default is allow. Replaying the literal MODE_DEFAULT
// saved by older gateways leaves AudioService's opPlayAudio mute in place.
func restorableAudioMode(mode string) string {
	if mode == "default" {
		return "allow"
	}
	return mode
}

func audioModeFromOutput(out string) (string, bool) {
	re := regexp.MustCompile(`PLAY_AUDIO:\s*(allow|ignore|default|foreground|deny)`)
	if match := re.FindStringSubmatch(out); len(match) == 2 {
		return restorableAudioMode(match[1]), true
	}
	if strings.Contains(out, "No operations") {
		// Newer Android versions report the effective default explicitly.
		defaultMode := regexp.MustCompile(`Default mode:\s*(allow|ignore|default|foreground|deny)`)
		if match := defaultMode.FindStringSubmatch(out); len(match) == 2 {
			return restorableAudioMode(match[1]), true
		}
		// PLAY_AUDIO defaults to MODE_ALLOWED. MODE_DEFAULT is a distinct
		// AppOps value and restoring it can leave playback muted.
		if strings.TrimSpace(out) == "No operations." || strings.TrimSpace(out) == "No operations" {
			return "allow", true
		}
	}
	return "", false
}
func saveAudioJournalLocked() error {
	b, err := json.Marshal(originalAudioModes)
	if err != nil {
		return err
	}
	p := audioJournalPath
	if err = os.WriteFile(p+".tmp", b, 0600); err != nil {
		return err
	}
	return os.Rename(p+".tmp", p)
}
func setPackageMuted(pkg string, userId int, mute bool) {
	if pkg == "" || strings.Contains(pkg, "launcher") || strings.Contains(pkg, "systemui") || pkg == "android" {
		return
	}
	key := strconv.Itoa(userId) + ":" + pkg
	audioMu.Lock()
	defer audioMu.Unlock()
	original, tracked := originalAudioModes[key]
	if mute {
		if !tracked {
			out, err := runAudioCommand("appops", "get", "--user", strconv.Itoa(userId), pkg, "PLAY_AUDIO")
			if err != nil {
				return
			}
			var ok bool
			original, ok = audioModeFromOutput(string(out))
			if !ok {
				return
			}
			originalAudioModes[key] = original
			if saveAudioJournalLocked() != nil {
				delete(originalAudioModes, key)
				return
			}
		}
		if _, err := runAudioCommand("appops", "set", "--user", strconv.Itoa(userId), pkg, "PLAY_AUDIO", "ignore"); err == nil {
			mutedPackages[pkg] = userId
		}
	} else if tracked {
		if _, err := runAudioCommand("appops", "set", "--user", strconv.Itoa(userId), pkg, "PLAY_AUDIO", restorableAudioMode(original)); err == nil {
			delete(originalAudioModes, key)
			delete(mutedPackages, pkg)
			_ = saveAudioJournalLocked()
		}
	}
}

func parseUserIDsFromOutput(out string) []int {
	re := regexp.MustCompile(`UserInfo\{(\d+):`)
	matches := re.FindAllStringSubmatch(out, -1)
	var uids []int
	for _, m := range matches {
		if len(m) > 1 {
			if id, err := strconv.Atoi(m[1]); err == nil {
				uids = append(uids, id)
			}
		}
	}
	if len(uids) == 0 {
		return []int{0}
	}
	return uids
}

func parseUserIDs() []int {
	out, err := exec.Command("/system/bin/pm", "list", "users").Output()
	if err != nil {
		return []int{0, 999}
	}
	return parseUserIDsFromOutput(string(out))
}

// unmuteAllAudio restores only AppOps recorded by this module before muting.
func unmuteAllAudio() {
	audioMu.Lock()
	defer audioMu.Unlock()
	for key, original := range originalAudioModes {
		parts := strings.SplitN(key, ":", 2)
		if len(parts) != 2 {
			continue
		}
		if _, err := runAudioCommand("appops", "set", "--user", parts[0], parts[1], "PLAY_AUDIO", restorableAudioMode(original)); err == nil {
			delete(originalAudioModes, key)
			delete(mutedPackages, parts[1])
		}
	}
	_ = saveAudioJournalLocked()
}

// reconcileVdAudio synchronizes package audio states with current VD tasks
func reconcileVdAudio(vdDid int) {
	if !isAudioMuteEnabled() || vdDid <= 0 || getCurrentMode() != "background" {
		audioMu.Lock()
		needsUnmute := len(mutedPackages) > 0
		audioMu.Unlock()
		if needsUnmute {
			unmuteAllAudio()
		}
		return
	}

	stacks := getStackList()
	vdPkgs := make(map[string]int)
	for _, s := range stacks {
		if s.DisplayID == vdDid {
			for _, pkg := range s.Packages {
				if strings.Contains(pkg, "launcher") || strings.Contains(pkg, "systemui") || pkg == "android" {
					continue
				}
				vdPkgs[pkg] = s.UserID
			}
		}
	}

	audioMu.Lock()
	currentMuted := make(map[string]int, len(mutedPackages))
	for k, v := range mutedPackages {
		currentMuted[k] = v
	}
	audioMu.Unlock()

	// 1. Mute apps now on VD
	for pkg, uId := range vdPkgs {
		if _, ok := currentMuted[pkg]; !ok {
			setPackageMuted(pkg, uId, true)
		}
	}

	// 2. Unmute apps that left VD
	for pkg, uId := range currentMuted {
		if _, ok := vdPkgs[pkg]; !ok {
			setPackageMuted(pkg, uId, false)
		}
	}
}

// Older gateways removed their journal after restoring the wrong literal
// default. Repair those untracked entries too; explicit ignore/deny/foreground
// modes are excluded by query-op's mode filter.
func repairLegacyAudioDefaults(userIDs []int) {
	pkgPattern := regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$`)
	for _, userID := range userIDs {
		user := strconv.Itoa(userID)
		out, err := runAudioCommand("appops", "query-op", "--user", user, "PLAY_AUDIO", "default")
		if err != nil {
			log.Printf("[AudioGuard] Cannot query legacy playback modes for user %s: %v", user, err)
			continue
		}
		for _, pkg := range strings.Fields(string(out)) {
			if !pkgPattern.MatchString(pkg) {
				continue
			}
			if _, err := runAudioCommand("appops", "set", "--user", user, pkg, "PLAY_AUDIO", "allow"); err != nil {
				log.Printf("[AudioGuard] Cannot repair legacy playback mode for %s:%s: %v", user, pkg, err)
			}
		}
	}
}

func restoreAudioState(userIDs []int) {
	if b, err := os.ReadFile(audioJournalPath); err == nil {
		audioMu.Lock()
		_ = json.Unmarshal(b, &originalAudioModes)
		audioMu.Unlock()
	}
	unmuteAllAudio()
	repairLegacyAudioDefaults(userIDs)
}

func initAudioGuard() {
	restoreAudioState(parseUserIDs())
}
