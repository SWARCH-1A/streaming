package audit

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	_ "github.com/jackc/pgx/v5/stdlib"
	"strconv"
	"time"
)

type Store struct{ Writer, Reader *sql.DB }

func Open(ctx context.Context, writer, reader string) (*Store, error) {
	w, err := sql.Open("pgx", writer)
	if err != nil {
		return nil, err
	}
	r, err := sql.Open("pgx", reader)
	if err != nil {
		w.Close()
		return nil, err
	}
	s := &Store{w, r}
	w.SetMaxOpenConns(8)
	r.SetMaxOpenConns(4)
	if err = w.PingContext(ctx); err == nil {
		err = r.PingContext(ctx)
	}
	if err != nil {
		s.Close()
		return nil, err
	}
	return s, nil
}
func (s *Store) Close() { s.Writer.Close(); s.Reader.Close() }
func (s *Store) Append(ctx context.Context, e Event) error {
	body, err := json.Marshal(e)
	if err != nil {
		return err
	}
	tx, err := s.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	// Serialize position assignment with commit: a cursor must never skip an earlier uncommitted insert.
	if _, err = tx.ExecContext(ctx, "SELECT pg_advisory_xact_lock(253601)"); err != nil {
		return err
	}
	// JSONB canonical equality makes retries independent of whitespace and property order.
	var same bool
	err = tx.QueryRowContext(ctx, `INSERT INTO audit.events(event_id,producer,actor_id,action,target_type,target_id,outcome,reason_code,occurred_at_utc,request_id,event)
 VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11) ON CONFLICT(event_id) DO NOTHING RETURNING true`,
		e.EventID, e.Producer, e.Payload.ActorID, e.Payload.Action, e.Payload.TargetType, e.Payload.TargetID, e.Payload.Outcome, e.Payload.ReasonCode, e.OccurredAtUTC, e.CorrelationID, body).Scan(&same)
	if errors.Is(err, sql.ErrNoRows) {
		err = tx.QueryRowContext(ctx, "SELECT event=$2::jsonb FROM audit.events WHERE event_id=$1", e.EventID, body).Scan(&same)
		if err == nil && !same {
			return ErrConflict
		}
	}
	if err != nil {
		return err
	}
	return tx.Commit()
}

type Query struct {
	ActorID, TargetID, Action, Outcome, Producer, RequestID string
	From, To                                                *time.Time
	Cursor                                                  int64
	Limit                                                   int
}
type Entry struct {
	Position      string    `json:"position"`
	ReceivedAtUTC time.Time `json:"receivedAtUtc"`
	Event         Event     `json:"event"`
}
type Page struct {
	Items      []Entry `json:"items"`
	NextCursor *string `json:"nextCursor"`
}

func (s *Store) Query(ctx context.Context, q Query) (Page, error) {
	page := Page{Items: []Entry{}}
	rows, err := s.Reader.QueryContext(ctx, `SELECT position,received_at_utc,event FROM audit.events
 WHERE position>$1 AND ($2='' OR actor_id=$2) AND ($3='' OR target_id=$3)
 AND ($4='' OR action=$4) AND ($5='' OR outcome=$5) AND ($6='' OR producer=$6)
 AND ($7='' OR request_id=$7) AND ($8::timestamptz IS NULL OR occurred_at_utc>=$8)
 AND ($9::timestamptz IS NULL OR occurred_at_utc<$9) ORDER BY position LIMIT $10`,
		q.Cursor, q.ActorID, q.TargetID, q.Action, q.Outcome, q.Producer, q.RequestID, q.From, q.To, q.Limit+1)
	if err != nil {
		return page, err
	}
	defer rows.Close()
	for rows.Next() {
		var entry Entry
		var position int64
		var body []byte
		if err = rows.Scan(&position, &entry.ReceivedAtUTC, &body); err != nil {
			return page, err
		}
		if err = json.Unmarshal(body, &entry.Event); err != nil {
			return page, err
		}
		entry.Position = strconv.FormatInt(position, 10)
		page.Items = append(page.Items, entry)
	}
	if err = rows.Err(); err != nil {
		return page, err
	}
	if len(page.Items) > q.Limit {
		cursor := page.Items[q.Limit-1].Position
		page.NextCursor = &cursor
		page.Items = page.Items[:q.Limit]
	}
	return page, nil
}
func (s *Store) RecordRead(ctx context.Context, actor string) error {
	if !opaque.MatchString(actor) {
		return fmt.Errorf("invalid reader identity")
	}
	_, err := s.Writer.ExecContext(ctx, "INSERT INTO audit.reads(actor_id) VALUES($1)", actor)
	return err
}
