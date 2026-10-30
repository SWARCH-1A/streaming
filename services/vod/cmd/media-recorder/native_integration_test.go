package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"streaming/vod/internal/vod"
	"strings"
	"testing"
	"time"

	"github.com/minio/minio-go/v7"
	amqp "github.com/rabbitmq/amqp091-go"
)

type nativeAuthority struct{}

func (nativeAuthority) Check(context.Context, string, string, string) (vod.Access, error) {
	return vod.Access{OwnerUserID: "usr_fixture", Active: true}, nil
}

// Real RTMP -> pinned MediaMTX -> native disk -> capture restart -> private HTTP
// -> PostgreSQL/MinIO -> confirmed AMQP -> validation/HLS. Identity and Streaming
// snapshots/ACK are test ports here, not evidence of a full deployed Core stack.
func TestNativeEngineRecordingClosesWithReconnectAndRejectsCrashTail(t *testing.T) {
	native := os.Getenv("VOD_TEST_NATIVE_DIR")
	db := os.Getenv("VOD_TEST_NATIVE_DATABASE_URL")
	if native == "" || db == "" {
		t.Skip("requires disposable native MediaMTX fixture from run.py")
	}
	if !strings.Contains(db, "vod_native_test") {
		t.Fatal("refusing non-fixture database")
	}
	ctx := context.Background()
	store, e := vod.Open(ctx, db)
	if e != nil {
		t.Fatal(e)
	}
	defer store.DB.Close()
	bucket := "vod-native-" + vod.ID("")
	objects, e := vod.NewS3(os.Getenv("VOD_TEST_S3_ENDPOINT"), bucket, "vod", "us-east-1", "vodfixture", "fixture-only-vod-password")
	if e != nil {
		t.Fatal(e)
	}
	if e = objects.Client.MakeBucket(ctx, bucket, minio.MakeBucketOptions{}); e != nil {
		t.Fatal(e)
	}
	defer func() {
		for o := range objects.Client.ListObjects(ctx, bucket, minio.ListObjectsOptions{Recursive: true}) {
			_ = objects.Client.RemoveObject(ctx, bucket, o.Key, minio.RemoveObjectOptions{})
		}
		_ = objects.Client.RemoveBucket(ctx, bucket)
	}()
	svc := &vod.Service{Store: store, Objects: objects, Authority: nativeAuthority{}, Secret: []byte(strings.Repeat("fixture-only-secret", 3)), Development: true, Origin: "http://fixture"}
	svc.Probe = svc.ProbeMedia
	server := httptest.NewServer(svc.InternalHandler("fixture-capture", "fixture-chat", "fixture-gateway"))
	defer server.Close()
	streaming := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasSuffix(r.URL.Path, "/acknowledged") || r.Header.Get("Authorization") != "Bearer fixture-streaming" {
			w.WriteHeader(403)
			return
		}
		w.WriteHeader(204)
	}))
	defer streaming.Close()
	brokerURL := os.Getenv("VOD_TEST_BROKER_URL")
	parsed, e := url.Parse(brokerURL)
	if e != nil {
		t.Fatal(e)
	}
	password, _ := parsed.User.Password()
	parsed.User = url.UserPassword("streaming", password)
	r := &recorder{ctx: ctx, dir: t.TempDir(), nativeDir: native, states: map[string]*state{}, running: map[string]bool{}, limit: 8 << 30, destination: server.URL, captureToken: "fixture-capture", streaming: streaming.URL, streamingToken: "fixture-streaming", broker: parsed.String(), client: server.Client()}
	defer r.closePublisher()
	session := "ses_00000000-0000-4000-8000-000000000014"
	readIndex := func(session, generation string) nativeInventory {
		t.Helper()
		raw, e := os.ReadFile(filepath.Join(native, session, generation, "rend_source", "index.json"))
		if e != nil {
			t.Fatal(e)
		}
		var result nativeInventory
		if json.Unmarshal(raw, &result) != nil {
			t.Fatal("invalid native inventory")
		}
		return result
	}
	first, second := readIndex(session, "1"), readIndex(session, "2")
	if !first.Closed || !second.Closed || first.Failure != "" || second.Failure != "" || len(first.Segments) < 3 || len(second.Segments) < 3 {
		t.Fatalf("actual native close missing: %+v %+v", first, second)
	}
	started := first.Segments[0].Started.Add(500 * time.Millisecond)
	start := vod.Start{SessionID: session, StreamID: "str_fixture", ChannelID: "chn_fixture", StreamGeneration: 1, Title: "Native recording", CategoryID: "cat_fixture", TagIDs: []string{}, Autopublish: true, PreferenceVersion: 1}
	ended := time.Now().UTC()
	s := &state{Start: start, Renditions: map[string]vod.Rendition{}, Next: map[string]int64{}, Finalized: map[string]bool{}, Ended: true, EndObservedAt: &ended, FirstSourceGeneration: 1, LastSourceGeneration: 2, CloseID: uuid("close:" + session)}
	r.states[session] = s
	src := source{Start: start, StartedAt: &started, InitialSourceGeneration: 1, SourceGeneration: 2, Status: "ENDED"}
	if e = r.captureNative(src); e != nil {
		t.Fatalf("native capture: %v, failure %s, native inventories %+v %+v", e, s.Failure, first, second)
	}
	// Restart from the fsynced checkpoint before any VOD process receives bytes.
	raw, e := os.ReadFile(filepath.Join(r.dir, session+".json"))
	if e != nil {
		t.Fatal(e)
	}
	var restored state
	if json.Unmarshal(raw, &restored) != nil {
		t.Fatal("checkpoint")
	}
	r.states[session] = &restored
	if e = r.captureNative(src); e != nil {
		t.Fatal(e)
	}
	if len(restored.Segments) != len(first.Segments)+len(second.Segments) {
		t.Fatal("native tail lost or duplicate")
	}
	if e = r.transfer(session); e != nil {
		t.Fatal(e)
	}
	if !restored.Closed || !restored.Acknowledged || restored.Index == nil {
		t.Fatal("final index was not confirmed")
	}
	conn, e := amqp.Dial(brokerURL)
	if e != nil {
		t.Fatal(e)
	}
	defer conn.Close()
	ch, e := conn.Channel()
	if e != nil {
		t.Fatal(e)
	}
	defer ch.Close()
	for _, queue := range []string{"vod.segments", "vod.recording"} {
		for {
			msg, ok, err := ch.Get(queue, false)
			if err != nil {
				t.Fatal(err)
			}
			if !ok {
				break
			}
			var envelope vod.Envelope
			if json.Unmarshal(msg.Body, &envelope) == nil && strings.Contains(string(envelope.Payload), session) {
				if e = svc.Event(ctx, queue, msg.Body); e != nil {
					t.Fatal(e)
				}
			}
			if e = msg.Ack(false); e != nil {
				t.Fatal(e)
			}
		}
	}
	recording, e := store.Session(ctx, session)
	if e != nil {
		t.Fatal(e)
	}
	if recording.Index == nil {
		t.Fatal("closed event not consumed")
	}
	if e = svc.Validate(ctx, recording); e != nil {
		t.Fatal(e)
	}
	recording, e = store.Session(ctx, session)
	if e != nil || !recording.Public() || recording.DurationMS < 9000 || len(recording.Renditions) != 1 {
		t.Fatalf("not a complete actual one-rendition VOD: %+v %v", recording, e)
	}
	timeline, e := store.Timeline(ctx, recording.VodID)
	if e != nil {
		t.Fatal(e)
	}
	if timeline.MapOffset(0) != 500 {
		t.Fatalf("LIVE origin moved: %+v", timeline)
	}
	var seam, sessionGap int64
	for _, interval := range timeline.Intervals {
		if interval.SourceGeneration == 2 {
			seam = interval.MediaStartMS
			sessionGap = interval.SessionStartMS - 1
			break
		}
	}
	if seam <= 0 || timeline.MapOffset(sessionGap) != seam {
		t.Fatal("reconnect gap not compacted")
	}
	asset, e := store.Asset(ctx, recording.VodID, "rend_source/index.m3u8")
	if e != nil {
		t.Fatal(e)
	}
	body, _, e := objects.Get(ctx, asset.Object, "")
	if e != nil {
		t.Fatal(e)
	}
	manifest, _ := io.ReadAll(body)
	body.Close()
	if !strings.Contains(string(manifest), "#EXT-X-ENDLIST") || !strings.Contains(string(manifest), "#EXT-X-DISCONTINUITY") {
		t.Fatalf("native final HLS: %s", manifest)
	}
	crashed := "ses_00000000-0000-4000-8000-000000000015"
	retired := readIndex("ses_00000000-0000-4000-8000-000000000016", "1")
	if !retired.Closed || retired.Failure != "CAPTURE_RETIRED" {
		t.Fatal("retired capture did not stop independently of live")
	}
	retiredSession := "ses_00000000-0000-4000-8000-000000000016"
	r.states[retiredSession] = &state{Start: vod.Start{SessionID: retiredSession}, Closed: true, Retired: true, FirstSourceGeneration: 1, LastSourceGeneration: 1}
	if e = r.transfer(retiredSession); e != nil {
		t.Fatal(e)
	}
	if _, e = os.Stat(filepath.Join(native, retiredSession, "1", "rend_source")); !os.IsNotExist(e) {
		t.Fatal("retired native buffer not cleaned")
	}
	if _, e = os.Stat(filepath.Join(native, retiredSession, "retired")); e != nil {
		t.Fatal("native retirement tombstone lost")
	}
	crashIndex := readIndex(crashed, "1")
	if crashIndex.Closed {
		t.Fatal("SIGKILL incorrectly certified native closure")
	}
	crashStart := start
	crashStart.SessionID = crashed
	crashClock := crashIndex.Segments[0].Started
	past := time.Now().Add(-31 * time.Second)
	broken := &state{Start: crashStart, Renditions: map[string]vod.Rendition{}, Next: map[string]int64{}, Finalized: map[string]bool{}, Ended: true, EndObservedAt: &past, FirstSourceGeneration: 1, LastSourceGeneration: 1, CloseID: uuid("close:" + crashed)}
	r.states[crashed] = broken
	if e = r.captureNative(source{Start: crashStart, StartedAt: &crashClock, InitialSourceGeneration: 1, SourceGeneration: 1, Status: "ENDED"}); e != nil {
		t.Fatal(e)
	}
	if e = r.transfer(crashed); e != nil {
		t.Fatal(e)
	}
	if broken.Failure != "CAPTURE_FINAL_INDEX_UNAVAILABLE" || broken.Index != nil {
		t.Fatal("uncertified crash tail published")
	}
	if e = r.transfer(crashed); e != nil {
		t.Fatal(e)
	}
	failure, e := store.Session(ctx, crashed)
	if e != nil || failure.Processing != "FAILED" || failure.Public() {
		t.Fatalf("crash state: %+v %v", failure, e)
	}
	t.Logf("Actual RTMP: %d native files, %d ms, one rendition, reconnect seam %d ms; capture retirement keeps publisher running; SIGKILL tail stays FAILED", len(restored.Segments), recording.DurationMS, seam)
}
