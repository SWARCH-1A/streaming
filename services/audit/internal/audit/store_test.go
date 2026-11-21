package audit

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestPostgresDurabilityPrivilegesAndPagination(t *testing.T) {
	writer, reader := os.Getenv("AUDIT_TEST_WRITER_URL"), os.Getenv("AUDIT_TEST_READER_URL")
	if writer == "" || reader == "" {
		t.Skip("requires disposable audit database with runtime roles")
	}
	ctx := context.Background()
	s, err := Open(ctx, writer, reader)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	e := example(t)
	if err = s.Append(ctx, e); err != nil {
		t.Fatal(err)
	}
	if err = s.Append(ctx, e); err != nil {
		t.Fatal("retry", err)
	}
	changed := e
	changed.Payload.ReasonCode = "DIFFERENT"
	if err = s.Append(ctx, changed); !errors.Is(err, ErrConflict) {
		t.Fatal("conflict not detected", err)
	}
	var wg sync.WaitGroup
	for i := 2; i <= 12; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			other := e
			other.EventID = fmt.Sprintf("a38a5088-2178-444f-9ebd-a3a1dfbe%04d", i)
			other.AggregateID = "audit:" + other.EventID
			if err := s.Append(ctx, other); err != nil {
				t.Error(err)
			}
		}(i)
	}
	wg.Wait()
	var count int
	cursor := int64(0)
	seen := map[string]bool{}
	for {
		page, err := s.Query(ctx, Query{TargetID: e.Payload.TargetID, Limit: 3, Cursor: cursor})
		if err != nil {
			t.Fatal(err)
		}
		for _, item := range page.Items {
			if seen[item.Event.EventID] {
				t.Fatal("duplicate")
			}
			seen[item.Event.EventID] = true
			count++
		}
		if page.NextCursor == nil {
			break
		}
		fmt.Sscan(*page.NextCursor, &cursor)
	}
	if count != 12 {
		t.Fatal("lost or duplicated events", count)
	}
	for _, db := range []*sql.DB{s.Writer, s.Reader} {
		for _, query := range []string{"DELETE FROM audit.events", "UPDATE audit.events SET action='FORGED'", "TRUNCATE audit.events", "CREATE TABLE audit.forged(id int)", "ALTER TABLE audit.events ADD COLUMN forged int"} {
			if _, err := db.ExecContext(ctx, query); err == nil {
				t.Fatal("runtime privilege bypass", query)
			}
		}
	}
	if _, err = s.Reader.ExecContext(ctx, "INSERT INTO audit.reads(actor_id) VALUES('forged')"); err == nil {
		t.Fatal("reader wrote")
	}
	token := strings.Repeat("s", 32)
	req := httptest.NewRequest("GET", "/internal/audit/events?targetId=vod_fixture&limit=3", nil)
	req.Header.Set("X-Service-Name", "core")
	req.Header.Set("X-Service-Token", token)
	req.Header.Set("X-Audit-Actor", "usr_admin")
	response := httptest.NewRecorder()
	Handler(s, token).ServeHTTP(response, req)
	if response.Code != 200 {
		t.Fatal(response.Code, response.Body.String())
	}
	var page Page
	if json.Unmarshal(response.Body.Bytes(), &page) != nil || len(page.Items) != 3 {
		t.Fatal(response.Body.String())
	}
	if err = s.Reader.QueryRowContext(ctx, "SELECT count(*) FROM audit.reads WHERE actor_id='usr_admin'").Scan(&count); err != nil || count != 1 {
		t.Fatal("read not audited", count, err)
	}
	// A fresh connection still observes committed records.
	s2, err := Open(ctx, writer, reader)
	if err != nil {
		t.Fatal(err)
	}
	defer s2.Close()
	from := time.Date(2026, 11, 20, 0, 0, 0, 0, time.UTC)
	to := from.Add(24 * time.Hour)
	page, err = s2.Query(ctx, Query{From: &from, To: &to, Outcome: "DENIED", Limit: 100})
	if err != nil || len(page.Items) != 12 {
		t.Fatal("reopen", len(page.Items), err)
	}
}
