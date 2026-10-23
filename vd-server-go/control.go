package main

import (
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"time"
)

const controlHeader = "X-Mobile-Use-Control"
const controlTTL = 30 * time.Second

type controlLease struct {
	sync.Mutex
	owner    string
	expires  time.Time
	inFlight int
}

var remoteControl controlLease

func (l *controlLease) acquire(id string, now time.Time) bool {
	return l.acquireWhen(id, now, nil)
}
func (l *controlLease) acquireWhen(id string, now time.Time, allowed func() bool) bool {
	l.Lock()
	defer l.Unlock()
	if l.owner == id && now.Before(l.expires) {
		l.expires = now.Add(controlTTL)
		return true
	}
	if l.owner != "" || l.inFlight != 0 {
		return false
	}
	if allowed != nil && !allowed() {
		return false
	}
	l.owner, l.expires = id, now.Add(controlTTL)
	return true
}
func (l *controlLease) renew(id string, now time.Time) bool {
	l.Lock()
	defer l.Unlock()
	if l.owner != id || !now.Before(l.expires) {
		return false
	}
	l.expires = now.Add(controlTTL)
	return true
}
func (l *controlLease) revoke(id string) bool {
	l.Lock()
	defer l.Unlock()
	if l.owner != id {
		return false
	}
	l.expires = time.Time{}
	return true
}
func (l *controlLease) begin(id string, emergency bool, now time.Time) bool {
	l.Lock()
	defer l.Unlock()
	if emergency {
		l.expires = time.Time{}
	} else if id != "" {
		if id != l.owner || !now.Before(l.expires) {
			return false
		}
	} else if l.owner != "" {
		return false
	}
	l.inFlight++
	return true
}
func (l *controlLease) end() { l.Lock(); l.inFlight--; l.Unlock() }
func (l *controlLease) expire(now time.Time, cleanup func(string)) bool {
	l.Lock()
	if l.owner == "" || now.Before(l.expires) || l.inFlight != 0 {
		l.Unlock()
		return false
	}
	id := l.owner
	l.inFlight++ // Keep new owners out until the old display is closed.
	l.Unlock()
	cleanup(id)
	l.Lock()
	l.owner = ""
	l.expires = time.Time{}
	l.inFlight--
	l.Unlock()
	return true
}
func controlledRoute(route string) bool {
	switch route {
	case "/api/start", "/api/stop", "/api/mode", "/api/action", "/api/resize", "/api/audio/toggle", "/api/audio/unmute-all", "/api/live/event", "/api/notify", "/api/question", "/api/question/settle", "/api/question/cancel":
		return true
	}
	return false
}
func guardControl(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := r.Header.Get(controlHeader)
		guard := controlledRoute(r.URL.Path) || (id != "" && (r.URL.Path == "/api/screenshot" || r.URL.Path == "/api/dump_ui"))
		// The phone's answer activity must still read and answer an open request.
		if r.URL.Path == "/api/question" && r.Method == http.MethodGet && id == "" {
			guard = false
		}
		if !guard {
			next.ServeHTTP(w, r)
			return
		}
		if !remoteControl.begin(id, r.URL.Path == "/api/stop" && id == "", time.Now()) {
			http.Error(w, "CONTROL_BUSY_OR_EXPIRED", http.StatusConflict)
			return
		}
		defer remoteControl.end()
		next.ServeHTTP(w, r)
	})
}
func registerControlRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/control/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST required", 405)
			return
		}
		id := r.Header.Get(controlHeader)
		if len(id) < 16 || len(id) > 128 || strings.ContainsAny(id, " \t\r\n") {
			http.Error(w, "invalid control identity", 400)
			return
		}
		ok := false
		released := false
		switch r.URL.Path {
		case "/api/control/acquire":
			ok = remoteControl.acquireWhen(id, time.Now(), func() bool {
				_, active := liveTasks.snapshot()
				return !active
			})
		case "/api/control/renew":
			ok = remoteControl.renew(id, time.Now())
		case "/api/control/release":
			ok = remoteControl.revoke(id)
			if ok {
				released = remoteControl.expire(time.Now(), cleanupRemoteControl)
			}
		default:
			http.NotFound(w, r)
			return
		}
		if !ok {
			http.Error(w, "CONTROL_BUSY_OR_EXPIRED", 409)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"success": true, "ttl_seconds": int(controlTTL.Seconds()), "released": released})
	})
	go func() {
		for now := range time.NewTicker(2 * time.Second).C {
			remoteControl.expire(now, cleanupRemoteControl)
		}
	}()
}

func cleanupRemoteControl(owner string) {
	setCurrentMode("idle")
	stopVirtualDisplay()
	unmuteAllAudio()
	sid := "remote-" + owner
	liveTasks.Lock()
	task := liveTasks.tasks[sid]
	var event LiveEvent
	if task != nil && !liveTerminal(task.Phase) {
		task.Phase = "cancelled"
		task.Detail = "远程控制连接已结束"
		task.Sequence++
		task.updated = time.Now()
		event = task.LiveEvent
	}
	liveTasks.Unlock()
	if event.SessionID != "" {
		setSessionActive(false, event.SessionID, event.Title)
		go postLiveOutcome(event)
	}
	questionStore.Lock()
	var requests []string
	for _, q := range questionStore.items {
		if q.SessionID == sid && (q.State == "open" || q.State == "submitted") {
			q.State = "cancelled"
			q.updated = time.Now()
			requests = append(requests, q.RequestID)
		}
	}
	questionStore.Unlock()
	for _, id := range requests {
		cancelQuestionOnDevice(id)
	}
}
