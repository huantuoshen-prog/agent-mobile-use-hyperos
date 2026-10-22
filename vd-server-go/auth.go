package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
)

const privateStateDir = "/data/adb/agent_mobile_use"

var gatewayToken string

func initGatewayAuth() error {
	if err := os.MkdirAll(privateStateDir, 0700); err != nil {
		return err
	}
	tokenPath := filepath.Join(privateStateDir, "gateway-token")
	b, err := os.ReadFile(tokenPath)
	if os.IsNotExist(err) {
		raw := make([]byte, 32)
		if _, err = rand.Read(raw); err != nil {
			return err
		}
		b = []byte(hex.EncodeToString(raw))
		if err = os.WriteFile(tokenPath, b, 0600); err != nil {
			return err
		}
	} else if err != nil {
		return err
	}
	gatewayToken = strings.TrimSpace(string(b))
	if len(gatewayToken) < 32 {
		return fmt.Errorf("gateway token invalid")
	}
	// 只把控制凭据放入两个受支持应用的私有目录，不写共享存储。
	for _, item := range [][2]string{
		{"/data/user/0/com.agent.mobileuse/files/gateway-token", "/data/user/0/com.agent.mobileuse"},
		{"/data/user/0/com.dsh.client/files/linux/ubuntu/root/.dsh/mobile-use-token", "/data/user/0/com.dsh.client"},
	} {
		st, e := os.Stat(item[1])
		if e != nil {
			continue
		}
		owner, ok := st.Sys().(*syscall.Stat_t)
		if !ok {
			continue
		}
		dir := filepath.Dir(item[0])
		if _, e = os.Stat(dir); e != nil {
			if item[1] != "/data/user/0/com.agent.mobileuse" {
				continue
			}
			if e = os.Mkdir(dir, 0700); e != nil {
				continue
			}
			_ = os.Chown(dir, int(owner.Uid), int(owner.Gid))
		}
		// root 创建的 files 目录缺少应用 MCS 分类，文件标签正确也无法穿越目录。
		_ = exec.Command("/system/bin/restorecon", "-F", dir).Run()
		if s, e := os.Lstat(item[0]); e == nil && s.Mode()&os.ModeSymlink != 0 {
			continue
		}
		tmp, e := os.CreateTemp(dir, ".mobile-token-")
		if e != nil {
			continue
		}
		name := tmp.Name()
		e = tmp.Chmod(0600)
		if e == nil {
			e = tmp.Chown(int(owner.Uid), int(owner.Gid))
		}
		if e == nil {
			_, e = tmp.Write(b)
		}
		tmp.Close()
		if e == nil {
			e = os.Rename(name, item[0])
		}
		if e == nil {
			_ = exec.Command("/system/bin/restorecon", "-F", item[0]).Run()
		}
		if e != nil {
			os.Remove(name)
		}
	}
	return nil
}

func gatewayAuthorized(r *http.Request, token string) bool {
	got := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if got == "" {
		if cookie, e := r.Cookie("mobile_use"); e == nil {
			got = cookie.Value
		}
	}
	return len(token) >= 32 && len(got) == len(token) && subtle.ConstantTimeCompare([]byte(got), []byte(token)) == 1
}

func protectGateway(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 状态探针不具备动作或凭据读取能力。
		if r.URL.Path == "/api/status" && r.Method == http.MethodGet {
			next.ServeHTTP(w, r)
			return
		}
		if origin := r.Header.Get("Origin"); origin != "" {
			u, e := url.Parse(origin)
			if e != nil || u.Scheme != "http" || (u.Host != "127.0.0.1:3070" && u.Host != "localhost:3070") {
				http.Error(w, "origin not allowed", http.StatusForbidden)
				return
			}
		}
		if !gatewayAuthorized(r, gatewayToken) {
			http.Error(w, "gateway authorization required", http.StatusUnauthorized)
			return
		}
		next.ServeHTTP(w, r)
	})
}
