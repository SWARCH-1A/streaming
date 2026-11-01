package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// Fixtures follow the contract examples in contracts/p1.json and contracts/p2.json.
const (
	channelFixture   = `{"channel":{"channelId":"chn_fixture","ownerUserId":"usr_fixture","description":"","bannerUri":null,"channelVersion":0},"handle":"fixture_01","profile":{"userId":"usr_fixture","displayName":"Fixture","bio":"","avatarUri":null,"updatedAtUtc":"2026-10-08T20:00:00Z","profileVersion":0},"stream":null,"streamStatusFresh":true,"availability":"OFFLINE"}`
	streamFixture    = `{"streamId":"str_fixture","channelId":"chn_fixture","sessionId":"ses_fixture","streamGeneration":1,"title":"Conversación","category":{"id":"cat_00000000000000000000000000000001","name":"Conversación"},"tags":[],"status":"LIVE","availability":"PLAYABLE","statusFresh":true,"metadataVersion":1,"sessionVersion":1,"viewerCount":1,"countVersion":1,"viewerCountObservedAtUtc":"2026-10-08T20:00:00Z"}`
	offlineFixture   = `{"streamId":"str_fixture","channelId":"chn_fixture","sessionId":null,"streamGeneration":1,"title":"Conversación","category":{"id":"cat_00000000000000000000000000000001","name":"Conversación"},"tags":[],"status":"OFFLINE","availability":"OFFLINE","statusFresh":true,"metadataVersion":1,"sessionVersion":null,"viewerCount":null,"countVersion":null,"viewerCountObservedAtUtc":null}`
	sessionFixture   = `{"sessionId":"ses_fixture","streamId":"str_fixture","channelId":"chn_fixture","streamGeneration":1,"status":"LIVE","availability":"PLAYABLE","playbackUrl":"/hls/ses_fixture/index.m3u8","timelinePositionMs":1000,"timelineSampledAtUtc":"2026-10-08T20:00:00Z","metadataVersion":1,"sessionVersion":1,"viewerCount":1,"countVersion":1,"viewerCountObservedAtUtc":"2026-10-08T20:00:00Z"}`
	renditionFixture = `{"sessionId":"ses_fixture","renditions":[{"renditionId":"rend_720","width":1280,"height":720,"bitrateBps":2500000,"videoCodec":"avc1","audioCodec":"mp4a"}],"masterPlaylistUri":"/hls/ses_fixture/master.m3u8"}`
	vodPageEmpty     = `{"items":[],"nextCursor":null}`
	followFixture    = `{"channelId":"chn_fixture","following":true,"followVersion":1,"followedAtUtc":"2026-10-26T18:00:00Z"}`
	vodFixture       = `{"sessionId":"ses_fixture","streamId":"str_fixture","channelId":"chn_fixture","streamGeneration":1,"title":"Clase grabada","description":"","categoryId":"cat_fixture","tagIds":[],"vodId":"vod_fixture","processingState":"READY","publicationState":"PUBLISHED","restriction":"NONE","version":2,"mappingVersion":1,"durationMs":1000,"renditions":[{"renditionId":"rend_720","width":1280,"height":720,"bitrateBps":2500000,"videoCodec":"avc1","audioCodec":"mp4a"}],"playbackPath":"/vod-media/vod_fixture/master.m3u8","createdAtUtc":"2026-10-29T12:00:00Z"}`
	profileFixture   = `{"userId":"usr_fan","displayName":"Fan","bio":"","avatarUri":null,"updatedAtUtc":"2026-10-08T20:00:00Z","profileVersion":0}`
	timelineFixture  = `{"vodId":"vod_fixture","sessionId":"ses_fixture","mappingVersion":1,"durationMs":1000,"gapPolicy":"COMPACT_NEAREST_SEAM","intervals":[{"sourceGeneration":1,"sessionStartMs":0,"mediaStartMs":0,"durationMs":1000}]}`
)

// routeMap answers path -> (status, body); a missing path is a test failure.
type routeMap map[string]func(w http.ResponseWriter, r *http.Request)

