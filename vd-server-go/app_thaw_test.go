package main

import (
	"reflect"
	"testing"
)

func TestAppUIDFreezePathsBeforeProcessStarts(t *testing.T) {
	if len(appUIDFreezePaths("10400")) != 2 {
		t.Fatal("App 尚未启动时未覆盖 UID 父组")
	}
	for _, uid := range []string{"0", "system", "10400/..", "010400"} {
		if len(appUIDFreezePaths(uid)) != 0 {
			t.Fatalf("接受了无效 UID: %s", uid)
		}
	}
}

func TestAppFreezePathsIncludeOwnProcess(t *testing.T) {
	for _, base := range []string{"/apps", ""} {
		parent := base + "/uid_10400"
		child := parent + "/pid_8483"
		got := appFreezePaths("10400", "8483", "4:memory:/mimd/uid_10400\n0::"+child+"\n")
		want := []string{"/sys/fs/cgroup" + parent + "/cgroup.freeze", "/sys/fs/cgroup" + child + "/cgroup.freeze"}
		if !reflect.DeepEqual(got, want) {
			t.Fatalf("未覆盖进程子组: got=%v want=%v", got, want)
		}
		if got = appFreezePaths("10400", "8483", "0::"+parent); !reflect.DeepEqual(got, want[:1]) {
			t.Fatalf("UID 组布局不兼容: %v", got)
		}
	}
}

func TestAppFreezePathsRejectOtherProcesses(t *testing.T) {
	for _, tc := range []struct{ uid, pid, group string }{
		{"10400", "8483", "0::/apps/uid_10399/pid_8483"},
		{"10400", "8483", "0::/apps/uid_10400/pid_9310"},
		{"10400", "8483", "4:memory:/apps/uid_10400/pid_8483"},
		{"10400", "8483", "0::/apps/uid_10400/pid_8483/../../uid_10399"},
		{"0", "8483", "0::/apps/uid_0/pid_8483"},
		{"10400/..", "8483", "0::/apps/uid_10400/pid_8483"},
		{"10400", "../8483", "0::/apps/uid_10400/pid_8483"},
	} {
		if got := appFreezePaths(tc.uid, tc.pid, tc.group); len(got) != 0 {
			t.Fatalf("接受了其他进程或无效节点: %+v => %v", tc, got)
		}
	}
}
