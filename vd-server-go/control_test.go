package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestControlLeaseOwnershipAndExpiry(t *testing.T) {
	var lease controlLease
	now := time.Now()
	if lease.acquireWhen("first", now, func() bool { return false }) {
		t.Fatal("acquired during another live task")
	}
	if !lease.begin("", false, now) {
		t.Fatal("local operation blocked without remote owner")
	}
	if lease.acquire("first", now) {
		t.Fatal("acquired during local operation")
	}
	lease.end()
	if !lease.acquire("first", now) || lease.acquire("second", now) {
		t.Fatal("ownership not exclusive")
	}
	if lease.begin("", false, now) || lease.begin("second", false, now) || lease.renew("second", now) || lease.revoke("second") {
		t.Fatal("foreign client accepted")
	}
	if !lease.begin("first", false, now) {
		t.Fatal("owner rejected")
	}
	expired := now.Add(controlTTL + time.Second)
	if lease.begin("first", false, expired) || lease.renew("first", expired) {
		t.Fatal("expired owner accepted")
	}
	cleaned := false
	cleanup := func(id string) {
		cleaned = true
		if id != "first" || lease.acquire("second", expired) {
			t.Fatal("new owner admitted before cleanup")
		}
	}
	if lease.expire(expired, cleanup) || cleaned {
		t.Fatal("cleaned while operation still running")
	}
	lease.end()
	if !lease.expire(expired, cleanup) || !cleaned || !lease.acquire("second", expired) {
		t.Fatal("expired owner not recovered")
	}
	if lease.begin("first", false, expired) {
		t.Fatal("previous owner's delayed action accepted")
	}
}

func TestControlLeaseReleaseAndEmergencyStop(t *testing.T) {
	for _, emergency := range []bool{false, true} {
		var lease controlLease
		now := time.Now()
		lease.acquire("owner", now)
		if emergency {
			if !lease.begin("", true, now) {
				t.Fatal("phone stop denied")
			}
			lease.end()
		} else if !lease.revoke("owner") {
			t.Fatal("release denied")
		}
		if lease.begin("owner", false, now) || lease.renew("owner", now) {
			t.Fatal("released owner accepted")
		}
		if !lease.expire(now, func(string) {}) || !lease.acquire("next", now) {
			t.Fatal("release failed to recover")
		}
	}
}

func TestControlGuardProtectsActionsAndPreservesPhoneAnswers(t *testing.T) {
	remoteControl = controlLease{}
	defer func() { remoteControl = controlLease{} }()
	owner := "owner-with-valid-identity"
	remoteControl.acquire(owner, time.Now())
	handler := guardControl(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	for _, tc := range []struct {
		route, method, id string
		want              int
	}{
		{"/api/action", "POST", "", 409}, {"/api/action", "POST", owner, 204},
		{"/api/action", "POST", "wrong", 409}, {"/api/live/event", "POST", "", 409},
		{"/api/question", "POST", "", 409}, {"/api/question", "GET", "", 204},
		{"/api/question/answer", "POST", "", 204}, {"/api/question/select", "POST", "", 204},
		{"/api/live/control", "POST", "", 204}, {"/api/status", "GET", "", 204},
		{"/api/screenshot", "GET", "wrong", 409},
	} {
		r := httptest.NewRequest(tc.method, tc.route, nil)
		r.Header.Set(controlHeader, tc.id)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != tc.want {
			t.Errorf("%s %s: got %d want %d", tc.method, tc.route, w.Code, tc.want)
		}
	}
}
