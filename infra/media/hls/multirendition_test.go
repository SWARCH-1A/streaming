package hls

import (
	"strings"
	"testing"
)

func TestNativePlaylistNamespacesInitAudioAndSegments(t *testing.T) {
	input := "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-PART:DURATION=0.2,URI=\"part.m4s\"\nsegment.mp4?session=opaque\n"
	got := prefixPlaylist(input, "rend_2__")
	for _, name := range []string{`URI="rend_2__init.mp4"`, `URI="rend_2__part.m4s"`, "rend_2__segment.mp4?session=opaque"} {
		if !strings.Contains(got, name) {
			t.Fatal("unscoped media reference", got)
		}
	}
}