func reply(status int, body string) func(http.ResponseWriter, *http.Request) {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}
}

type recorded struct {
	mu       sync.Mutex
	requests []*http.Request
}

func (r *recorded) add(request *http.Request) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.requests = append(r.requests, request.Clone(context.Background()))
}

func (r *recorded) find(path string) *http.Request {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, request := range r.requests {
		if request.URL.Path == path {
			return request
		}
	}
	return nil
}

func upstream(t *testing.T, routes routeMap, log *recorded) *httptest.Server {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if log != nil {
			log.add(r)
		}
		handler, ok := routes[r.Method+" "+r.URL.Path]
		if !ok {
			t.Errorf("unexpected upstream request %s %s", r.Method, r.URL.Path)
			w.WriteHeader(http.StatusTeapot)
			return
		}
		handler(w, r)
	}))
	t.Cleanup(server.Close)
	return server
}

type fixture struct {
	core, vod, live routeMap
	log             recorded
	proxies         string
	budget          time.Duration
}

func newFixture() *fixture {
	return &fixture{
		core: routeMap{
			"GET /api/channels/by-handle/fixture_01":     reply(200, channelFixture),
			"GET /api/channels/chn_fixture":              reply(200, channelFixture),
			"GET /api/profile/me":                        reply(200, profileFixture),
			"GET /api/channels/chn_fixture/followers/me": reply(200, followFixture),
			"POST /api/discovery/graphql":                reply(200, `{"data":{"streams":{"items":[],"nextCursor":null}}}`),
		},
		vod: routeMap{
			"GET /api/vods":                      reply(200, vodPageEmpty),
			"GET /api/vods/vod_fixture":          reply(200, vodFixture),
			"GET /api/vods/vod_fixture/timeline": reply(200, timelineFixture),
		},
		live: routeMap{
			"GET /api/streams/str_fixture":                     reply(200, streamFixture),
			"GET /api/streams/sessions/ses_fixture":            reply(200, sessionFixture),
			"GET /api/streams/sessions/ses_fixture/renditions": reply(200, renditionFixture),
		},
	}
}

func (f *fixture) server(t *testing.T) *httptest.Server {
	t.Helper()
	queries := filepath.Join(t.TempDir(), "graphql-queries.json")
	if e := os.WriteFile(queries, []byte(`[{"name":"LiveStreams","query":"query LiveStreams { streams { items { streamId } nextCursor } }"}]`), 0o600); e != nil {
		t.Fatal(e)
	}
	proxies, e := parseTrustedProxies(f.proxies)
	if e != nil {
		t.Fatal(e)
	}
	g := gateway{
		core:        upstream(t, f.core, &f.log).URL,
		vod:         upstream(t, f.vod, &f.log).URL,
		live:        upstream(t, f.live, &f.log).URL,
		client:      &http.Client{Timeout: 5 * time.Second},
		proxies:     proxies,
		queriesFile: queries,
		budget:      f.budget,
	}
	server := httptest.NewServer(g.routes())
	t.Cleanup(server.Close)
	return server
}

type screenResult struct {
	Meta struct {
		Partial   bool   `json:"partial"`
		RequestID string `json:"requestId"`
	} `json:"meta"`
	Channel    json.RawMessage `json:"channel"`
	Session    json.RawMessage `json:"session"`
	Stream     json.RawMessage `json:"stream"`
	Vods       section         `json:"vods"`
	Live       section         `json:"live"`
	Follow     section         `json:"follow"`
	Renditions section         `json:"renditions"`
	Discovery  section         `json:"discovery"`
	Code       string          `json:"code"`
}

func get(t *testing.T, server *httptest.Server, path string, headers map[string]string) (*http.Response, screenResult, []byte) {
	t.Helper()
	request, _ := http.NewRequest("GET", server.URL+path, nil)
	for key, value := range headers {
		request.Header.Set(key, value)
	}
	response, e := server.Client().Do(request)
	if e != nil {
		t.Fatal(e)
	}
	defer response.Body.Close()
	body, _ := io.ReadAll(response.Body)
	var result screenResult
	_ = json.Unmarshal(body, &result)
	if cache := response.Header.Get("Cache-Control"); cache != "no-store, private" {
		t.Errorf("%s: Cache-Control %q", path, cache)
	}
	saveSample(t, path, response.StatusCode, body)
	return response, result, body
}

