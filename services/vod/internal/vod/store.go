package vod

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	_ "github.com/jackc/pgx/v5/stdlib"
	"io"
	"strings"
	"time"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

type Store struct {
	DB          *sql.DB
	objectSlots chan struct{}
}

func Open(ctx context.Context, url string) (*Store, error) {
	db, e := sql.Open("pgx", url)
	if e != nil {
		return nil, e
	}
	db.SetMaxOpenConns(12)
	db.SetConnMaxLifetime(30 * time.Minute)
	s := &Store{DB: db, objectSlots: make(chan struct{}, 4)}
	if e = db.PingContext(ctx); e == nil {
		e = s.migrate(ctx)
	}
	if e != nil {
		db.Close()
		return nil, e
	}
	return s, nil
}
func canonical(raw []byte) []byte {
	var value any
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	if dec.Decode(&value) != nil {
		return raw
	}
	out, err := json.Marshal(value)
	if err != nil {
		return raw
	}
	return out
}
func fingerprint(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }
func objectID(key string) string  { return "obj_" + fingerprint([]byte(key)) }
func scanRecording(row interface{ Scan(...any) error }) (Recording, error) {
	var r Recording
	var data, index []byte
	err := row.Scan(&data, &r.OwnerUserID, &index)
	if errors.Is(err, sql.ErrNoRows) {
		return r, Fail(404, "VOD_NOT_FOUND")
	}
	if err != nil {
		return r, err
	}
	owner := r.OwnerUserID
	if err = json.Unmarshal(data, &r); err != nil {
		return r, err
	}
	r.OwnerUserID = owner
	if len(index) > 0 && string(index) != "null" {
		err = json.Unmarshal(index, &r.Index)
	}
	return r, err
}
func (s *Store) Get(ctx context.Context, id string) (Recording, error) {
	return scanRecording(s.DB.QueryRowContext(ctx, "SELECT data,owner_id,index_ref FROM vod_recordings WHERE id=$1", id))
}
func (s *Store) Session(ctx context.Context, id string) (Recording, error) {
	return scanRecording(s.DB.QueryRowContext(ctx, "SELECT data,owner_id,index_ref FROM vod_recordings WHERE session_id=$1", id))
}
func (s *Store) Active(ctx context.Context, owner string) (bool, error) {
	var active bool
	e := s.DB.QueryRowContext(ctx, "SELECT NOT EXISTS(SELECT 1 FROM vod_accounts WHERE user_id=$1 AND status='SUSPENDED')", owner).Scan(&active)
	return active, e
}
func (s *Store) Start(ctx context.Context, start Start, owner string) (Recording, error) {
	r := Recording{VodID: "vod_" + fingerprint([]byte(start.SessionID))[:32], Start: start, OwnerUserID: owner, Processing: "RECORDING", Publication: "DRAFT", Restriction: "NONE", Version: 1, Renditions: []Rendition{}, CreatedAt: time.Now().UTC()}
	raw, _ := json.Marshal(r)
	snapshot, _ := json.Marshal(start)
	_, e := s.DB.ExecContext(ctx, "INSERT INTO vod_recordings(id,session_id,owner_id,channel_id,data,start_snapshot) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT(session_id) DO NOTHING", r.VodID, r.SessionID, owner, r.ChannelID, raw, snapshot)
	if e != nil {
		return r, e
	}
	existing, e := s.Session(ctx, start.SessionID)
	if e != nil {
		return r, e
	}
	var before []byte
	if e = s.DB.QueryRowContext(ctx, "SELECT start_snapshot FROM vod_recordings WHERE session_id=$1", start.SessionID).Scan(&before); e != nil {
		return r, e
	}
	if string(canonical(before)) != string(canonical(snapshot)) || owner != existing.OwnerUserID {
		return r, Fail(409, "RECORDING_IDENTITY_CONFLICT")
	}
	if existing.Deleted() {
		return r, Fail(410, "VOD_DELETED")
	}
	return existing, nil
}

