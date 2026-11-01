package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
)

// P2ChannelScreen: Core channel is essential; VOD library and follow state are sections; live is
// the stream projection Core already returns with the channel.
func (g gateway) channelScreen(ctx context.Context, w http.ResponseWriter, r *http.Request) {
	handle := r.PathValue("handle")
	if !channelHandle.MatchString(handle) {
		screenError(w, r, http.StatusNotFound)
		return
	}
	channel, code := g.read(ctx, r, g.core, "/api/channels/by-handle/"+url.PathEscape(handle), true)
	if code != http.StatusOK {
		screenError(w, r, code)
		return
	}
	var c struct {
		Channel struct {
			ID string `json:"channelId"`
		} `json:"channel"`
		Stream json.RawMessage `json:"stream"`
	}
	if json.Unmarshal(channel, &c) != nil || !resourceID.MatchString(c.Channel.ID) {
		screenError(w, r, http.StatusServiceUnavailable)
		return
	}
	var vods, follow json.RawMessage
	var vc, fc int
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		vods, vc = g.read(ctx, r, g.vod, "/api/vods?channelId="+url.QueryEscape(c.Channel.ID), true)
	}()
	go func() {
		defer wg.Done()
		follow, fc = g.read(ctx, r, g.core, "/api/channels/"+url.PathEscape(c.Channel.ID)+"/followers/me", true)
	}()
	wg.Wait()
	result := map[string]any{"channel": channel}
	partial := false
	if vc == http.StatusOK {
		result["vods"] = available(vods)
	} else {
		result["vods"] = failure("VOD_UNAVAILABLE", correlation(r))
		partial = true
	}
	switch {
	case fc == http.StatusOK:
		result["follow"] = available(follow)
	case fc == http.StatusUnauthorized && presentsIdentity(r):
		// A presented but invalid credential is never downgraded to an anonymous visitor (CA-03).
		screenError(w, r, http.StatusUnauthorized)
		return
	case fc == http.StatusUnauthorized:
		result["follow"] = section{Status: "DENIED", Error: map[string]any{"code": "SESSION_REQUIRED", "retryable": false, "requestId": correlation(r)}}
	default:
		result["follow"] = failure("FOLLOW_UNAVAILABLE", correlation(r))
		partial = true
	}
	if len(c.Stream) > 0 && string(c.Stream) != "null" {
		result["live"] = available(c.Stream)
	} else {
		result["live"] = section{Status: "EMPTY"}
	}
	writeScreen(w, r, result, partial)
}

