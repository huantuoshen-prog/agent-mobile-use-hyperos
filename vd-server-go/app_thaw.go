package main

import (
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
)

func appUIDFreezePaths(uid string) []string {
	u, err := strconv.ParseUint(uid, 10, 32)
	if err != nil || u < 10000 || strconv.FormatUint(u, 10) != uid {
		return nil
	}
	return []string{"/sys/fs/cgroup/apps/uid_" + uid + "/cgroup.freeze", "/sys/fs/cgroup/uid_" + uid + "/cgroup.freeze"}
}

// 仅处理模块 App 自己的 cgroup v2 节点；HyperOS 可单独冻结 pid 子组。
func appFreezePaths(uid, pid, cgroups string) []string {
	if len(appUIDFreezePaths(uid)) == 0 {
		return nil
	}
	p, err := strconv.ParseUint(pid, 10, 32)
	if err != nil || p == 0 || strconv.FormatUint(p, 10) != pid {
		return nil
	}
	for _, line := range strings.Split(cgroups, "\n") {
		parts := strings.SplitN(line, ":", 3)
		if len(parts) != 3 || parts[0] != "0" || parts[1] != "" {
			continue
		}
		for _, base := range []string{"/apps", ""} {
			parent := fmt.Sprintf("%s/uid_%s", base, uid)
			child := fmt.Sprintf("%s/pid_%s", parent, pid)
			if parts[2] == parent || parts[2] == child {
				paths := []string{"/sys/fs/cgroup" + parent + "/cgroup.freeze"}
				if parts[2] == child {
					paths = append(paths, "/sys/fs/cgroup"+child+"/cgroup.freeze")
				}
				return paths
			}
		}
	}
	return nil
}

func thawAppCgroups(uid string) {
	// App 尚未启动时也要清理 UID 父组，否则新进程会继承冻结状态。
	for _, path := range appUIDFreezePaths(uid) {
		_ = os.WriteFile(path, []byte("0"), 0644)
	}
	pids, err := exec.Command("/system/bin/pidof", "com.agent.mobileuse").Output()
	if err != nil {
		return
	}
	for _, pid := range strings.Fields(string(pids)) {
		groups, err := os.ReadFile("/proc/" + pid + "/cgroup")
		if err != nil {
			continue
		}
		for _, path := range appFreezePaths(uid, pid, string(groups)) {
			_ = os.WriteFile(path, []byte("0"), 0644)
		}
	}
}