// A reservation is committed before S3 I/O, so retries use exactly the same opaque object ID.
func (s *Store) Reserve(ctx context.Context, id, key string, ref ObjectRef) (ObjectRef, bool, error) {
	ref.ObjectID = objectID(id + ":" + key)
	raw, _ := json.Marshal(ref)
	tx, e := s.DB.BeginTx(ctx, nil)
	if e != nil {
		return ref, false, e
	}
	defer tx.Rollback()
	r, e := scanRecording(tx.QueryRowContext(ctx, "SELECT data,owner_id,index_ref FROM vod_recordings WHERE id=$1 FOR UPDATE", id))
	if e != nil {
		return ref, false, e
	}
	if r.Deleted() {
		return ref, false, Fail(410, "VOD_DELETED")
	}

	inserted, e := tx.ExecContext(ctx, "INSERT INTO vod_objects(object_id,vod_id,identity,ref) VALUES($1,$2,$3,$4) ON CONFLICT(identity) DO NOTHING", ref.ObjectID, id, id+":"+key, raw)
	if e != nil {
		return ref, false, e
	}
	n, _ := inserted.RowsAffected()
	if n > 0 && r.Processing == "READY" && !strings.HasPrefix(key, "subtitle:") {
		return ref, false, Fail(409, "RECORDING_CLOSED")
	}
	var existing ObjectRef
	var data []byte
	var done bool
	e = tx.QueryRowContext(ctx, "SELECT ref,confirmed FROM vod_objects WHERE identity=$1", id+":"+key).Scan(&data, &done)
	if e != nil {
		return ref, false, e
	}
	if e = json.Unmarshal(data, &existing); e != nil {
		return ref, false, e
	}
	if existing != ref {
		return ref, false, Fail(409, "OBJECT_IDENTITY_CONFLICT")
	}
	return existing, done, tx.Commit()
}
func (s *Store) Confirm(ctx context.Context, id string) error {
	_, e := s.DB.ExecContext(ctx, "UPDATE vod_objects SET confirmed=true,created_at=now() WHERE object_id=$1", id)
	return e
}
func (s *Store) Owns(ctx context.Context, id string, ref ObjectRef) error {
	var raw []byte
	e := s.DB.QueryRowContext(ctx, "SELECT ref FROM vod_objects WHERE object_id=$1 AND vod_id=$2 AND confirmed", ref.ObjectID, id).Scan(&raw)
	if errors.Is(e, sql.ErrNoRows) {
		return Fail(422, "OBJECT_NOT_CONFIRMED")
	}
	if e != nil {
		return e
	}
	var actual ObjectRef
	if json.Unmarshal(raw, &actual) != nil || actual != ref {
		return Fail(409, "OBJECT_REFERENCE_CONFLICT")
	}
	return nil
}
func save(ctx context.Context, tx *sql.Tx, r Recording) error {
	raw, _ := json.Marshal(r)
	index, _ := json.Marshal(r.Index)
	_, e := tx.ExecContext(ctx, "UPDATE vod_recordings SET data=$2,index_ref=$3 WHERE id=$1", r.VodID, raw, index)
	return e
}
func availability(ctx context.Context, tx *sql.Tx, r Recording) error {
	var active bool
	if e := tx.QueryRowContext(ctx, "SELECT NOT EXISTS(SELECT 1 FROM vod_accounts WHERE user_id=$1 AND status='SUSPENDED')", r.OwnerUserID).Scan(&active); e != nil {
		return e
	}
	payload, _ := json.Marshal(map[string]any{"vodId": r.VodID, "sessionId": r.SessionID, "channelId": r.ChannelID, "version": r.Version, "mappingVersion": r.MappingVersion, "available": r.Public() && active, "restriction": r.Restriction, "publicationState": r.Publication})
	e := Envelope{ID("evt_"), "vod.availability.changed.v1", 1, "vod", "vod:" + r.VodID, r.Version, time.Now().UTC(), ID("req_"), payload}
	body, _ := json.Marshal(e)
	_, err := tx.ExecContext(ctx, "INSERT INTO vod_outbox(event_id,body) VALUES($1,$2)", e.EventID, body)
	return err
}
func job(ctx context.Context, tx *sql.Tx, id, kind string) (string, error) {
	jid := ID("op_")
	_, e := tx.ExecContext(ctx, "INSERT INTO vod_jobs(id,vod_id,kind) VALUES($1,$2,$3) ON CONFLICT(vod_id,kind) DO UPDATE SET state=CASE WHEN vod_jobs.state='COMPLETED' THEN vod_jobs.state ELSE 'PENDING' END,next_at=now()", jid, id, kind)
	if e == nil {
		e = tx.QueryRowContext(ctx, "SELECT id FROM vod_jobs WHERE vod_id=$1 AND kind=$2", id, kind).Scan(&jid)
	}
	return jid, e
}

