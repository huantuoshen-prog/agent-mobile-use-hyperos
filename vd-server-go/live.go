package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

type LiveEvent struct {
	SessionID      string `json:"session_id"`
	TurnID         string `json:"turn_id"`
	Sequence       int64  `json:"sequence"`
	Title          string `json:"session_title"`
	Phase          string `json:"phase"`
	Detail         string `json:"detail"`
	Source         string `json:"source"`
	DisplayBackend string `json:"display_backend"`
	Total          int    `json:"total"`
	Completed      int    `json:"completed"`
}
type liveTask struct {
	LiveEvent
	updated time.Time
	command string
}
type liveRegistry struct {
	sync.Mutex
	tasks   map[string]*liveTask
	retired map[string][]string
}

var liveTasks = liveRegistry{tasks: make(map[string]*liveTask)}

func liveTerminal(phase string) bool {
	return phase == "completed" || phase == "failed" || phase == "cancelled"
}
func validLivePhase(phase string) bool {
	return phase == "running" || phase == "foreground" || phase == "background" || phase == "waiting" || liveTerminal(phase)
}
func (r *liveRegistry) accept(e LiveEvent, now time.Time) (bool, error) {
	if e.SessionID == "" || len(e.SessionID) > 256 || e.TurnID == "" || len(e.TurnID) > 128 || e.Sequence < 1 || !validLivePhase(e.Phase) || e.Total < 0 || e.Completed < 0 || (e.Total > 0 && e.Completed > e.Total) || (e.DisplayBackend != "" && e.DisplayBackend != "root" && e.DisplayBackend != "dsha" && e.DisplayBackend != "physical") {
		return false, fmt.Errorf("invalid live event")
	}
	r.Lock()
	defer r.Unlock()
	old := r.tasks[e.SessionID]
	for _, turn := range r.retired[e.SessionID] {
		if turn == e.TurnID {
			return false, nil
		}
	}
	if old != nil {
		if old.TurnID == e.TurnID {
			if old.Sequence >= e.Sequence || liveTerminal(old.Phase) {
				return false, nil
			}
		} else if liveTerminal(e.Phase) {
			return false, nil
		}
	} else if liveTerminal(e.Phase) {
		return false, nil
	}
	if len(r.tasks) >= 64 && old == nil {
		return false, fmt.Errorf("too many live sessions")
	}
	e.Title = trimLive(e.Title, 80)
	e.Detail = trimLive(e.Detail, 160)
	command := ""
	if old != nil && old.TurnID == e.TurnID {
		command = old.command
	} else if old != nil {
		if r.retired == nil {
			r.retired = make(map[string][]string)
		}
		history := append(r.retired[e.SessionID], old.TurnID)
		if len(history) > 8 {
			history = history[len(history)-8:]
		}
		r.retired[e.SessionID] = history
	}
	r.tasks[e.SessionID] = &liveTask{LiveEvent: e, updated: now, command: command}
	return true, nil
}
func trimLive(s string, n int) string {
	r := []rune(strings.TrimSpace(s))
	if len(r) > n {
		r = r[:n]
	}
	return string(r)
}
func (r *liveRegistry) snapshot() (LiveEvent, bool) {
	r.Lock()
	defer r.Unlock()
	var newest *liveTask
	for _, t := range r.tasks {
		if !liveTerminal(t.Phase) && (newest == nil || t.updated.After(newest.updated)) {
			newest = t
		}
	}
	if newest == nil {
		return LiveEvent{}, false
	}
	return newest.LiveEvent, true
}
func (r *liveRegistry) control(sid, turn string) bool {
	r.Lock()
	defer r.Unlock()
	t := r.tasks[sid]
	if t == nil || t.TurnID != turn || liveTerminal(t.Phase) {
		return false
	}
	t.command = "stop"
	return true
}
func (r *liveRegistry) isWaiting(sid, turn string) bool {
	r.Lock()
	defer r.Unlock()
	t := r.tasks[sid]
	return t != nil && t.TurnID == turn && t.Phase == "waiting"
}
func (r *liveRegistry) takeCommand(sid, turn string) string {
	r.Lock()
	defer r.Unlock()
	t := r.tasks[sid]
	if t == nil || t.TurnID != turn {
		return ""
	}
	return t.command // 直到终态确认前保留取消意图，连接中断不会吞掉停止请求。
}

func registerLiveRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/live/event", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.Method != http.MethodPost {
			http.Error(w, "POST required", 405)
			return
		}
		var e LiveEvent
		dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16384))
		dec.DisallowUnknownFields()
		if err := dec.Decode(&e); err != nil {
			http.Error(w, "invalid event", 400)
			return
		}
		accepted, err := liveTasks.accept(e, time.Now())
		if err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		if accepted {
			if liveTerminal(e.Phase) {
				setSessionActive(false, e.SessionID, e.Title)
				go postLiveOutcome(e)
			} else {
				setSessionActive(true, e.SessionID, e.Title)
			}
		}
		json.NewEncoder(w).Encode(map[string]any{"success": true, "accepted": accepted, "notification": "queued"})
	})
	mux.HandleFunc("/api/live/control", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST required", 405)
			return
		}
		var p struct {
			SessionID string `json:"session_id"`
			TurnID    string `json:"turn_id"`
			Action    string `json:"action"`
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&p) != nil || p.Action != "stop" {
			http.Error(w, "invalid control", 400)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		ok := liveTasks.control(p.SessionID, p.TurnID)
		json.NewEncoder(w).Encode(map[string]any{"success": ok, "message": "stop requested; wait for task acknowledgement"})
	})
	mux.HandleFunc("/api/live/commands", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"success": true, "command": liveTasks.takeCommand(r.URL.Query().Get("session_id"), r.URL.Query().Get("turn_id"))})
	})
	mux.HandleFunc("/api/live/status", func(w http.ResponseWriter, r *http.Request) {
		e, active := liveTasks.snapshot()
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"success": true, "active": active, "task": e})
	})
	go func() {
		for range time.NewTicker(10 * time.Second).C {
			var expired []LiveEvent
			liveTasks.Lock()
			for sid, t := range liveTasks.tasks {
				if time.Since(t.updated) > 60*time.Second {
					if !liveTerminal(t.Phase) {
						expired = append(expired, t.LiveEvent)
					}
					delete(liveTasks.tasks, sid)
					delete(liveTasks.retired, sid)
				}
			}
			liveTasks.Unlock()
			for _, e := range expired {
				setSessionActive(false, e.SessionID, e.Title)
			}
		}
	}()
}

func postLiveOutcome(e LiveEvent) {
	thawAppProcess()
	title := "任务已完成"
	if e.Phase == "failed" {
		title = "任务未完成"
	}
	if e.Phase == "cancelled" {
		title = "任务已停止"
	}
	// 参数独立传入 am，任务文字不会经过 shell 展开。
	cmd := exec.Command("/system/bin/am", "broadcast", "-n", "com.agent.mobileuse/.NotifyReceiver", "-a", "com.agent.mobileuse.ACTION_LIVE_OUTCOME",
		"--es", "outcome", e.Phase, "--es", "title", title, "--es", "content", e.Detail, "--es", "session_title", e.Title, "--es", "session_id", e.SessionID, "--es", "turn_id", e.TurnID,
		"--ei", "total", strconv.Itoa(e.Total), "--ei", "completed", strconv.Itoa(e.Completed))
	if out, err := cmd.CombinedOutput(); err != nil {
		fmt.Printf("[live] outcome delivery failed: %v (%d bytes)\n", err, len(out))
	}
}

func handleManualNotice(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	if r.Method != http.MethodPost {
		http.Error(w, "POST required", 405)
		return
	}
	var p struct {
		Title        string `json:"title"`
		SessionTitle string `json:"session_title"`
		Content      string `json:"content"`
		SessionID    string `json:"session_id"`
		Total        int    `json:"total"`
		Completed    int    `json:"completed"`
		IsCompleted  bool   `json:"is_completed"`
	}
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 16384)).Decode(&p) != nil || p.Total < 0 || p.Completed < 0 || (p.Total > 0 && p.Completed > p.Total) {
		http.Error(w, "invalid notification", 400)
		return
	}
	sid := p.SessionID
	if sid == "" {
		sid = "manual"
	}
	liveTasks.Lock()
	old := liveTasks.tasks[sid]
	var e LiveEvent
	if old != nil {
		e = old.LiveEvent
	}
	liveTasks.Unlock()
	if e.SessionID == "" || liveTerminal(e.Phase) {
		e = LiveEvent{SessionID: sid, TurnID: fmt.Sprintf("manual-%d", time.Now().UnixNano()), Phase: "running", Source: "manual"}
	}
	e.Sequence++
	e.Title = p.SessionTitle
	if e.Title == "" {
		e.Title = p.Title
	}
	e.Detail = p.Content
	e.Total = p.Total
	e.Completed = p.Completed
	done := p.IsCompleted || (p.Total > 0 && p.Completed >= p.Total)
	if done && e.Source == "manual" {
		if e.Sequence == 1 {
			_, _ = liveTasks.accept(e, time.Now())
			e.Sequence++
		}
		e.Phase = "completed"
	}
	accepted, err := liveTasks.accept(e, time.Now())
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	if accepted {
		if liveTerminal(e.Phase) {
			setSessionActive(false, e.SessionID, e.Title)
			go postLiveOutcome(e)
		} else {
			setSessionActive(true, e.SessionID, e.Title)
		}
	}
	json.NewEncoder(w).Encode(map[string]any{"success": true, "accepted": accepted, "notification": "queued", "completion_source": "turn/end for automatic sessions"})
}