// saveSample lets CI validate real gateway output against contracts/p2.json (see README).
func saveSample(t *testing.T, path string, status int, body []byte) {
	dir := os.Getenv("GATEWAY_SCREEN_SAMPLES")
	if dir == "" || status != 200 {
		return
	}
	schema := map[string]string{"channels": "P2ChannelScreen", "watch": "P2WatchScreen", "vods": "P2VodScreen", "explore": "P2ExploreScreen"}
	kind := strings.Split(strings.TrimPrefix(path, "/api/screens/"), "/")[0]
	kind = strings.Split(kind, "?")[0]
	name := schema[kind]
	if name == "" {
		return
	}
	file := filepath.Join(dir, name+"."+strings.NewReplacer("/", "_", "?", "_", "=", "_").Replace(t.Name())+".json")
	if e := os.WriteFile(file, body, 0o600); e != nil {
		t.Fatal(e)
	}
}

func sectionCode(s section) string {
	if m, ok := s.Error.(map[string]any); ok {
		code, _ := m["code"].(string)
		return code
	}
	return ""
}

// CA-01, CA-02: one request composes channel, live, VOD and follow; a VOD outage is a section.
func TestChannelPartialFailureKeepsIdentityAndRequestID(t *testing.T) {
	f := newFixture()
	f.vod["GET /api/vods"] = reply(503, `{}`)
	server := f.server(t)
	response, result, body := get(t, server, "/api/screens/channels/fixture_01", map[string]string{"Cookie": "stream_session=alice; theme=dark", "X-Request-ID": "req_channel"})
	if response.StatusCode != 200 || !result.Meta.Partial || result.Meta.RequestID != "req_channel" || result.Vods.Status != "UNAVAILABLE" ||
		sectionCode(result.Vods) != "VOD_UNAVAILABLE" || result.Live.Status != "EMPTY" || result.Follow.Status != "AVAILABLE" {
		t.Fatal(response.StatusCode, string(body))
	}
	follow := f.log.find("/api/channels/chn_fixture/followers/me")
	if follow == nil || follow.Header.Get("Cookie") != "stream_session=alice" || follow.Header.Get("X-Request-ID") != "req_channel" {
		t.Fatal("identity or correlation not forwarded exactly", follow)
	}
}

func TestChannelEmptyLibraryAndAnonymousFollowAreNotFailures(t *testing.T) {
	f := newFixture()
	f.core["GET /api/channels/chn_fixture/followers/me"] = reply(401, `{"code":"SESSION_REQUIRED"}`)
	_, result, body := get(t, f.server(t), "/api/screens/channels/fixture_01", nil)
	if result.Meta.Partial || result.Vods.Status != "EMPTY" || result.Follow.Status != "DENIED" || sectionCode(result.Follow) != "SESSION_REQUIRED" {
		t.Fatal(string(body))
	}
}

func TestChannelLiveSectionComesFromTheChannelProjection(t *testing.T) {
	f := newFixture()
	f.core["GET /api/channels/by-handle/fixture_01"] = reply(200, strings.Replace(channelFixture, `"stream":null`, `"stream":`+strings.TrimSuffix(streamFixture, "}")+`,"session":null}`, 1))
	_, result, body := get(t, f.server(t), "/api/screens/channels/fixture_01", nil)
	if result.Live.Status != "AVAILABLE" {
		t.Fatal(string(body))
	}
}

func TestChannelSectionsAreReadConcurrently(t *testing.T) {
	f := newFixture()
	arrived := make(chan struct{}, 2)
	barrier := func(body string) func(http.ResponseWriter, *http.Request) {
		return func(w http.ResponseWriter, r *http.Request) {
			arrived <- struct{}{}
			deadline := time.After(time.Second)
			for len(arrived) < 2 {
				select {
				case <-deadline:
					w.WriteHeader(503)
					return
				case <-time.After(5 * time.Millisecond):
				}
			}
			reply(200, body)(w, r)
		}
	}
	f.vod["GET /api/vods"] = barrier(vodPageEmpty)
	f.core["GET /api/channels/chn_fixture/followers/me"] = barrier(followFixture)
	_, result, body := get(t, f.server(t), "/api/screens/channels/fixture_01", nil)
	if result.Meta.Partial {
		t.Fatal("sections were read sequentially", string(body))
	}
}