// Command serialization, optimistic versions, audit, outbox and cleanup intention share one commit.
func (s *Store) Previous(ctx context.Context, actor, key, fp string) ([]byte, bool, error) {
	var previous string
	var raw []byte
	e := s.DB.QueryRowContext(ctx, "SELECT fingerprint,response FROM vod_commands WHERE actor=$1 AND key=$2", actor, key).Scan(&previous, &raw)
	if errors.Is(e, sql.ErrNoRows) {
		return nil, false, nil
	}
	if e != nil {
		return nil, false, e
	}
	if fp != previous {
		return nil, false, Fail(409, "IDEMPOTENCY_KEY_REUSED")
	}
	return canonical(raw), true, nil
}
func (s *Store) Command(ctx context.Context, id, actor, key, fp, operation string, change func(*Recording) (any, error)) ([]byte, error) {
	tx, e := s.DB.BeginTx(ctx, nil)
	if e != nil {
		return nil, e
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(ctx, "SELECT pg_advisory_xact_lock(hashtextextended($1,0))", actor+":"+key); e != nil {
		return nil, e
	}
	var previous string
	var response []byte
	e = tx.QueryRowContext(ctx, "SELECT fingerprint,response FROM vod_commands WHERE actor=$1 AND key=$2", actor, key).Scan(&previous, &response)
	if e == nil {
		if previous != fp {
			return nil, Fail(409, "IDEMPOTENCY_KEY_REUSED")
		}
		return canonical(response), nil
	}
	if !errors.Is(e, sql.ErrNoRows) {
		return nil, e
	}
	r, e := scanRecording(tx.QueryRowContext(ctx, "SELECT data,owner_id,index_ref FROM vod_recordings WHERE id=$1 FOR UPDATE", id))
	if e != nil {
		return nil, e
	}
	version := r.Version
	result, e := change(&r)
	if e != nil {
		return nil, e
	}
	if t, ok := result.(trackAction); ok {
		result, e = trackCommand(ctx, tx, t)
		if e != nil {
			return nil, e
		}
	}
	if r.Deleted() || operation == "REMOVE" {
		kind := "DELETE"
		if operation == "REMOVE" {
			kind = "REMOVE"
		}
		jid, err := job(ctx, tx, id, kind)
		if err != nil {
			return nil, err
		}
		if _, err = tx.ExecContext(ctx, "UPDATE vod_jobs SET actor_id=$2 WHERE id=$1", jid, actor); err != nil {
			return nil, err
		}
		result = map[string]any{"operationId": jid, "status": "PENDING", "statusUri": "/api/vods/operations/" + jid}
	}
	if r.Version != version {
		if e = save(ctx, tx, r); e != nil {
			return nil, e
		}
		if e = availability(ctx, tx, r); e != nil {
			return nil, e
		}
	}
	response, e = json.Marshal(result)
	if e != nil {
		return nil, e
	}
	if _, e = tx.ExecContext(ctx, "INSERT INTO vod_commands(actor,key,fingerprint,response) VALUES($1,$2,$3,$4)", actor, key, fp, response); e != nil {
		return nil, e
	}
	if _, e = tx.ExecContext(ctx, "INSERT INTO vod_audit(actor,vod_id,operation,reason) VALUES($1,$2,$3,$4)", actor, id, operation, r.AuditReason); e != nil {
		return nil, e
	}
	return canonical(response), tx.Commit()
}
func (s *Store) List(ctx context.Context, q Query) ([]Recording, error) {
	rows, e := s.DB.QueryContext(ctx, `SELECT r.data,r.owner_id,r.index_ref FROM vod_recordings r WHERE r.id>$1
 AND ($2='' OR r.channel_id=$2) AND ($3='' OR strpos(lower(r.data->>'title'),lower($3))>0)
 AND ($4='' OR r.data->>'categoryId'=$4) AND ($5='' OR (r.data->'tagIds') ? $5)
 AND ($6='' OR r.owner_id=$6) AND r.data->>'restriction'<>'DELETED'
 AND NOT EXISTS(SELECT 1 FROM vod_accounts a WHERE a.user_id=r.owner_id AND a.status='SUSPENDED')
 AND ($6<>'' OR (r.data->>'processingState'='READY' AND r.data->>'publicationState'='PUBLISHED' AND r.data->>'restriction'='NONE'))
 ORDER BY r.id LIMIT $7`, q.After, q.ChannelID, q.Title, q.CategoryID, q.TagID, q.Owner, q.Limit+1)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	result := []Recording{}
	for rows.Next() {
		r, err := scanRecording(rows)
		if err != nil {
			return nil, err
		}
		result = append(result, r)
	}
	return result, rows.Err()
}
func decodePayload(raw []byte, out any) error {
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if e := decoder.Decode(out); e != nil {
		return e
	}
	var tail any
	if decoder.Decode(&tail) != io.EOF {
		return fmt.Errorf("unexpected event data")
	}
	return nil
}
func (s *Store) Apply(ctx context.Context, consumer string, e Envelope, raw []byte) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	fp := fingerprint(raw)
	result, err := tx.ExecContext(ctx, "INSERT INTO vod_inbox(consumer,event_id,fingerprint) VALUES($1,$2,$3) ON CONFLICT DO NOTHING", consumer, e.EventID, fp)
	if err != nil {
		return err
	}
	n, _ := result.RowsAffected()
	if n == 0 {
		var old string
		if err = tx.QueryRowContext(ctx, "SELECT fingerprint FROM vod_inbox WHERE consumer=$1 AND event_id=$2", consumer, e.EventID).Scan(&old); err != nil {
			return err
		}
		if old != fp {
			return Fail(409, "EVENT_IDENTITY_CONFLICT")
		}
		return nil
	}
	switch e.EventType {
	case "account.status.changed.v1":
		var p struct {
			UserID         string `json:"userId"`
			Status         string `json:"status"`
			AccountVersion int64  `json:"accountVersion"`
		}
		if decodePayload(e.Payload, &p) != nil || !ValidID(p.UserID) || (p.Status != "ACTIVE" && p.Status != "SUSPENDED") || p.AccountVersion != e.Sequence || e.AggregateID != "account:"+p.UserID {
			return Fail(422, "EVENT_INVALID")
		}
		updated, er := tx.ExecContext(ctx, "INSERT INTO vod_accounts(user_id,version,status) VALUES($1,$2,$3) ON CONFLICT(user_id) DO UPDATE SET version=excluded.version,status=excluded.status WHERE excluded.version>vod_accounts.version", p.UserID, p.AccountVersion, p.Status)
		if er != nil {
			return er
		}
		changed, _ := updated.RowsAffected()
		if changed > 0 {
			rows, er := tx.QueryContext(ctx, "SELECT data,owner_id,index_ref FROM vod_recordings WHERE owner_id=$1 ORDER BY id FOR UPDATE", p.UserID)
			if er != nil {
				return er
			}
			var recordings []Recording
			for rows.Next() {
				rec, er := scanRecording(rows)
				if er != nil {
					rows.Close()
					return er
				}
				recordings = append(recordings, rec)
			}
			er = rows.Err()
			rows.Close()
			if er != nil {
				return er
			}
			for _, rec := range recordings {
				rec.Version++
				if er = save(ctx, tx, rec); er != nil {
					return er
				}
				if er = availability(ctx, tx, rec); er != nil {
					return er
				}
			}
		}
	case "recording.closed.v1":
		var p struct {
			SessionID        string    `json:"sessionId"`
			StreamGeneration int64     `json:"streamGeneration"`
			Index            ObjectRef `json:"index"`
		}
		if decodePayload(e.Payload, &p) != nil || !p.Index.Valid() || (e.AggregateID != "recording:"+p.SessionID || e.Sequence != p.StreamGeneration) {
			return Fail(422, "EVENT_INVALID")
		}
		r, er := scanRecording(tx.QueryRowContext(ctx, "SELECT data,owner_id,index_ref FROM vod_recordings WHERE session_id=$1 FOR UPDATE", p.SessionID))
		if er != nil {
			return er
		}
		if r.Deleted() {
			break
		}
		if r.StreamGeneration != p.StreamGeneration {
			return Fail(409, "GENERATION_CONFLICT")
		}
		if r.Index != nil && *r.Index != p.Index {
			return Fail(409, "RECORDING_CLOSE_CONFLICT")
		}
		if r.Processing == "READY" {
			break
		}
		r.Index = &p.Index
		r.Processing = "VALIDATING"
		err = save(ctx, tx, r)
		if err == nil {
			_, err = job(ctx, tx, r.VodID, "VALIDATE")
		}
	case "recording.segment.available.v1":
		var p Segment
		if decodePayload(e.Payload, &p) != nil || !p.Object.Valid() || !ValidID(p.SessionID) || !ValidID(p.RenditionID) || p.SourceGeneration < 1 || p.Sequence < 0 || p.DurationMS < 1 || p.DurationMS > 60000 || p.SessionStartMS < -120000 || (p.SessionStartMS < 0 && p.SourceGeneration != 1) || (e.AggregateID != "segment:"+p.Key() || e.Sequence != p.Sequence+1) {
			return Fail(422, "EVENT_INVALID")
		}
		r, er := scanRecording(tx.QueryRowContext(ctx, "SELECT data,owner_id,index_ref FROM vod_recordings WHERE session_id=$1 FOR UPDATE", p.SessionID))
		if er != nil {
			return er
		}
		if r.Deleted() {
			break
		}
		if err = segmentInventory(ctx, tx, r, p); err != nil {
			return err
		}
	case "stream.session.changed.v1": // ENDED deliberately does not publish or infer a complete recording.
	default:
		return Fail(422, "EVENT_TYPE_INVALID")
	}
	if err != nil {
		return err
	}
	return tx.Commit()
}
func (s *Store) Asset(ctx context.Context, id, path string) (Asset, error) {
	a := Asset{Path: path}
	var raw []byte
	e := s.DB.QueryRowContext(ctx, "SELECT ref,mapping_version FROM vod_assets WHERE vod_id=$1 AND path=$2", id, path).Scan(&raw, &a.MappingVersion)
	if errors.Is(e, sql.ErrNoRows) {
		return a, Fail(404, "ASSET_NOT_FOUND")
	}
	if e != nil {
		return a, e
	}
	e = json.Unmarshal(raw, &a.Object)
	return a, e
}
func (s *Store) Timeline(ctx context.Context, id string) (Timeline, error) {
	var t Timeline
	var raw []byte
	e := s.DB.QueryRowContext(ctx, "SELECT data FROM vod_timelines WHERE vod_id=$1", id).Scan(&raw)
	if e != nil {
		return t, e
	}
	e = json.Unmarshal(raw, &t)
	return t, e
}
func (s *Store) Complete(ctx context.Context, r Recording, t Timeline, assets []Asset, segments []Segment) error {
	tx, e := s.DB.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	current, e := scanRecording(tx.QueryRowContext(ctx, "SELECT data,owner_id,index_ref FROM vod_recordings WHERE id=$1 FOR UPDATE", r.VodID))
	if e != nil {
		return e
	}
	if current.Deleted() {
		return Fail(410, "VOD_DELETED")
	}
	if current.Processing == "READY" {
		return nil
	}
	r.Title = current.Title
	r.Description = current.Description
	r.CategoryID = current.CategoryID
	r.TagIDs = current.TagIDs
	r.Restriction = current.Restriction
	r.Version = current.Version + 1
	r.Processing = "READY"
	r.MappingVersion = t.MappingVersion
	r.DurationMS = t.DurationMS
	r.PlaybackPath = "/vod-media/" + r.VodID + "/master.m3u8"
	if r.Autopublish {
		r.Publication = "PUBLISHED"
	}
	if e = checkIndexCoverage(ctx, tx, r, Index{Segments: segments}); e != nil {
		return e
	}
	// The final index is authoritative for recovering notices lost before broker publication.
	for _, seg := range segments {
		if e = segmentInventory(ctx, tx, r, seg); e != nil {
			return e
		}
	}
	for _, a := range assets {
		raw, _ := json.Marshal(a.Object)
		if _, e = tx.ExecContext(ctx, "INSERT INTO vod_assets(vod_id,path,ref,mapping_version) VALUES($1,$2,$3,$4) ON CONFLICT(vod_id,path) DO UPDATE SET ref=excluded.ref,mapping_version=excluded.mapping_version", r.VodID, a.Path, raw, t.MappingVersion); e != nil {
			return e
		}
	}
	raw, _ := json.Marshal(t)
	if _, e = tx.ExecContext(ctx, "INSERT INTO vod_timelines(vod_id,data) VALUES($1,$2) ON CONFLICT(vod_id) DO UPDATE SET data=excluded.data", r.VodID, raw); e != nil {
		return e
	}
	if e = save(ctx, tx, r); e != nil {
		return e
	}
	if e = availability(ctx, tx, r); e != nil {
		return e
	}
	return tx.Commit()
}

