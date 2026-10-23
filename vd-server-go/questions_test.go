package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestQuestionHTTPFlowAndExpiredButton(t *testing.T) {
	oldStore, oldLive, oldDeliver := questionStore.items, liveTasks.tasks, deliverQuestion
	defer func() { questionStore.items = oldStore; liveTasks.tasks = oldLive; deliverQuestion = oldDeliver }()
	questionStore.items = map[string]*QuestionRecord{}
	liveTasks.tasks = map[string]*liveTask{}
	deliverQuestion = func(q QuestionRecord) error { return nil }
	liveTasks.accept(LiveEvent{SessionID: "s", TurnID: "t", Sequence: 1, Phase: "waiting"}, time.Now())
	mux := http.NewServeMux()
	registerQuestionRoutes(mux)
	request := func(method, path, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, r)
		return w
	}
	raw, _ := json.Marshal(sampleQuestion())
	if w := request("POST", "/api/question", string(raw)); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	body := `{"request_id":"r","session_id":"s","turn_id":"t","answers":[{"id":"q","selected":["继续"]}]}`
	if w := request("POST", "/api/question/answer", body); w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	w := request("GET", "/api/question?request_id=r&session_id=s&turn_id=t", "")
	var q QuestionRecord
	json.Unmarshal(w.Body.Bytes(), &q)
	if q.State != "submitted" || q.Answers[0].Selected[0] != "继续" {
		t.Fatal(w.Body.String())
	}
	// A new turn must invalidate the old PendingIntent, even with valid auth.
	liveTasks.accept(LiveEvent{SessionID: "s", TurnID: "t2", Sequence: 1, Phase: "running"}, time.Now())
	if w := request("POST", "/api/question/answer", body); w.Code != 409 {
		t.Fatal("old button accepted", w.Code)
	}
	if w := request("GET", "/api/question?request_id=r&session_id=other&turn_id=t", ""); w.Code != 404 {
		t.Fatal("other session read", w.Code)
	}
	if w := request("GET", "/api/question/answer", ""); w.Code != 405 {
		t.Fatal("method accepted", w.Code)
	}
}

func sampleQuestion() QuestionRecord {
	return QuestionRecord{QuestionKey: QuestionKey{"r", "s", "t"}, Bridge: true, Questions: []QuestionItem{{ID: "q", Question: "继续？", Options: []QuestionOption{{Label: "继续"}, {Label: "停止"}}}}}
}
func TestQuestionIdentityAndSettlement(t *testing.T) {
	s := questionRegistry{items: map[string]*QuestionRecord{}}
	q := sampleQuestion()
	if err := s.register(q); err != nil {
		t.Fatal(err)
	}
	a := []QuestionAnswer{{ID: "q", Selected: []string{"继续"}}}
	if _, err := s.answer(QuestionKey{"r", "wrong", "t"}, a); err == nil {
		t.Fatal("wrong session accepted")
	}
	if _, err := s.answer(q.QuestionKey, []QuestionAnswer{{ID: "q", Selected: []string{"fake"}}}); err == nil {
		t.Fatal("fake option")
	}
	if state, err := s.answer(q.QuestionKey, a); err != nil || state != "submitted" {
		t.Fatal(state, err)
	}
	if _, err := s.answer(q.QuestionKey, a); err != nil {
		t.Fatal("retry must be idempotent", err)
	}
	if _, err := s.answer(q.QuestionKey, []QuestionAnswer{{ID: "q", Selected: []string{"停止"}}}); err == nil {
		t.Fatal("answer overwritten")
	}
	s.settle(q.QuestionKey, "answered")
	if _, err := s.answer(q.QuestionKey, a); err == nil {
		t.Fatal("stale button accepted")
	}
}
func TestConcurrentQuestionChoice(t *testing.T) {
	s := questionRegistry{items: map[string]*QuestionRecord{}}
	q := sampleQuestion()
	s.register(q)
	var wg sync.WaitGroup
	success := make(chan bool, 2)
	for _, label := range []string{"继续", "停止"} {
		wg.Add(1)
		go func(l string) {
			defer wg.Done()
			_, err := s.answer(q.QuestionKey, []QuestionAnswer{{ID: "q", Selected: []string{l}}})
			success <- err == nil
		}(label)
	}
	wg.Wait()
	close(success)
	count := 0
	for ok := range success {
		if ok {
			count++
		}
	}
	if count != 1 {
		t.Fatal("must accept one choice", count)
	}
}
func TestCompleteQuestionBatch(t *testing.T) {
	q := sampleQuestion()
	q.Questions = append(q.Questions, QuestionItem{ID: "text", Question: "说明"})
	if validateAnswers(&q, []QuestionAnswer{{ID: "q", Selected: []string{"继续"}}}) == nil {
		t.Fatal("partial batch")
	}
	if err := validateAnswers(&q, []QuestionAnswer{{ID: "q", Selected: []string{"继续"}}, {ID: "text", Custom: "稍后"}}); err != nil {
		t.Fatal(err)
	}
}

func TestSequentialIslandAnswersAndHostWinner(t *testing.T) {
	s := questionRegistry{items: map[string]*QuestionRecord{}}
	q := sampleQuestion()
	q.Questions = append(q.Questions, QuestionItem{ID: "q2", Question: "下一步？", Options: []QuestionOption{{Label: "查看"}, {Label: "返回"}}})
	if err := s.register(q); err != nil {
		t.Fatal(err)
	}
	first, err := s.selectOption(q.QuestionKey, "q", 0)
	if err != nil || first.State != "open" || first.Step != 1 || len(first.Drafts) != 1 || len(first.Answers) != 0 {
		t.Fatal(first, err)
	}
	if _, err := s.selectOption(q.QuestionKey, "q", 1); err == nil {
		t.Fatal("old question advanced next step")
	}
	if _, err := s.selectOption(q.QuestionKey, "q2", 99); err == nil {
		t.Fatal("invalid choice")
	}
	last, err := s.selectOption(q.QuestionKey, "q2", 1)
	if err != nil || last.State != "submitted" || len(last.Answers) != 2 {
		t.Fatal(last, err)
	}
	if _, err := s.selectOption(q.QuestionKey, "q2", 0); err == nil {
		t.Fatal("submitted answer overwritten")
	}
	winner := []QuestionAnswer{{ID: "q", Selected: []string{"停止"}}, {ID: "q2", Selected: []string{"查看"}}}
	if !s.accept(q.QuestionKey, winner, "host") {
		t.Fatal("host winner rejected")
	}
	record, _ := s.snapshot(q.QuestionKey)
	if record.Source != "host" || record.Answers[0].Selected[0] != "停止" || record.State != "answered" {
		t.Fatal(record)
	}
	if s.accept(q.QuestionKey, last.Answers, "island") {
		t.Fatal("late island answer overwrote winner")
	}
}