// CA-02: the essential channel keeps 404 when absent and 503 when it cannot be resolved.
func TestChannelEssentialStatuses(t *testing.T) {
	for upstreamStatus, want := range map[int]int{404: 404, 500: 503, 503: 503} {
		f := newFixture()
		f.core["GET /api/channels/by-handle/fixture_01"] = reply(upstreamStatus, `{}`)
		response, _, _ := get(t, f.server(t), "/api/screens/channels/fixture_01", nil)
		if response.StatusCode != want {
			t.Errorf("upstream %d -> %d, want %d", upstreamStatus, response.StatusCode, want)
		}
	}
	response, _, _ := get(t, newFixture().server(t), "/api/screens/channels/no", nil)
	if response.StatusCode != 404 {
		t.Fatal("invalid handle reached the domain", response.StatusCode)
	}
}

// CA-03: a presented but invalid credential is 401, never an anonymous visitor.
func TestInvalidPresentedIdentityIsNotAnonymous(t *testing.T) {
	for _, header := range []map[string]string{{"Cookie": "stream_session=revoked"}, {"Authorization": "Bearer revoked"}} {
		f := newFixture()
		f.core["GET /api/channels/chn_fixture/followers/me"] = reply(401, `{}`)
		response, _, body := get(t, f.server(t), "/api/screens/channels/fixture_01", header)
		if response.StatusCode != 401 {
			t.Fatal(response.StatusCode, string(body))
		}
	}
}

// CA-06: concurrent SSR requests keep their own identity.
func TestParallelSSRRequestsDoNotShareCredentials(t *testing.T) {
	f := newFixture()
	f.core["GET /api/channels/chn_fixture/followers/me"] = func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(5 * time.Millisecond)
		following := map[bool]string{true: "true", false: "false"}[r.Header.Get("Cookie") == "stream_session=alice"]
		reply(200, `{"channelId":"chn_fixture","following":`+following+`,"followVersion":1,"followedAtUtc":null}`)(w, r)
	}
	server := f.server(t)
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		for _, identity := range []string{"alice", "bob"} {
			wg.Add(1)
			go func(identity string) {
				defer wg.Done()
				_, result, body := get(t, server, "/api/screens/channels/fixture_01", map[string]string{"Cookie": "stream_session=" + identity})
				data, _ := result.Follow.Data.(map[string]any)
				if following, _ := data["following"].(bool); following != (identity == "alice") {
					t.Errorf("shared identity for %s: %s", identity, body)
				}
			}(identity)
		}
	}
	wg.Wait()
}

// P2WatchScreen: stream and session essential; channel and renditions are sections.
func TestWatchScreenComposesStreamSessionChannelAndRenditions(t *testing.T) {
	f := newFixture()
	_, result, body := get(t, f.server(t), "/api/screens/watch/str_fixture", map[string]string{"Cookie": "stream_session=alice"})
	var channel section
	_ = json.Unmarshal(result.Channel, &channel)
	if result.Meta.Partial || string(result.Session) == "null" || channel.Status != "AVAILABLE" || result.Renditions.Status != "AVAILABLE" {
		t.Fatal(string(body))
	}
	for _, path := range []string{"/api/streams/str_fixture", "/api/streams/sessions/ses_fixture", "/api/streams/sessions/ses_fixture/renditions"} {
		if request := f.log.find(path); request == nil || request.Header.Get("Cookie") != "" || request.Header.Get("Authorization") != "" {
			t.Fatal("public Streaming reads must not carry identity", path)
		}
	}
}

