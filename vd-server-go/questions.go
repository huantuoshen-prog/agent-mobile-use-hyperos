package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os/exec"
	"reflect"
	"strings"
	"sync"
	"time"
)

type QuestionOption struct {
	Label       string `json:"label"`
	Description string `json:"description,omitempty"`
}
type QuestionItem struct {
	ID          string           `json:"id"`
	Question    string           `json:"question"`
	Header      string           `json:"header,omitempty"`
	Detail      string           `json:"detail,omitempty"`
	Options     []QuestionOption `json:"options,omitempty"`
	MultiSelect bool             `json:"multiSelect,omitempty"`
}
type QuestionAnswer struct {
	ID       string   `json:"id"`
	Selected []string `json:"selected"`
	Custom   string   `json:"custom,omitempty"`
}
type QuestionKey struct {
	RequestID string `json:"request_id"`
	SessionID string `json:"session_id"`
	TurnID    string `json:"turn_id"`
}
type QuestionRecord struct {
	QuestionKey
	CallID    string           `json:"call_id,omitempty"`
	Questions []QuestionItem   `json:"questions"`
	Bridge    bool             `json:"bridge"`
	State     string           `json:"state"`
	Answers   []QuestionAnswer `json:"answers,omitempty"`
	Drafts    []QuestionAnswer `json:"drafts,omitempty"`
	Step      int              `json:"step"`
	Source    string           `json:"answer_source,omitempty"`
	updated   time.Time
}
type questionRegistry struct {
	sync.Mutex
	items map[string]*QuestionRecord
}

var questionStore = questionRegistry{items: map[string]*QuestionRecord{}}
var deliverQuestion = sendQuestionToDevice

func validateQuestions(q *QuestionRecord) error {
	if q.RequestID == "" || len(q.RequestID) > 128 || len(q.SessionID) > 256 || len(q.TurnID) > 128 || len(q.Questions) < 1 || len(q.Questions) > 12 {
		return fmt.Errorf("invalid question request")
	}
	ids := map[string]bool{}
	for _, item := range q.Questions {
		if q.Bridge && (item.ID == "" || ids[item.ID]) {
			return fmt.Errorf("question IDs must be unique")
		}
		ids[item.ID] = true
		if strings.TrimSpace(item.Question) == "" || len(item.Options) > 32 {
			return fmt.Errorf("invalid question")
		}
		labels := map[string]bool{}
		for _, o := range item.Options {
			if o.Label == "" || labels[o.Label] {
				return fmt.Errorf("invalid option labels")
			}
			labels[o.Label] = true
		}
	}
	return nil
}
func validateAnswers(q *QuestionRecord, answers []QuestionAnswer) error {
	if len(answers) != len(q.Questions) {
		return fmt.Errorf("answer every question")
	}
	byID := map[string]QuestionAnswer{}
	for _, a := range answers {
		if _, ok := byID[a.ID]; ok {
			return fmt.Errorf("duplicate answer")
		}
		byID[a.ID] = a
	}
	for _, item := range q.Questions {
		a, ok := byID[item.ID]
		if !ok {
			return fmt.Errorf("unknown question ID")
		}
		if (!item.MultiSelect && (len(a.Selected) > 1 || (len(a.Selected) > 0 && strings.TrimSpace(a.Custom) != ""))) || (len(a.Selected) == 0 && strings.TrimSpace(a.Custom) == "") || len(a.Custom) > 8000 {
			return fmt.Errorf("invalid answer selection")
		}
		seen := map[string]bool{}
		for _, label := range a.Selected {
			valid := false
			for _, o := range item.Options {
				if o.Label == label {
					valid = true
				}
			}
			if !valid || seen[label] {
				return fmt.Errorf("unknown or repeated option")
			}
			seen[label] = true
		}
	}
	return nil
}
func (s *questionRegistry) register(q QuestionRecord) error {
	if err := validateQuestions(&q); err != nil {
		return err
	}
	s.Lock()
	defer s.Unlock()
	for id, old := range s.items {
		if time.Since(old.updated) > 30*time.Minute {
			delete(s.items, id)
		}
	}
	if old := s.items[q.RequestID]; old != nil {
		if old.QuestionKey == q.QuestionKey && reflect.DeepEqual(old.Questions, q.Questions) {
			return nil
		}
		return fmt.Errorf("request ID collision")
	}
	if len(s.items) >= 64 {
		return fmt.Errorf("too many questions")
	}
	q.State = "open"
	q.Answers = nil
	q.Drafts = nil
	q.Step = 0
	q.Source = ""
	q.updated = time.Now()
	s.items[q.RequestID] = &q
	return nil
}
func (s *questionRegistry) snapshot(k QuestionKey) (QuestionRecord, bool) {
	s.Lock()
	defer s.Unlock()
	q := s.items[k.RequestID]
	if q == nil || q.QuestionKey != k {
		return QuestionRecord{}, false
	}
	return *q, true
}
func (s *questionRegistry) answer(k QuestionKey, a []QuestionAnswer) (string, error) {
	s.Lock()
	defer s.Unlock()
	q := s.items[k.RequestID]
	if q == nil || q.QuestionKey != k || !q.Bridge {
		return "", fmt.Errorf("question unavailable")
	}
	if q.State == "submitted" && reflect.DeepEqual(q.Answers, a) {
		return q.State, nil
	}
	if q.State != "open" {
		return "", fmt.Errorf("question already settled or submitting")
	}
	if err := validateAnswers(q, a); err != nil {
		return "", err
	}
	q.Answers = a
	q.State = "submitted"
	q.updated = time.Now()
	return q.State, nil
}