func segmentInventory(ctx context.Context, tx *sql.Tx, r Recording, p Segment) error {
	refs := map[string]ObjectRef{"segment:" + strings.TrimPrefix(p.Key(), p.SessionID+":"): p.Object}
	if p.Initialization != nil {
		refs[fmt.Sprintf("init:%s:%d", p.RenditionID, p.SourceGeneration)] = *p.Initialization
	}
	for key, ref := range refs {
		var raw []byte
		e := tx.QueryRowContext(ctx, "SELECT ref FROM vod_objects WHERE vod_id=$1 AND identity=$2 AND confirmed", r.VodID, r.VodID+":"+key).Scan(&raw)
		if errors.Is(e, sql.ErrNoRows) {
			return Fail(503, "OBJECT_NOT_CONFIRMED")
		}
		if e != nil {
			return e
		}
		var saved ObjectRef
		if json.Unmarshal(raw, &saved) != nil || saved != ref {
			return Fail(409, "OBJECT_REFERENCE_CONFLICT")
		}
	}
	raw, _ := json.Marshal(p)
	if _, e := tx.ExecContext(ctx, "INSERT INTO vod_segments(identity,vod_id,data) VALUES($1,$2,$3) ON CONFLICT DO NOTHING", p.Key(), r.VodID, raw); e != nil {
		return e
	}
	var old []byte
	if e := tx.QueryRowContext(ctx, "SELECT data FROM vod_segments WHERE identity=$1", p.Key()).Scan(&old); e != nil {
		return e
	}
	if string(canonical(old)) != string(canonical(raw)) {
		return Fail(409, "SEGMENT_IDENTITY_CONFLICT")
	}
	return nil
}