// P2WatchScreen: the public stream and its session are essential; channel and renditions are
// sections. A channel confirmed absent (including suspended owners) hides the whole screen.
func (g gateway) watchScreen(ctx context.Context, w http.ResponseWriter, r *http.Request) {
	streamID := r.PathValue("streamId")
	if !resourceID.MatchString(streamID) {
		screenError(w, r, http.StatusNotFound)
		return
	}
	raw, code := g.read(ctx, r, g.live, "/api/streams/"+url.PathEscape(streamID), false)
	if code != http.StatusOK {
		screenError(w, r, code)
		return
	}
	var stream struct {
		StreamID         string  `json:"streamId"`
		ChannelID        string  `json:"channelId"`
		SessionID        *string `json:"sessionId"`
		StreamGeneration *int64  `json:"streamGeneration"`
		Availability     string  `json:"availability"`
	}
	if json.Unmarshal(raw, &stream) != nil || stream.StreamID != streamID || !resourceID.MatchString(stream.ChannelID) || stream.StreamGeneration == nil {
		screenError(w, r, http.StatusServiceUnavailable)
		return
	}
	sessionID := ""
	if stream.SessionID != nil {
		if !resourceID.MatchString(*stream.SessionID) {
			screenError(w, r, http.StatusServiceUnavailable)
			return
		}
		sessionID = *stream.SessionID
	}
	var session, channel, renditions json.RawMessage
	sessionCode, renditionsCode := http.StatusOK, 0
	var channelCode int
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		channel, channelCode = g.read(ctx, r, g.core, "/api/channels/"+url.PathEscape(stream.ChannelID), true)
	}()
	if sessionID != "" {
		wg.Add(1)
		go func() {
			defer wg.Done()
			session, sessionCode = g.read(ctx, r, g.live, "/api/streams/sessions/"+url.PathEscape(sessionID), false)
		}()
		if stream.Availability == "PLAYABLE" {
			wg.Add(1)
			go func() {
				defer wg.Done()
				renditions, renditionsCode = g.read(ctx, r, g.live, "/api/streams/sessions/"+url.PathEscape(sessionID)+"/renditions", false)
			}()
		}
	}
	wg.Wait()
	if channelCode == http.StatusNotFound || (channelCode == http.StatusUnauthorized && presentsIdentity(r)) {
		screenError(w, r, channelCode)
		return
	}
	result := map[string]any{"stream": raw, "session": nil}
	playable := false
	if sessionID != "" {
		if sessionCode != http.StatusOK {
			// The stream announced a session: never present it as reproducible without that session.
			if sessionCode == http.StatusNotFound {
				sessionCode = http.StatusServiceUnavailable
			}
			screenError(w, r, sessionCode)
			return
		}
		var s struct {
			SessionID        string `json:"sessionId"`
			StreamID         string `json:"streamId"`
			ChannelID        string `json:"channelId"`
			StreamGeneration *int64 `json:"streamGeneration"`
			Availability     string `json:"availability"`
		}
		if json.Unmarshal(session, &s) != nil || s.SessionID != sessionID || s.StreamID != streamID || s.ChannelID != stream.ChannelID ||
			s.StreamGeneration == nil || *s.StreamGeneration != *stream.StreamGeneration {
			screenError(w, r, http.StatusServiceUnavailable)
			return
		}
		result["session"] = session
		playable = s.Availability == "PLAYABLE" && stream.Availability == "PLAYABLE"
	}
	partial := false
	if channelCode == http.StatusOK {
		result["channel"] = available(channel)
	} else {
		result["channel"] = failure("CHANNEL_UNAVAILABLE", correlation(r))
		partial = true
	}
	switch {
	case !playable:
		result["renditions"] = section{Status: "EMPTY"}
	case renditionsCode == http.StatusOK && sameSession(renditions, sessionID):
		result["renditions"] = section{Status: "AVAILABLE", Data: renditions}
	default:
		result["renditions"] = failure("STREAM_RENDITIONS_UNAVAILABLE", correlation(r))
		partial = true
	}
	writeScreen(w, r, result, partial)
}

func sameSession(raw json.RawMessage, sessionID string) bool {
	var value struct {
		SessionID string `json:"sessionId"`
	}
	return json.Unmarshal(raw, &value) == nil && value.SessionID == sessionID
}

// P2VodScreen: VOD and its timeline are essential; the channel is a section.
func (g gateway) vodScreen(ctx context.Context, w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("vodId")
	if !resourceID.MatchString(id) {
		screenError(w, r, http.StatusNotFound)
		return
	}
	var raw, timeline json.RawMessage
	var code, timelineCode int
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); raw, code = g.read(ctx, r, g.vod, "/api/vods/"+id, true) }()
	go func() {
		defer wg.Done()
		timeline, timelineCode = g.read(ctx, r, g.vod, "/api/vods/"+id+"/timeline", true)
	}()
	wg.Wait()
	if code != http.StatusOK {
		screenError(w, r, code)
		return
	}
	if timelineCode != http.StatusOK {
		screenError(w, r, timelineCode)
		return
	}
	var v struct {
		ChannelID string `json:"channelId"`
	}
	if json.Unmarshal(raw, &v) != nil || !resourceID.MatchString(v.ChannelID) {
		screenError(w, r, http.StatusServiceUnavailable)
		return
	}
	channel, c := g.read(ctx, r, g.core, "/api/channels/"+url.PathEscape(v.ChannelID), true)
	result := map[string]any{"vod": raw, "mapping": timeline}
	partial := false
	if c == http.StatusOK {
		result["channel"] = available(channel)
	} else {
		result["channel"] = failure("CHANNEL_UNAVAILABLE", correlation(r))
		partial = true
	}
	writeScreen(w, r, result, partial)
}