// CA-03: Core only introspects Bearer on public routes, so the watch aggregate validates a presented
// cookie through an authenticated read instead of serving a revoked session as a visitor.
func TestWatchScreenRejectsRevokedCookie(t *testing.T) {
	f := newFixture()
	f.core["GET /api/profile/me"] = reply(401, `{"code":"AUTH_REQUIRED"}`)
	response, _, body := get(t, f.server(t), "/api/screens/watch/str_fixture", map[string]string{"Cookie": "stream_session=revoked"})
	if response.StatusCode != 401 {
		t.Fatal(response.StatusCode, string(body))
	}
	if request := f.log.find("/api/profile/me"); request == nil || request.Header.Get("Cookie") != "stream_session=revoked" {
		t.Fatal("the presented cookie was not validated by Core")
	}
}

func TestWatchScreenIdentityCheckOnlyWhenPresented(t *testing.T) {
	f := newFixture()
	if response, _, body := get(t, f.server(t), "/api/screens/watch/str_fixture", nil); response.StatusCode != 200 {
		t.Fatal(response.StatusCode, string(body))
	}
	if f.log.find("/api/profile/me") != nil {
		t.Fatal("anonymous viewers must not trigger an identity read")
	}
	// An identity read that cannot complete keeps the public screen: it carries no per-user data.
	f = newFixture()
	f.core["GET /api/profile/me"] = reply(503, `{}`)
	if response, _, body := get(t, f.server(t), "/api/screens/watch/str_fixture", map[string]string{"Cookie": "stream_session=alice"}); response.StatusCode != 200 {
		t.Fatal(response.StatusCode, string(body))
	}
}

func TestWatchScreenSectionFailuresArePartial(t *testing.T) {
	f := newFixture()
	f.core["GET /api/channels/chn_fixture"] = reply(503, `{}`)
	f.live["GET /api/streams/sessions/ses_fixture/renditions"] = reply(504, `{}`)
	response, result, body := get(t, f.server(t), "/api/screens/watch/str_fixture", nil)
	var channel section
	_ = json.Unmarshal(result.Channel, &channel)
	if response.StatusCode != 200 || !result.Meta.Partial || channel.Status != "UNAVAILABLE" || result.Renditions.Status != "UNAVAILABLE" ||
		sectionCode(result.Renditions) != "STREAM_RENDITIONS_UNAVAILABLE" {
		t.Fatal(string(body))
	}
}

func TestWatchScreenOfflineStreamHasNoSessionOrRenditions(t *testing.T) {
	f := newFixture()
	f.live = routeMap{"GET /api/streams/str_fixture": reply(200, offlineFixture)}
	_, result, body := get(t, f.server(t), "/api/screens/watch/str_fixture", nil)
	if result.Meta.Partial || string(result.Session) != "null" || result.Renditions.Status != "EMPTY" {
		t.Fatal(string(body))
	}
}

func TestWatchScreenEssentialFailures(t *testing.T) {
	cases := map[string]struct {
		mutate func(*fixture)
		want   int
	}{
		"stream absent":     {func(f *fixture) { f.live["GET /api/streams/str_fixture"] = reply(404, `{}`) }, 404},
		"stream down":       {func(f *fixture) { f.live["GET /api/streams/str_fixture"] = reply(500, `{}`) }, 503},
		"suspended channel": {func(f *fixture) { f.core["GET /api/channels/chn_fixture"] = reply(404, `{}`) }, 404},
		"session down":      {func(f *fixture) { f.live["GET /api/streams/sessions/ses_fixture"] = reply(503, `{}`) }, 503},
		"session vanished":  {func(f *fixture) { f.live["GET /api/streams/sessions/ses_fixture"] = reply(404, `{}`) }, 503},
		"generation mismatch": {func(f *fixture) {
			f.live["GET /api/streams/sessions/ses_fixture"] = reply(200, strings.Replace(sessionFixture, `"streamGeneration":1`, `"streamGeneration":2`, 1))
		}, 503},
		"other stream": {func(f *fixture) {
			f.live["GET /api/streams/str_fixture"] = reply(200, strings.Replace(streamFixture, "str_fixture", "str_other", 1))
		}, 503},
		"invalid presented token": {func(f *fixture) { f.core["GET /api/channels/chn_fixture"] = reply(401, `{}`) }, 401},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFixture()
			c.mutate(f)
			response, _, body := get(t, f.server(t), "/api/screens/watch/str_fixture", map[string]string{"Authorization": "Bearer fixture"})
			if response.StatusCode != c.want {
				t.Fatal(response.StatusCode, string(body))
			}
		})
	}
}