func (s *Store) RegisterSegment(ctx context.Context, segment Segment) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	r, err := scanRecording(tx.QueryRowContext(ctx, "SELECT data,owner_id,index_ref FROM vod_recordings WHERE session_id=$1 FOR UPDATE", segment.SessionID))
	if err != nil {
		return err
	}
	if r.Deleted() {
		return Fail(410, "VOD_DELETED")
	}
	if err = segmentInventory(ctx, tx, r, segment); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) CheckIndexCoverage(ctx context.Context, r Recording, index Index) error {
	return checkIndexCoverage(ctx, s.DB, r, index)
}
func checkIndexCoverage(ctx context.Context, db interface {
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
}, r Recording, index Index) error {
	expected := map[string]bool{}
	for _, segment := range index.Segments {
		expected[r.VodID+":segment:"+strings.TrimPrefix(segment.Key(), r.SessionID+":")] = true
	}
	rows, err := db.QueryContext(ctx, "SELECT identity,confirmed FROM vod_objects WHERE vod_id=$1 AND starts_with(identity,$2)", r.VodID, r.VodID+":segment:")
	if err != nil {
		return err
	}
	defer rows.Close()
	count := 0
	for rows.Next() {
		var key string
		var confirmed bool
		if err = rows.Scan(&key, &confirmed); err != nil {
			return err
		}
		if !expected[key] || !confirmed {
			return Fail(503, "INDEX_INCOMPLETE")
		}
		count++
	}
	if err = rows.Err(); err != nil {
		return err
	}
	if count != len(index.Segments) {
		return Fail(503, "INDEX_INCOMPLETE")
	}
	return nil
}

