// Package vod consume el único contrato privado que Chat necesita de VOD: p2.vod.replay-context,
// que autoriza la lectura del VOD y devuelve su timeline compactado (P2Timeline, ADR-022).
package vod

// Interval es un tramo reproducible: [SessionStartMs, SessionStartMs+DurationMs) del timeline de la
// sesión se ve en [MediaStartMs, MediaStartMs+DurationMs) del video.
type Interval struct {
	SourceGeneration int64 `json:"sourceGeneration"`
	SessionStartMs   int64 `json:"sessionStartMs"`
	MediaStartMs     int64 `json:"mediaStartMs"`
	DurationMs       int64 `json:"durationMs"`
}

// Timeline es P2Timeline con gapPolicy COMPACT_NEAREST_SEAM.
type Timeline struct {
	VodID          string     `json:"vodId"`
	SessionID      string     `json:"sessionId"`
	MappingVersion int64      `json:"mappingVersion"`
	DurationMs     int64      `json:"durationMs"`
	GapPolicy      string     `json:"gapPolicy"`
	Intervals      []Interval `json:"intervals"`
}

// valid comprueba las mismas reglas de cobertura que contracts/check_p2.py: tramos ordenados que
// cubren exactamente el video compactado después de un prefijo PREPARING acotado.
func (t Timeline) valid(vodID string) bool {
	if t.VodID != vodID || t.SessionID == "" || t.MappingVersion < 1 || t.DurationMs < 1 ||
		t.GapPolicy != "COMPACT_NEAREST_SEAM" || len(t.Intervals) == 0 {
		return false
	}
	first := t.Intervals[0]
	if first.MediaStartMs < 0 || first.MediaStartMs > 120000 || (first.MediaStartMs > 0 && first.SessionStartMs != 0) {
		return false
	}
	mediaEnd, sessionEnd := first.MediaStartMs, int64(0)
	for _, i := range t.Intervals {
		if i.DurationMs < 1 || i.MediaStartMs != mediaEnd || i.SessionStartMs < sessionEnd {
			return false
		}
		mediaEnd += i.DurationMs
		sessionEnd = i.SessionStartMs + i.DurationMs
	}
	return mediaEnd == t.DurationMs
}

// MediaOffset ubica una posición de la sesión en el video. Un mensaje en un hueco compactado va a la
// unión siguiente, y después del último tramo al último milisegundo reproducible (ADR-022).
func (t Timeline) MediaOffset(sessionMs int64) int64 {
	for _, i := range t.Intervals {
		if sessionMs < i.SessionStartMs {
			return i.MediaStartMs
		}
		if sessionMs < i.SessionStartMs+i.DurationMs {
			return i.MediaStartMs + sessionMs - i.SessionStartMs
		}
	}
	return t.DurationMs - 1
}

// SessionThreshold es la menor posición de la sesión cuyo MediaOffset es >= mediaMs; ok=false si
// ninguna lo alcanza. MediaOffset es no decreciente, así que la ventana [from, to) del video
// corresponde al tramo [SessionThreshold(from), SessionThreshold(to)) de la sesión.
func (t Timeline) SessionThreshold(mediaMs int64) (int64, bool) {
	previousEnd := int64(0)
	for _, i := range t.Intervals {
		if i.MediaStartMs >= mediaMs {
			return previousEnd, true
		}
		if i.MediaStartMs+i.DurationMs > mediaMs {
			return i.SessionStartMs + mediaMs - i.MediaStartMs, true
		}
		previousEnd = i.SessionStartMs + i.DurationMs
	}
	// mediaMs >= DurationMs: incluso la cola posterior al último tramo queda antes.
	return 0, false
}
