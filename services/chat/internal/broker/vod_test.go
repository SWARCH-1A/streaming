package broker

import (
	"strings"
	"testing"
)

// vodEvent tiene la forma que publica services/vod (store.availability): IDs hex con prefijo y hora
// RFC 3339 con nanosegundos.
const vodEvent = `{"eventId":"a38a5088-2178-444f-9ebd-a3a1dfbe7d02","eventType":"vod.availability.changed.v1","schemaVersion":1,
"producer":"vod","aggregateId":"vod:vod_0123456789abcdef0123456789abcdef0123","sequence":2,"occurredAtUtc":"2026-10-28T20:00:00.123456789Z",
"correlationId":"req_0123456789abcdef0123456789abcdef0123","payload":{"vodId":"vod_0123456789abcdef0123456789abcdef0123",
"sessionId":"ses_fixture","channelId":"chn_fixture","version":2,"mappingVersion":1,"available":true,"restriction":"NONE",
"publicationState":"PUBLISHED"}}`

func TestDecodeVodAcceptsTheVodEnvelope(t *testing.T) {
	event, link, fingerprint, err := decodeVod([]byte(vodEvent))
	if err != nil || event.EventID == "" || fingerprint == "" || link.VodID != "vod_0123456789abcdef0123456789abcdef0123" ||
		link.SessionID != "ses_fixture" || link.ChannelID != "chn_fixture" || link.Version != 2 || link.MappingVersion != 1 ||
		!link.Available || link.Restriction != "NONE" || link.PublicationState != "PUBLISHED" || link.Deleted() {
		t.Fatalf("%+v %+v %v", event, link, err)
	}
	_, _, again, _ := decodeVod([]byte(strings.ReplaceAll(vodEvent, "\n", " ")))
	if again != fingerprint {
		t.Fatal("el fingerprint no depende del espaciado")
	}
	deleted := strings.NewReplacer(`"available":true`, `"available":false`, `"restriction":"NONE"`, `"restriction":"DELETED"`).Replace(vodEvent)
	if _, link, _, err := decodeVod([]byte(deleted)); err != nil || !link.Deleted() || link.Available {
		t.Fatalf("eliminación: %+v %v", link, err)
	}
	draft := strings.NewReplacer(`"available":true`, `"available":false`, `"mappingVersion":1`, `"mappingVersion":0`,
		`"publicationState":"PUBLISHED"`, `"publicationState":"DRAFT"`).Replace(vodEvent)
	if _, link, _, err := decodeVod([]byte(draft)); err != nil || link.MappingVersion != 0 || link.PublicationState != "DRAFT" {
		t.Fatalf("borrador sin timeline: %+v %v", link, err)
	}
}

func TestDecodeVodRejectsInconsistentEnvelopes(t *testing.T) {
	for name, mutate := range map[string]func(string) string{
		"secuencia distinta de version": func(s string) string { return strings.Replace(s, `"sequence":2`, `"sequence":3`, 1) },
		"agregado ajeno": func(s string) string {
			return strings.Replace(s, `"aggregateId":"vod:vod_`, `"aggregateId":"vod:otro_`, 1)
		},
		"productor ajeno":         func(s string) string { return strings.Replace(s, `"producer":"vod"`, `"producer":"core"`, 1) },
		"restricción desconocida": func(s string) string { return strings.Replace(s, `"restriction":"NONE"`, `"restriction":"HIDDEN"`, 1) },
		"publicación desconocida": func(s string) string { return strings.Replace(s, `"PUBLISHED"`, `"LIVE"`, 1) },
		"disponible retirado": func(s string) string {
			return strings.Replace(s, `"restriction":"NONE"`, `"restriction":"RETIRED"`, 1)
		},
		"disponible en borrador": func(s string) string { return strings.Replace(s, `"PUBLISHED"`, `"DRAFT"`, 1) },
		"campo extra":            func(s string) string { return strings.Replace(s, `"available":true`, `"available":true,"x":1`, 1) },
		"campo ausente":          func(s string) string { return strings.Replace(s, `"mappingVersion":1,`, ``, 1) },
		"campo duplicado": func(s string) string {
			return strings.Replace(s, `"restriction":"NONE"`, `"restriction":"NONE","restriction":"DELETED"`, 1)
		},
		"payload anterior":      func(s string) string { return strings.Replace(s, `"version":2`, `"vodVersion":2`, 1) },
		"mapping negativo":      func(s string) string { return strings.Replace(s, `"mappingVersion":1`, `"mappingVersion":-1`, 1) },
		"available no booleano": func(s string) string { return strings.Replace(s, `"available":true`, `"available":"true"`, 1) },
		"hora sin UTC":          func(s string) string { return strings.Replace(s, `20:00:00.123456789Z`, `20:00:00-05:00`, 1) },
	} {
		if _, _, _, err := decodeVod([]byte(mutate(vodEvent))); err == nil {
			t.Errorf("%s debe rechazarse", name)
		}
	}
}