func (s *questionRegistry) selectOption(k QuestionKey, id string, option int) (QuestionRecord, error) {
	s.Lock()
	defer s.Unlock()
	q := s.items[k.RequestID]
	if q == nil || q.QuestionKey != k || !q.Bridge || q.State != "open" {
		return QuestionRecord{}, fmt.Errorf("question no longer open")
	}
	if q.Step >= len(q.Questions) {
		return QuestionRecord{}, fmt.Errorf("question already submitted")
	}
	item := q.Questions[q.Step]
	if item.ID != id || item.MultiSelect || option < 0 || option >= len(item.Options) {
		return QuestionRecord{}, fmt.Errorf("question or option changed; refresh")
	}
	q.Drafts = append(q.Drafts, QuestionAnswer{ID: id, Selected: []string{item.Options[option].Label}})
	q.Step++
	if q.Step == len(q.Questions) {
		q.Answers = append([]QuestionAnswer{}, q.Drafts...)
		q.State = "submitted"
	}
	q.updated = time.Now()
	return *q, nil
}
func (s *questionRegistry) settle(k QuestionKey, state string) bool {
	s.Lock()
	defer s.Unlock()
	q := s.items[k.RequestID]
	if q == nil || q.QuestionKey != k {
		return false
	}
	if q.State == "answered" || q.State == "cancelled" {
		return q.State == state
	}
	q.State = state
	q.updated = time.Now()
	return true
}

