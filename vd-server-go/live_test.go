package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestLiveStateRejectsStaleAndTerminalResurrection(t *testing.T) {
	r := liveRegistry{tasks: map[string]*liveTask{}}
	e := LiveEvent{SessionID: "s", TurnID: "t1", Sequence: 1, Phase: "running"}
	if ok, err := r.accept(e, time.Now()); !ok || err != nil {
		t.Fatal(ok, err)
	}
	e.Sequence = 2
	e.Phase = "foreground"
	if ok, _ := r.accept(e, time.Now()); !ok {
		t.Fatal("foreground transition rejected")
	}
	e.Sequence = 1
	if ok, _ := r.accept(e, time.Now()); ok {
		t.Fatal("stale event applied")
	}
	e.Sequence = 3
	e.Phase = "cancelled"
	r.accept(e, time.Now())
	e.Sequence = 4
	e.Phase = "completed"
	if ok, _ := r.accept(e, time.Now()); ok {
		t.Fatal("cancelled task turned green")
	}
	e.TurnID = "t2"
	e.Sequence = 1
	e.Phase = "running"
	if ok, _ := r.accept(e, time.Now()); !ok {
		t.Fatal("next turn rejected")
	}
	e.TurnID = "t1"
	e.Sequence = 1
	if ok, _ := r.accept(e, time.Now()); ok {
		t.Fatal("retired turn resurrected after next turn")
	}
}

func TestLiveStopIsBoundToTurnAndSurvivesHeartbeat(t *testing.T) {
	r := liveRegistry{tasks: map[string]*liveTask{}}
	e := LiveEvent{SessionID: "s", TurnID: "new", Sequence: 1, Phase: "background"}
	r.accept(e, time.Now())
	if r.control("s", "old") {
		t.Fatal("old notification stopped new turn")
	}
	if !r.control("s", "new") {
		t.Fatal("stop request rejected")
	}
	e.Sequence++
	r.accept(e, time.Now())
	if r.takeCommand("s", "new") != "stop" || r.takeCommand("s", "new") != "stop" {
		t.Fatal("stop intent lost before acknowledgement")
	}
}

func TestLiveProgressDoesNotInventCompletion(t *testing.T) {
	r := liveRegistry{tasks: map[string]*liveTask{}}
	e := LiveEvent{SessionID: "s", TurnID: "t", Sequence: 1, Phase: "running", Total: 2, Completed: 2}
	r.accept(e, time.Now())
	s, active := r.snapshot()
	if !active || s.Phase != "running" {
		t.Fatal("all todos completed was treated as turn end")
	}
	e.Completed = 3
	if _, err := r.accept(e, time.Now()); err == nil {
		t.Fatal("invalid progress accepted")
	}
}

func TestLiveQuestionUsesOnlyCurrentWaitingCapsule(t *testing.T) {
	r := liveRegistry{tasks: map[string]*liveTask{}}
	e := LiveEvent{SessionID: "s", TurnID: "t", Sequence: 1, Phase: "running"}
	r.accept(e, time.Now())
	if r.isWaiting("s", "t") {
		t.Fatal("running task accepted as waiting")
	}
	e.Sequence++
	e.Phase = "waiting"
	r.accept(e, time.Now())
	if !r.isWaiting("s", "t") || r.isWaiting("s", "old") {
		t.Fatal("question turn mismatch")
	}
	e.Sequence++
	e.Phase = "completed"
	r.accept(e, time.Now())
	if r.isWaiting("s", "t") {
		t.Fatal("completed task accepted question")
	}
}

func TestGatewayRejectsMissingTokenAndCrossOrigin(t *testing.T) {
	previous := gatewayToken
	defer func() { gatewayToken = previous }()
	gatewayToken = strings.Repeat("a", 64)
	h := protectGateway(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	for _, tc := range []struct {
		path, token, origin string
		want                int
	}{
		{"/api/action", "", "", 401}, {"/api/action", gatewayToken, "https://external.invalid", 403},
		{"/api/action", gatewayToken, "", 204}, {"/api/status", "", "", 204},
	} {
		req := httptest.NewRequest(http.MethodGet, tc.path, nil)
		if tc.token != "" {
			req.Header.Set("Authorization", "Bearer "+tc.token)
		}
		req.Header.Set("Origin", tc.origin)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, req)
		if w.Code != tc.want {
			t.Fatalf("%s status %d want %d", tc.path, w.Code, tc.want)
		}
	}
}
