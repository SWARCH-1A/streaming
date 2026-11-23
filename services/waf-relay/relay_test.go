package main

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// Shape of a real ModSecurity v3.0.17 Concurrent audit record (parts A, H, Z), with fixture values.
const blocked = `{"transaction":{"client_ip":"192.0.2.10","time_stamp":"Mon Nov 23 15:19:37 2026","server_id":"x",
"client_port":34572,"host_ip":"192.0.2.3","host_port":8443,"unique_id":"2ef5522f57c4fe9648f2d11c094825dc","is_interrupted":true,
"request":{"method":"GET","http_version":"1.1","hostname":"localhost","uri":"/api/taxonomy?q=%27%20OR%201%3D1--"},
"response":{"http_code":403},"producer":{"modsecurity":"ModSecurity v3.0.17 (Linux)"},
"messages":[{"message":"SQL Injection Attack Detected via libinjection","details":{"ruleId":"942100","data":"Matched Data: s&1c found within ARGS:q: ' OR 1=1--"}},
{"message":"Inbound Anomaly Score Exceeded (Total Score: 5)","details":{"ruleId":"949110","data":""}}]}}`

func TestBlockBecomesAnUnattributedAuditEvent(t *testing.T) {
	body, id, publish, err := Convert([]byte(blocked))
	if err != nil || !publish {
		t.Fatal(err, publish)
	}
	var fields map[string]any
	if err := json.Unmarshal(body, &fields); err != nil || len(fields) != 9 {
		t.Fatalf("envelope must have exactly nine fields: %s", body)
	}
	var e event
	_ = json.Unmarshal(body, &e)
	want := payload{Action: "WAF_BLOCK", TargetType: "ROUTE", TargetID: "api_taxonomy", Outcome: "DENIED", ReasonCode: "SQL_INJECTION"}
	if e.Payload != want || e.Producer != "waf" || e.EventType != "security.audit.waf.v1" || e.Sequence != 1 ||
		e.CorrelationID != "2ef5522f57c4fe9648f2d11c094825dc" || e.AggregateID != "audit:"+id || e.EventID != id ||
		e.OccurredAtUTC != "2026-11-23T15:19:37Z" {
		t.Fatalf("unexpected event: %s", body)
	}
	if !strings.Contains(string(body), `"actorId":null`) {
		t.Fatal("actor must be explicitly unknown")
	}
	// Nothing identifying or attacker-controlled beyond the normalized route leaves the WAF.
	for _, leak := range []string{"192.0.2.10", "OR 1=1", "%27", "Matched", "localhost", "942100"} {
		if strings.Contains(string(body), leak) {
			t.Fatalf("event leaks %q: %s", leak, body)
		}
	}
	again, sameID, _, _ := Convert([]byte(blocked))
	if sameID != id || string(again) != string(body) {
		t.Fatal("a retried record must produce the identical event")
	}
}

func TestOnlyBlocksArePublished(t *testing.T) {
	for name, record := range map[string]string{
		"detection below threshold": strings.Replace(blocked, `"is_interrupted":true`, `"is_interrupted":false`, 1),
		"upstream error":            strings.Replace(strings.Replace(blocked, `"is_interrupted":true`, `"is_interrupted":false`, 1), `"http_code":403`, `"http_code":502`, 1),
	} {
		if _, _, publish, err := Convert([]byte(record)); err != nil || publish {
			t.Fatal(name, err, publish)
		}
	}
	for name, record := range map[string]string{
		"not JSON":          "{",
		"no transaction":    `{"other":{}}`,
		"forged request id": strings.Replace(blocked, "2ef5522f57c4fe9648f2d11c094825dc", "x\nforged", 1),
		"bad time":          strings.Replace(blocked, "Mon Nov 23 15:19:37 2026", "yesterday", 1),
	} {
		if _, _, _, err := Convert([]byte(record)); err == nil {
			t.Fatal(name + " accepted")
		}
	}
}