// The host waterfall chooses the winner. A concurrently submitted island choice
// may lose to the host UI; retain the actual accepted result, not that draft.
func (s *questionRegistry) accept(k QuestionKey, answers []QuestionAnswer, source string) bool {
	s.Lock()
	defer s.Unlock()
	q := s.items[k.RequestID]
	if q == nil || q.QuestionKey != k || !q.Bridge || q.State == "cancelled" || (source != "host" && source != "island") {
		return false
	}
	if q.State == "answered" {
		return reflect.DeepEqual(q.Answers, answers) && q.Source == source
	}
	if validateAnswers(q, answers) != nil {
		return false
	}
	q.Answers, q.Source, q.State, q.updated = answers, source, "answered", time.Now()
	return true
}
func questionKey(r *http.Request) QuestionKey {
	q := r.URL.Query()
	return QuestionKey{q.Get("request_id"), q.Get("session_id"), q.Get("turn_id")}
}
func sendQuestionToDevice(q QuestionRecord) error {
	data, _ := json.Marshal(q)
	thawAppProcess()
	return exec.Command("/system/bin/am", "broadcast", "-n", "com.agent.mobileuse/.NotifyReceiver", "-a", "com.agent.mobileuse.ACTION_LIVE_QUESTION", "--es", "request_id", q.RequestID, "--es", "session_id", q.SessionID, "--es", "turn_id", q.TurnID, "--es", "data", string(data)).Run()
}
func registerQuestionRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/api/question/select", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != "POST" {
			http.Error(w, "POST required", 405)
			return
		}
		var p struct {
			QuestionKey
			ID     string `json:"question_id"`
			Option *int   `json:"option_index"`
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048)).Decode(&p) != nil || p.Option == nil {
			http.Error(w, "invalid choice", 400)
			return
		}
		if !liveTasks.isWaiting(p.SessionID, p.TurnID) {
			http.Error(w, "question no longer waiting", 409)
			return
		}
		q, err := questionStore.selectOption(p.QuestionKey, p.ID, *p.Option)
		if err != nil {
			http.Error(w, err.Error(), 409)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(q)
	})
	mux.HandleFunc("/api/question", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.Method == http.MethodGet {
			q, ok := questionStore.snapshot(questionKey(r))
			if !ok {
				http.Error(w, "question not found", 404)
				return
			}
			if q.Bridge && !liveTasks.isWaiting(q.SessionID, q.TurnID) && (q.State == "open" || q.State == "submitted") {
				questionStore.settle(q.QuestionKey, "cancelled")
				q.State = "cancelled"
			}
			json.NewEncoder(w).Encode(q)
			return
		}
		if r.Method != http.MethodPost {
			http.Error(w, "POST required", 405)
			return
		}
		var q QuestionRecord
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 49152)).Decode(&q) != nil {
			http.Error(w, "invalid request", 400)
			return
		}
		if q.Bridge && (q.TurnID == "" || !liveTasks.isWaiting(q.SessionID, q.TurnID)) {
			http.Error(w, "waiting turn unavailable", 409)
			return
		}
		if err := questionStore.register(q); err != nil {
			http.Error(w, err.Error(), 400)
			return
		}
		q, _ = questionStore.snapshot(q.QuestionKey)
		if err := deliverQuestion(q); err != nil {
			http.Error(w, "notification delivery failed", 502)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"success": true, "notification": "queued", "bridge": q.Bridge})
	})
	mux.HandleFunc("/api/question/answer", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST required", 405)
			return
		}
		var p struct {
			QuestionKey
			Answers []QuestionAnswer `json:"answers"`
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 32768)).Decode(&p) != nil {
			http.Error(w, "invalid answer", 400)
			return
		}
		if !liveTasks.isWaiting(p.SessionID, p.TurnID) {
			http.Error(w, "question no longer waiting", 409)
			return
		}
		state, err := questionStore.answer(p.QuestionKey, p.Answers)
		if err != nil {
			http.Error(w, err.Error(), 409)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"success": true, "state": state})
	})
	mux.HandleFunc("/api/question/settle", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST required", 405)
			return
		}
		var p struct {
			QuestionKey
			State   string           `json:"state"`
			Answers []QuestionAnswer `json:"answers"`
			Source  string           `json:"answer_source"`
		}
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 32768)).Decode(&p) != nil || (p.State != "answered" && p.State != "cancelled") {
			http.Error(w, "invalid settlement", 400)
			return
		}
		var ok bool
		if p.State == "answered" {
			ok = questionStore.accept(p.QuestionKey, p.Answers, p.Source)
		} else {
			ok = questionStore.settle(p.QuestionKey, p.State)
		}
		if ok {
			cancelQuestionOnDevice(p.RequestID)
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"success": ok})
	})
	mux.HandleFunc("/api/question/cancel", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST required", 405)
			return
		}
		var k QuestionKey
		if json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048)).Decode(&k) != nil || k.RequestID == "" {
			http.Error(w, "invalid request", 400)
			return
		}
		// Legacy notification-only cancellation cannot cancel a bridged host question.
		questionStore.Lock()
		q := questionStore.items[k.RequestID]
		if q != nil && !q.Bridge {
			k = q.QuestionKey
		}
		questionStore.Unlock()
		ok := questionStore.settle(k, "cancelled")
		if ok {
			cancelQuestionOnDevice(k.RequestID)
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{"success": ok})
	})
}
