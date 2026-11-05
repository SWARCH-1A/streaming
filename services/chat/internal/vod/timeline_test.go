package vod

import "testing"

// Dos generaciones con un corte de reconexión: la sesión [0, 10000) y [15000, 20000) se ve en
// [2000, 12000) y [12000, 17000) del video, después de 2000 ms de prefijo PREPARING.
var fixture = Timeline{VodID: "vod_1", SessionID: "ses_1", MappingVersion: 3, DurationMs: 17000, GapPolicy: "COMPACT_NEAREST_SEAM",
	Intervals: []Interval{
		{SourceGeneration: 1, SessionStartMs: 0, MediaStartMs: 2000, DurationMs: 10000},
		{SourceGeneration: 2, SessionStartMs: 15000, MediaStartMs: 12000, DurationMs: 5000},
	}}

func TestMediaOffsetCompactsGapsToTheNextSeam(t *testing.T) {
	for session, media := range map[int64]int64{
		0:     2000,  // el cero de Chat es el instante LIVE, después del prefijo
		9999:  11999, // último milisegundo de la primera generación
		10000: 12000, // durante el corte: unión siguiente
		14999: 12000,
		15000: 12000,
		16500: 13500,
		20000: 16999, // después del último tramo: último milisegundo reproducible
		99999: 16999,
	} {
		if got := fixture.MediaOffset(session); got != media {
			t.Errorf("sesión %d: %d, se esperaba %d", session, got, media)
		}
	}
}

func TestSessionThresholdIsTheInverseOfMediaOffset(t *testing.T) {
	for media, session := range map[int64]int64{
		0:     0, // el prefijo PREPARING no tiene mensajes; la ventana empieza en la sesión 0
		2000:  0,
		5000:  3000,
		11999: 9999,
		12000: 10000, // los mensajes del corte pertenecen a la unión
		12001: 15001,
		16999: 19999, // último milisegundo: incluye la cola posterior al último tramo
	} {
		got, ok := fixture.SessionThreshold(media)
		if !ok || got != session {
			t.Errorf("video %d: %d %v, se esperaba %d", media, got, ok, session)
		}
	}
	if _, ok := fixture.SessionThreshold(17000); ok {
		t.Error("una posición fuera del video no tiene tramo de sesión")
	}
	// Para cada posición de sesión, MediaOffset(s) >= m equivale a s >= SessionThreshold(m).
	for m := int64(0); m <= 17000; m += 250 {
		threshold, ok := fixture.SessionThreshold(m)
		for s := int64(0); s <= 25000; s += 125 {
			inside := fixture.MediaOffset(s) >= m
			if inside != (ok && s >= threshold) {
				t.Fatalf("video %d, sesión %d: MediaOffset=%d umbral=%d/%v", m, s, fixture.MediaOffset(s), threshold, ok)
			}
		}
	}
}

func TestTimelineValidation(t *testing.T) {
	if !fixture.valid("vod_1") {
		t.Fatal("el fixture es válido")
	}
	for name, mutate := range map[string]func(*Timeline){
		"otro VOD":           func(tl *Timeline) { tl.VodID = "vod_2" },
		"sin tramos":         func(tl *Timeline) { tl.Intervals = nil },
		"sin mapping":        func(tl *Timeline) { tl.MappingVersion = 0 },
		"otra política":      func(tl *Timeline) { tl.GapPolicy = "KEEP_GAPS" },
		"duración distinta":  func(tl *Timeline) { tl.DurationMs = 18000 },
		"hueco de video":     func(tl *Timeline) { tl.Intervals[1].MediaStartMs = 12500; tl.DurationMs = 17500 },
		"sesión hacia atrás": func(tl *Timeline) { tl.Intervals[1].SessionStartMs = 5000 },
		"prefijo excesivo": func(tl *Timeline) {
			tl.Intervals[0].MediaStartMs, tl.Intervals[1].MediaStartMs, tl.DurationMs = 130000, 140000, 145000
		},
	} {
		tl := fixture
		tl.Intervals = append([]Interval(nil), fixture.Intervals...)
		mutate(&tl)
		if tl.valid("vod_1") {
			t.Errorf("%s debe rechazarse", name)
		}
	}
}
