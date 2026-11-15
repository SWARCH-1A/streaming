package rendition

import (
	"github.com/bluenviron/gortsplib/v5/pkg/description"
	"github.com/bluenviron/gortsplib/v5/pkg/format"
	"testing"
)

func TestNativeGroupsKeepSourceIdentityAndBoundVideoCount(t *testing.T) {
	source := &description.Session{}
	for range 4 {
		source.Medias = append(source.Medias, &description.Media{Type: description.MediaTypeVideo, Formats: []format.Format{&format.H264{}}})
	}
	audio := &description.Media{Type: description.MediaTypeAudio, Formats: []format.Format{&format.MPEG4Audio{}}}
	source.Medias = append(source.Medias, audio)
	groups, _, err := Groups(source, nil)
	if err != nil || len(groups) != 4 {
		t.Fatal("four received videos not grouped", err)
	}
	for i, g := range groups {
		if len(g.Medias) != 2 || g.Medias[0] != source.Medias[i] || g.Medias[1] != audio {
			t.Fatal("source media identities or common audio changed")
		}
	}
	source.Medias = append(source.Medias, source.Medias[0])
	if _, _, err = Groups(source, nil); err == nil {
		t.Fatal("fifth video accepted")
	}
}
