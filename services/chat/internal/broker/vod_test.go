package broker

import (
	"strings"
	"testing"
)

const vodEvent = `{"eventId":"a38a5088-2178-444f-9ebd-a3a1dfbe7d02","eventType":"vod.availability.changed.v1","schemaVersion":1,
"producer":"vod","aggregateId":"vod:vod_fixture","sequence":2,"occurredAtUtc":"2026-10-28T20:00:00Z",
"correlationId":"corr_fixture","payload":{"vodId":"vod_fixture","vodVersion":2,"channelId":"chn_fixture",
"sessionId":"ses_fixture","status":"PUBLISHED","timelineStartMs":0,"durationMs":3600000}}`

func TestDecodeVodAcceptsTheProposedEnvelope(t *testing.T) {
	event, link, fingerprint, err := decodeVod([]byte(vodEvent))
	if err != nil || event.EventID == "" || fingerprint == "" || link.VodID != "vod_fixture" || link.SessionID != "ses_fixture" ||
		link.VodVersion != 2 || link.Status != "PUBLISHED" || link.DurationMs == nil || *link.DurationMs != 3600000 {
		t.Fatalf("%+v %+v %v", event, link, err)
	}
	_, _, again, _ := decodeVod([]byte(strings.ReplaceAll(vodEvent, "\n", " ")))
	if again != fingerprint {
		t.Fatal("el fingerprint no depende del espaciado")
	}
}

func TestDecodeVodRejectsInconsistentEnvelopes(t *testing.T) {
	for name, mutate := range map[string]func(string) string{
		"secuencia distinta de vodVersion": func(s string) string { return strings.Replace(s, `"sequence":2`, `"sequence":3`, 1) },
		"agregado ajeno":                   func(s string) string { return strings.Replace(s, `"vod:vod_fixture"`, `"vod:otro"`, 1) },
		"productor ajeno":                  func(s string) string { return strings.Replace(s, `"producer":"vod"`, `"producer":"core"`, 1) },
		"estado desconocido":               func(s string) string { return strings.Replace(s, `"PUBLISHED"`, `"LIVE"`, 1) },
		"campo extra":                      func(s string) string { return strings.Replace(s, `"durationMs":3600000`, `"durationMs":1,"x":1`, 1) },
		"campo ausente":                    func(s string) string { return strings.Replace(s, `,"durationMs":3600000`, ``, 1) },
		"campo duplicado": func(s string) string {
			return strings.Replace(s, `"status":"PUBLISHED"`, `"status":"PUBLISHED","status":"DELETED"`, 1)
		},
		"timeline negativo": func(s string) string { return strings.Replace(s, `"timelineStartMs":0`, `"timelineStartMs":-1`, 1) },
		"hora sin UTC":      func(s string) string { return strings.Replace(s, `20:00:00Z`, `20:00:00-05:00`, 1) },
	} {
		if _, _, _, err := decodeVod([]byte(mutate(vodEvent))); err == nil {
			t.Errorf("%s debe rechazarse", name)
		}
	}
}