type Job struct {
	ID, VodID, Kind, State, Error string
	Attempts                      int
}

func (s *Store) NextJob(ctx context.Context) (Job, error) {
	var j Job
	e := s.DB.QueryRowContext(ctx, `UPDATE vod_jobs SET state='RUNNING',attempts=attempts+1,next_at=now()+interval '2 minutes' WHERE id=(SELECT id FROM vod_jobs WHERE state<>'COMPLETED' AND next_at<=now() ORDER BY next_at FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING id,vod_id,kind,state,error_code,attempts`).Scan(&j.ID, &j.VodID, &j.Kind, &j.State, &j.Error, &j.Attempts)
	return j, e
}
func (s *Store) JobResult(ctx context.Context, j Job, err error) error {
	state, code := "COMPLETED", ""
	if err != nil {
		state = "FAILED"
		code = "DEPENDENCY_UNAVAILABLE"
		var f *Fault
		if errors.As(err, &f) {
			code = f.Code
		}
	}
	_, e := s.DB.ExecContext(ctx, "UPDATE vod_jobs SET state=$2,error_code=$3,next_at=now()+interval '30 seconds' WHERE id=$1", j.ID, state, code)
	if err != nil && j.Kind == "VALIDATE" {
		_, _ = s.DB.ExecContext(ctx, "UPDATE vod_recordings SET data=jsonb_set(jsonb_set(data,'{processingState}','\"FAILED\"'),'{failureCode}',to_jsonb($2::text)) WHERE id=$1 AND data->>'processingState'<>'READY' AND data->>'restriction'<>'DELETED'", j.VodID, code)
	}
	return e
}
func (s *Store) Objects(ctx context.Context, id string) ([]ObjectRef, error) {
	rows, e := s.DB.QueryContext(ctx, "SELECT ref FROM vod_objects WHERE vod_id=$1", id)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	refs := []ObjectRef{}
	for rows.Next() {
		var raw []byte
		var ref ObjectRef
		if e = rows.Scan(&raw); e != nil {
			return nil, e
		}
		if e = json.Unmarshal(raw, &ref); e != nil {
			return nil, e
		}
		refs = append(refs, ref)
	}
	return refs, rows.Err()
}
func (s *Store) Operation(ctx context.Context, id, actor string) (map[string]any, error) {
	var j Job
	var owner, requester string
	e := s.DB.QueryRowContext(ctx, "SELECT j.id,j.vod_id,j.state,j.error_code,r.owner_id,j.actor_id FROM vod_jobs j JOIN vod_recordings r ON r.id=j.vod_id WHERE j.id=$1", id).Scan(&j.ID, &j.VodID, &j.State, &j.Error, &owner, &requester)
	if errors.Is(e, sql.ErrNoRows) {
		return nil, Fail(404, "OPERATION_NOT_FOUND")
	}
	if e != nil {
		return nil, e
	}
	if owner != actor && requester != actor {
		return nil, Fail(403, "VOD_FORBIDDEN")
	}
	return map[string]any{"operationId": id, "status": j.State, "errorCode": j.Error, "replayCleanup": "DELEGATED_TO_CHAT"}, nil
}
func (s *Store) Outbox(ctx context.Context, publish func(Envelope, []byte) error) error {
	rows, e := s.DB.QueryContext(ctx, "SELECT event_id,body FROM vod_outbox WHERE NOT sent ORDER BY event_id LIMIT 50")
	if e != nil {
		return e
	}
	defer rows.Close()
	type message struct {
		id  string
		raw []byte
	}
	var messages []message
	for rows.Next() {
		var m message
		if e = rows.Scan(&m.id, &m.raw); e != nil {
			return e
		}
		messages = append(messages, m)
	}
	if e = rows.Err(); e != nil {
		return e
	}
	for _, m := range messages {
		var ev Envelope
		if e = json.Unmarshal(m.raw, &ev); e != nil {
			return e
		}
		if e = publish(ev, m.raw); e != nil {
			return e
		}
		if _, e = s.DB.ExecContext(ctx, "UPDATE vod_outbox SET sent=true WHERE event_id=$1", m.id); e != nil {
			return e
		}
	}
	return nil
}
func (s *Store) Stats(ctx context.Context) (map[string]int64, error) {
	result := map[string]int64{}
	for key, query := range map[string]string{"jobs_pending": "SELECT count(*) FROM vod_jobs WHERE state<>'COMPLETED'", "outbox_pending": "SELECT count(*) FROM vod_outbox WHERE NOT sent", "objects_unconfirmed": "SELECT count(*) FROM vod_objects WHERE NOT confirmed"} {
		var n int64
		if err := s.DB.QueryRowContext(ctx, query).Scan(&n); err != nil {
			return nil, fmt.Errorf("metrics unavailable")
		}
		result[key] = n
	}
	return result, nil
}