func TestCategoryAndTarget(t *testing.T) {
	for ids, want := range map[string]string{
		"941100,949110": "XSS", "930120,949110": "PATH_TRAVERSAL", "949110,932160": "COMMAND_INJECTION",
		"949110": "ANOMALY_THRESHOLD", "980170,959100,951120": "SQL_ERROR_LEAKAGE",
	} {
		if got := category(strings.Split(ids, ",")); got != want {
			t.Errorf("%s: %s != %s", ids, got, want)
		}
	}
	for uri, want := range map[string]string{
		"/": "root", "": "root", "/api/taxonomy?q=1": "api_taxonomy", "/api/internal/x": "api_internal",
		"/hls/str_1/index.m3u8": "hls_str_1", "/../../etc/passwd": "x_x", "/a%20b/<script>#f": "a_20b_script",
		"/" + strings.Repeat("a", 200): strings.Repeat("a", 128),
	} {
		if got := target(uri); got != want {
			t.Errorf("%q: %q != %q", uri, got, want)
		}
	}
}

type fakeBroker struct {
	ids  []string
	fail bool
}

func (f *fakeBroker) Publish(_ context.Context, id string, _ []byte) error {
	if f.fail {
		return errors.New("broker down")
	}
	f.ids = append(f.ids, id)
	return nil
}

func write(t *testing.T, dir, name, content string, age time.Duration) string {
	t.Helper()
	path := filepath.Join(dir, "20261123", "20261123-1519", name)
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o640); err != nil {
		t.Fatal(err)
	}
	when := time.Now().Add(-age)
	_ = os.Chtimes(path, when, when)
	return path
}

func TestSpoolIsAnOutbox(t *testing.T) {
	dir := t.TempDir()
	r := relay{spool: dir, maxBacklog: 100, now: time.Now}
	ready := write(t, dir, "a", blocked, time.Minute)
	writing := write(t, dir, "b", strings.Replace(blocked, "2ef5", "aaaa", 1), 0)
	ignored := write(t, dir, "c", strings.Replace(blocked, `"is_interrupted":true`, `"is_interrupted":false`, 1), time.Minute)
	partial := write(t, dir, "d", `{"transaction":`, 10*time.Second)
	corrupt := write(t, dir, "e", `{"transaction":`, 2*time.Minute)

	down := &fakeBroker{fail: true}
	if _, err := r.Pass(context.Background(), down); err == nil {
		t.Fatal("broker failure must surface")
	}
	if _, err := os.Stat(ready); err != nil {
		t.Fatal("an unconfirmed block must stay in the spool")
	}

	up := &fakeBroker{}
	n, err := r.Pass(context.Background(), up)
	if err != nil || n != 1 || len(up.ids) != 1 {
		t.Fatal(n, err, up.ids)
	}
	for path, kept := range map[string]bool{ready: false, writing: true, ignored: false, partial: true, corrupt: false} {
		if _, err := os.Stat(path); (err == nil) != kept {
			t.Errorf("%s kept=%v", filepath.Base(path), err == nil)
		}
	}
}

func TestBacklogIsBounded(t *testing.T) {
	dir := t.TempDir()
	r := relay{spool: dir, maxBacklog: 2, now: time.Now}
	for i, name := range []string{"old1", "old2", "new1", "new2"} {
		write(t, dir, name, strings.Replace(blocked, "2ef5", "bbb"+name, 1), time.Duration(10-i)*time.Minute)
	}
	up := &fakeBroker{}
	if n, err := r.Pass(context.Background(), up); err != nil || n != 2 {
		t.Fatal(n, err)
	}
	left, _ := r.files()
	if len(left) != 0 {
		t.Fatal("published records must be removed")
	}
}

func TestRulesListOnlyRuleIDs(t *testing.T) {
	got := Rules([]byte(strings.Replace(blocked, `"ruleId":"949110"`, `"ruleId":"949110\nforged"`, 1)))
	if len(got) != 1 || got[0] != "942100" {
		t.Fatal(got)
	}
	if len(Rules([]byte("{"))) != 0 {
		t.Fatal("invalid record must list nothing")
	}
}