func TestVodScreenKeepsVodWhenChannelFails(t *testing.T) {
	f := newFixture()
	f.core["GET /api/channels/chn_fixture"] = reply(503, `{}`)
	_, result, body := get(t, f.server(t), "/api/screens/vods/vod_fixture", nil)
	var channel section
	_ = json.Unmarshal(result.Channel, &channel)
	if !result.Meta.Partial || channel.Status != "UNAVAILABLE" {
		t.Fatal(string(body))
	}
	f = newFixture()
	f.vod["GET /api/vods/vod_fixture"] = reply(404, `{}`)
	if response, _, _ := get(t, f.server(t), "/api/screens/vods/vod_fixture", nil); response.StatusCode != 404 {
		t.Fatal("absent VOD", response.StatusCode)
	}
}

func TestExploreScreen(t *testing.T) {
	f := newFixture()
	f.vod["GET /api/vods"] = func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Has("unknown") || r.URL.Query().Get("title") != "clase" {
			t.Error("explore forwarded an unexpected VOD query", r.URL.RawQuery)
		}
		reply(200, vodPageEmpty)(w, r)
	}
	_, result, body := get(t, f.server(t), "/api/screens/explore?title=clase&unknown=x", nil)
	if result.Meta.Partial || result.Vods.Status != "EMPTY" || result.Discovery.Status != "AVAILABLE" {
		t.Fatal(string(body))
	}
	if discovery := f.log.find("/api/discovery/graphql"); discovery == nil || discovery.Header.Get("Cookie") != "" {
		t.Fatal("discovery must not carry identity")
	}

	f = newFixture()
	f.core["POST /api/discovery/graphql"] = reply(200, `{"errors":[{"message":"boom"}]}`)
	_, result, body = get(t, f.server(t), "/api/screens/explore", nil)
	if !result.Meta.Partial || result.Discovery.Status != "UNAVAILABLE" || result.Vods.Status != "EMPTY" {
		t.Fatal(string(body))
	}

	for upstreamStatus, want := range map[int]int{400: 400, 410: 410, 401: 401} {
		f = newFixture()
		f.vod["GET /api/vods"] = reply(upstreamStatus, `{}`)
		if response, _, _ := get(t, f.server(t), "/api/screens/explore?cursor=x", nil); response.StatusCode != want {
			t.Errorf("explore VOD %d -> %d", upstreamStatus, response.StatusCode)
		}
	}
}

// CA-05: client-supplied private headers never reach a domain and forged forwarding is ignored.
func TestPrivateHeadersAndForgedForwardingDoNotCross(t *testing.T) {
	f := newFixture()
	server := f.server(t)
	get(t, server, "/api/screens/channels/fixture_01", map[string]string{
		"X-Service-Name": "vod", "X-Service-Token": "forged", "X-Session-Credential": "forged",
		"X-Forwarded-For": "203.0.113.9", "Forwarded": "for=203.0.113.9", "X-Request-ID": "bad id!",
	})
	request := f.log.find("/api/channels/by-handle/fixture_01")
	if request == nil {
		t.Fatal("no upstream request")
	}
	for _, name := range []string{"X-Service-Name", "X-Service-Token", "X-Session-Credential", "Forwarded"} {
		if request.Header.Get(name) != "" {
			t.Errorf("%s crossed the gateway", name)
		}
	}
	if request.Header.Get("X-Forwarded-For") != "127.0.0.1" {
		t.Errorf("untrusted peer selected a forwarded address: %q", request.Header.Get("X-Forwarded-For"))
	}
	if id := request.Header.Get("X-Request-ID"); !strings.HasPrefix(id, "req_") {
		t.Errorf("invalid request id was propagated: %q", id)
	}
}