// Also recovers objects left between S3 confirmation and a failed track transaction.
func (s *Store) ScheduleOrphans(ctx context.Context) error {
	_, err := s.DB.ExecContext(ctx, `INSERT INTO vod_jobs(id,vod_id,kind)
 SELECT 'op_'||md5(o.object_id),o.vod_id,'ORPHAN:'||o.object_id FROM vod_objects o
 WHERE o.confirmed AND split_part(o.identity,':',2)='subtitle' AND o.created_at<now()-interval '30 minutes' AND starts_with(o.identity,o.vod_id||':subtitle:')
 AND NOT EXISTS(SELECT 1 FROM vod_assets a WHERE a.vod_id=o.vod_id AND a.ref->>'objectId'=o.object_id)
 ON CONFLICT(vod_id,kind) DO UPDATE SET state='PENDING',next_at=now() WHERE vod_jobs.state='COMPLETED'`)
	return err
}
func (s *Store) CleanOrphan(ctx context.Context, id, objectID string, remove func(ObjectRef) error) error {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var locked string
	if err = tx.QueryRowContext(ctx, "SELECT id FROM vod_recordings WHERE id=$1 FOR UPDATE", id).Scan(&locked); err != nil {
		return err
	}
	var attached bool
	if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM vod_assets WHERE vod_id=$1 AND ref->>'objectId'=$2)", id, objectID).Scan(&attached); err != nil {
		return err
	}
	if attached {
		return nil
	}
	var raw []byte
	if err = tx.QueryRowContext(ctx, "SELECT ref FROM vod_objects WHERE vod_id=$1 AND object_id=$2", id, objectID).Scan(&raw); err != nil {
		return err
	}
	var ref ObjectRef
	if err = json.Unmarshal(raw, &ref); err != nil {
		return err
	}
	if err = remove(ref); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "UPDATE vod_objects SET confirmed=false WHERE object_id=$1", objectID); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) migrate(ctx context.Context) error {
	tx, e := s.DB.BeginTx(ctx, nil)
	if e != nil {
		return e
	}
	defer tx.Rollback()
	if _, e = tx.ExecContext(ctx, "SELECT pg_advisory_xact_lock(hashtextextended('vod-schema',0))"); e != nil {
		return e
	}
	if _, e = tx.ExecContext(ctx, "CREATE TABLE IF NOT EXISTS vod_schema_migrations(version text PRIMARY KEY,checksum text NOT NULL)"); e != nil {
		return e
	}
	files, e := migrationFiles.ReadDir("migrations")
	if e != nil {
		return e
	}
	for _, file := range files {
		raw, e := migrationFiles.ReadFile("migrations/" + file.Name())
		if e != nil {
			return e
		}
		sum := fingerprint(raw)
		var previous string
		e = tx.QueryRowContext(ctx, "SELECT checksum FROM vod_schema_migrations WHERE version=$1", file.Name()).Scan(&previous)
		if e == nil {
			if previous != sum {
				return fmt.Errorf("VOD migration checksum changed")
			}
			continue
		}
		if !errors.Is(e, sql.ErrNoRows) {
			return e
		}
		if _, e = tx.ExecContext(ctx, string(raw)); e != nil {
			return e
		}
		if _, e = tx.ExecContext(ctx, "INSERT INTO vod_schema_migrations(version,checksum) VALUES($1,$2)", file.Name(), sum); e != nil {
			return e
		}
	}
	return tx.Commit()
}

// Serializes object writes against physical cleanup, including crashes between S3 and SQL.
func (s *Store) WithObjectsLock(ctx context.Context, id string, action func() error) error {
	// Leave SQL capacity for the reservation/confirmation while a lock connection is held.
	select {
	case s.objectSlots <- struct{}{}:
		defer func() { <-s.objectSlots }()
	case <-ctx.Done():
		return ctx.Err()
	}
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "SELECT pg_advisory_xact_lock(hashtextextended($1,1))", id); err != nil {
		return err
	}
	if err = action(); err != nil {
		return err
	}
	return tx.Commit()
}