// P2ExploreScreen: Discovery and VOD keep distinct cursors; this aggregate carries the VOD page
// plus public live metadata. Client errors on the query are the caller's, not a partial failure.
func (g gateway) exploreScreen(ctx context.Context, w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	vquery := url.Values{}
	for _, key := range []string{"title", "channelId", "categoryId", "tagId", "limit", "cursor"} {
		if q.Has(key) {
			vquery[key] = q[key]
		}
	}
	var raw, live json.RawMessage
	var code, liveCode int
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); raw, code = g.read(ctx, r, g.vod, "/api/vods?"+vquery.Encode(), true) }()
	go func() { defer wg.Done(); live, liveCode = g.discovery(ctx, r) }()
	wg.Wait()
	switch code {
	case http.StatusBadRequest, http.StatusUnauthorized, http.StatusGone, http.StatusUnprocessableEntity:
		screenError(w, r, code)
		return
	}
	result := map[string]any{}
	partial := false
	if code == http.StatusOK {
		result["vods"] = available(raw)
	} else {
		result["vods"] = failure("VOD_UNAVAILABLE", correlation(r))
		partial = true
	}
	if liveCode == http.StatusOK {
		result["discovery"] = available(live)
	} else {
		result["discovery"] = failure("DISCOVERY_UNAVAILABLE", correlation(r))
		partial = true
	}
	writeScreen(w, r, result, partial)
}

// discovery runs the canonical generated LiveStreams query; it never forwards identity.
func (g gateway) discovery(ctx context.Context, r *http.Request) (json.RawMessage, int) {
	rawQueries, e := os.ReadFile(g.queriesFile)
	if e != nil {
		return nil, http.StatusServiceUnavailable
	}
	var queries []struct {
		Name  string `json:"name"`
		Query string `json:"query"`
	}
	if json.Unmarshal(rawQueries, &queries) != nil {
		return nil, http.StatusServiceUnavailable
	}
	query := ""
	for _, q := range queries {
		if q.Name == "LiveStreams" {
			query = q.Query
		}
	}
	if query == "" {
		return nil, http.StatusServiceUnavailable
	}
	variables := map[string]any{"limit": 12}
	for _, field := range []string{"categoryId", "tagId"} {
		if value := r.URL.Query().Get(field); value != "" {
			variables[field] = value
		}
	}
	if value := r.URL.Query().Get("title"); value != "" {
		variables["q"] = value
	}
	payload, _ := json.Marshal(map[string]any{"query": query, "variables": variables})
	request, e := http.NewRequestWithContext(ctx, http.MethodPost, g.core+"/api/discovery/graphql", strings.NewReader(string(payload)))
	if e != nil {
		return nil, http.StatusServiceUnavailable
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Accept", "application/json")
	g.forwarding(r, request)
	res, e := g.client.Do(request)
	if e != nil {
		return nil, transportStatus(ctx, e)
	}
	defer res.Body.Close()
	raw, e := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if e != nil || res.StatusCode != http.StatusOK {
		return nil, http.StatusServiceUnavailable
	}
	var data struct {
		Data   json.RawMessage `json:"data"`
		Errors []any           `json:"errors"`
	}
	if json.Unmarshal(raw, &data) != nil || len(data.Errors) > 0 || len(data.Data) == 0 || string(data.Data) == "null" {
		return nil, http.StatusServiceUnavailable
	}
	return data.Data, http.StatusOK
}
