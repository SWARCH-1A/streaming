// Package rendition selects source-encoded tracks without copying or transcoding media.
package rendition

import (
	"fmt"
	"github.com/bluenviron/gortsplib/v5/pkg/description"
	"github.com/bluenviron/gortsplib/v5/pkg/format"
)

// Groups preserves original Media/Format pointers, required by stream.Reader.
// Every group contains one received H.264 video and the same optional AAC track.
func Groups(orig, out *description.Session) ([]*description.Session, []*description.Session, error) {
	var videos []int
	audio := 0
	for i, m := range orig.Medias {
		if len(m.Formats) != 1 {
			return nil, nil, fmt.Errorf("one format per source track required")
		}
		switch m.Formats[0].(type) {
		case *format.H264:
			videos = append(videos, i)
		case *format.MPEG4Audio:
			audio++
		default:
			return nil, nil, fmt.Errorf("source requires H.264 and optional AAC")
		}
	}
	if len(videos) < 1 || len(videos) > 4 || audio > 1 {
		return nil, nil, fmt.Errorf("source requires one to four videos and at most one audio")
	}
	if out == nil {
		out = orig
	}
	if len(out.Medias) != len(orig.Medias) {
		return nil, nil, fmt.Errorf("source descriptions differ")
	}
	originals, outputs := []*description.Session{}, []*description.Session{}
	for _, selected := range videos {
		a, b := &description.Session{}, &description.Session{}
		for i, m := range orig.Medias {
			if i == selected || m.Type == description.MediaTypeAudio {
				a.Medias = append(a.Medias, m)
				b.Medias = append(b.Medias, out.Medias[i])
			}
		}
		originals, outputs = append(originals, a), append(outputs, b)
	}
	return originals, outputs, nil
}

func ID(index, count int) string {
	if count == 1 {
		return "rend_source"
	}
	return fmt.Sprintf("rend_%d", index)
}