func TestTrustedProxyForwardsTheObservedClient(t *testing.T) {
	f := newFixture()
	f.proxies = "127.0.0.1/32"
	server := f.server(t)
	get(t, server, "/api/screens/channels/fixture_01", map[string]string{"X-Forwarded-For": "203.0.113.7"})
	if got := f.log.find("/api/channels/by-handle/fixture_01").Header.Get("X-Forwarded-For"); got != "203.0.113.7" {
		t.Fatalf("client address %q", got)
	}
	f.log = recorded{}
	get(t, server, "/api/screens/channels/fixture_01", map[string]string{"X-Forwarded-For": "not-an-ip"})
	if got := f.log.find("/api/channels/by-handle/fixture_01").Header.Get("X-Forwarded-For"); got != "127.0.0.1" {
		t.Fatalf("malformed chain selected %q", got)
	}
	if _, e := parseTrustedProxies("10.0.0.0/33"); e == nil {
		t.Fatal("invalid prefix accepted")
	}
}

// CA-05: internal, health and unknown routes are not served as screens.
func TestOnlyPublishedRoutesAreServed(t *testing.T) {
	server := newFixture().server(t)
	for _, path := range []string{"/internal/vod/content-removals", "/api/screens/unknown", "/api/screens/watch/a/b", "/api/screens/watch/a%2Fb"} {
		response, _, _ := get(t, server, path, nil)
		if response.StatusCode != 404 {
			t.Errorf("%s -> %d", path, response.StatusCode)
		}
	}
	response, e := server.Client().Post(server.URL+"/api/screens/explore", "application/json", strings.NewReader("{}"))
	if e != nil {
		t.Fatal(e)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusMethodNotAllowed {
		t.Fatal("POST screen", response.StatusCode)
	}
}

// CA-07: the total budget answers 504 and cancels pending internal reads; a client disconnect too.
func TestBudgetTimeoutCancelsInternalReads(t *testing.T) {
	f := newFixture()
	f.budget = 150 * time.Millisecond
	cancelled := make(chan struct{}, 1)
	f.live["GET /api/streams/str_fixture"] = func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-r.Context().Done():
			cancelled <- struct{}{}
		case <-time.After(3 * time.Second):
		}
	}
	started := time.Now()
	response, result, _ := get(t, f.server(t), "/api/screens/watch/str_fixture", nil)
	if response.StatusCode != 504 || result.Code != "SCREEN_TIMEOUT" || time.Since(started) > 2*time.Second {
		t.Fatal(response.StatusCode, time.Since(started))
	}
	select {
	case <-cancelled:
	case <-time.After(2 * time.Second):
		t.Fatal("internal read was not cancelled")
	}
}

func TestClientDisconnectCancelsInternalReads(t *testing.T) {
	f := newFixture()
	cancelled := make(chan struct{}, 1)
	entered := make(chan struct{})
	f.core["GET /api/channels/by-handle/fixture_01"] = func(w http.ResponseWriter, r *http.Request) {
		close(entered)
		select {
		case <-r.Context().Done():
			cancelled <- struct{}{}
		case <-time.After(3 * time.Second):
		}
	}
	server := f.server(t)
	ctx, cancel := context.WithCancel(context.Background())
	request, _ := http.NewRequestWithContext(ctx, "GET", server.URL+"/api/screens/channels/fixture_01", nil)
	go func() { <-entered; cancel() }()
	if response, e := server.Client().Do(request); e == nil {
		response.Body.Close()
	}
	select {
	case <-cancelled:
	case <-time.After(2 * time.Second):
		t.Fatal("client cancellation did not reach the internal read")
	}
}

func TestOversizedOrInvalidUpstreamBodiesAreUnavailable(t *testing.T) {
	for name, body := range map[string]string{"invalid": `{"channel":`, "oversized": `{"x":"` + strings.Repeat("a", maxUpstreamBody) + `"}`} {
		f := newFixture()
		f.core["GET /api/channels/by-handle/fixture_01"] = reply(200, body)
		if response, _, _ := get(t, f.server(t), "/api/screens/channels/fixture_01", nil); response.StatusCode != 503 {
			t.Errorf("%s -> %d", name, response.StatusCode)
		}
	}
}

