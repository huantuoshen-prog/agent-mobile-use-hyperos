package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

const dshaAppDir = "/data/user/0/com.dsh.client"

// Follow DSHA UserDataLayout's selection contract. The guest /root/.dsh may
// be a bind mount of files/user-data-v5/dsh; the rootfs copy can be stale.
// Never create, migrate, or choose between ambiguous homes from this module.
func resolveDSHAHome(files string) (string, error) {
	legacy := filepath.Join(files, "linux/ubuntu/root/.dsh")
	stable := filepath.Join(files, "user-data-v5/dsh")
	record := filepath.Join(files, "data-locations-v5.json")
	if _, err := os.Lstat(record); os.IsNotExist(err) {
		record += ".previous"
	} else if err != nil {
		return "", fmt.Errorf("DSHA 数据位置记录不可读")
	}
	chosen := ""
	if info, err := os.Lstat(record); err == nil {
		if !info.Mode().IsRegular() || info.Size() > 4096 {
			return "", fmt.Errorf("DSHA 数据位置记录无效")
		}
		f, err := os.Open(record)
		if err != nil {
			return "", fmt.Errorf("DSHA 数据位置记录不可读")
		}
		b, err := io.ReadAll(io.LimitReader(f, 4097))
		f.Close()
		var selection struct {
			Version int    `json:"version"`
			Home    string `json:"home"`
		}
		if err != nil || len(b) > 4096 || json.Unmarshal(b, &selection) != nil || selection.Version != 1 {
			return "", fmt.Errorf("DSHA 数据位置记录无效")
		}
		switch selection.Home {
		case "LEGACY":
			chosen = legacy
		case "STABLE":
			chosen = stable
		default:
			return "", fmt.Errorf("DSHA 数据位置记录包含未知目录类型")
		}
	} else if !os.IsNotExist(err) {
		return "", fmt.Errorf("DSHA 数据位置记录不可读")
	} else {
		st, err := os.Lstat(stable)
		if os.IsNotExist(err) {
			chosen = legacy
		} else if err != nil || !st.IsDir() {
			return "", fmt.Errorf("DSHA 独立数据目录不可用")
		} else {
			if _, err := os.Lstat(legacy); !os.IsNotExist(err) {
				return "", fmt.Errorf("DSHA 数据目录存在冲突，请先在 DSHA 中确认数据位置")
			}
			chosen = stable
		}
	}
	st, err := os.Stat(chosen)
	if err != nil || !st.IsDir() {
		return "", fmt.Errorf("DSHA 选用的数据目录未初始化或不可读")
	}
	if !privateDescendant(files, chosen) {
		return "", fmt.Errorf("DSHA 数据目录超出应用私有范围")
	}
	return chosen, nil
}

func privateDescendant(owner, dir string) bool {
	realOwner, e1 := filepath.EvalSymlinks(owner)
	realDir, e2 := filepath.EvalSymlinks(dir)
	return e1 == nil && e2 == nil && strings.HasPrefix(realDir, realOwner+string(os.PathSeparator))
}

func dshaTokenPath() (string, error) {
	home, err := resolveDSHAHome(filepath.Join(dshaAppDir, "files"))
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "mobile-use-token"), nil
}