func removalServer(t *testing.T, handler http.HandlerFunc) (*httptest.Server, *atomic.Int32) {
	t.Helper()
	var calls atomic.Int32
	vod := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1); handler(w, r) }))
	t.Cleanup(vod.Close)
	t.Setenv("GATEWAY_VOD_INTERNAL_URL", vod.URL)
	t.Setenv("GATEWAY_VOD_TOKEN", "service-fixture")
	t.Setenv("GATEWAY_PUBLIC_ORIGIN", "https://localhost:3445")
	g := gateway{client: &http.Client{Timeout: time.Second}}
	server := httptest.NewServer(g.routes())
	t.Cleanup(server.Close)
	return server, &calls
}

func postRemoval(t *testing.T, server *httptest.Server, body string, headers map[string]string) *http.Response {
	t.Helper()
	request, _ := http.NewRequest("POST", server.URL+"/api/admin/content-removals", strings.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	for key, value := range headers {
		request.Header.Set(key, value)
	}
	response, e := server.Client().Do(request)
	if e != nil {
		t.Fatal(e)
	}
	response.Body.Close()
	return response
}

func TestContentRemovalForwardsOnceWithServiceIdentity(t *testing.T) {
	server, calls := removalServer(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/internal/vod/content-removals" || r.Header.Get("X-Service-Token") != "service-fixture" ||
			r.Header.Get("X-Session-Credential") != "admin" || r.Header.Get("Idempotency-Key") != "00000000-0000-4000-8000-000000000001" {
			t.Error("unexpected internal removal request", r.Header)
		}
		w.WriteHeader(503)
	})
	body := `{"resourceType":"VOD","resourceId":"vod_fixture","expectedVersion":2,"reason":"fixture"}`
	headers := map[string]string{"Authorization": "Bearer admin", "Idempotency-Key": "00000000-0000-4000-8000-000000000001", "X-Service-Token": "forged"}
	if response := postRemoval(t, server, body, headers); response.StatusCode != 503 || calls.Load() != 1 {
		t.Fatal("a failed command must be returned, not retried", response.StatusCode, calls.Load())
	}
}

func TestContentRemovalRejectsBeforeReachingTheDomain(t *testing.T) {
	server, calls := removalServer(t, func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(202) })
	valid := `{"resourceType":"VOD","resourceId":"vod_fixture","expectedVersion":2,"reason":"fixture"}`
	cases := []struct {
		body    string
		headers map[string]string
		want    int
	}{
		{valid, nil, 401},
		{valid, map[string]string{"Cookie": "stream_session=admin"}, 403},
		{valid, map[string]string{"Authorization": "Basic x"}, 401},
		{valid, map[string]string{"Cookie": "stream_session=a", "Authorization": "Bearer b"}, 401},
		{`{"resourceType":"CHANNEL","resourceId":"chn_fixture","expectedVersion":1,"reason":"x"}`, map[string]string{"Authorization": "Bearer admin"}, 422},
		{`{"resourceType":"VOD","resourceId":"vod_fixture","expectedVersion":2,"reason":"x","extra":1}`, map[string]string{"Authorization": "Bearer admin"}, 422},
	}
	for _, c := range cases {
		if response := postRemoval(t, server, c.body, c.headers); response.StatusCode != c.want {
			t.Errorf("%v -> %d, want %d", c.headers, response.StatusCode, c.want)
		}
	}
	if calls.Load() != 0 {
		t.Fatal("rejected commands reached VOD", calls.Load())
	}
	csrf := strings.Repeat("x", 24)
	browser := map[string]string{"Cookie": "stream_session=admin; XSRF-TOKEN=" + csrf, "X-XSRF-TOKEN": csrf, "Origin": "https://localhost:3445"}
	if response := postRemoval(t, server, valid, browser); response.StatusCode != 202 || calls.Load() != 1 {
		t.Fatal("browser command with CSRF", response.StatusCode)
	}
}
