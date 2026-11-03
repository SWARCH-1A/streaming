package recorder

import (
	"path/filepath"
	"strings"

	"github.com/bluenviron/gortsplib/v5/pkg/description"
	"github.com/bluenviron/mediamtx/internal/rendition"
)

func (r *Recorder) splitNativeRenditions() bool {
	if !nativeDirectory.MatchString(filepath.Dir(r.PathFormat)) || r.selectedDesc != nil {
		return false
	}
	groups, _, err := rendition.Groups(r.Stream.OrigDesc, nil)
	if err != nil || len(groups) == 1 {
		return false
	}
	ids := make([]string, len(groups))
	for i := range groups {
		ids[i] = rendition.ID(i, len(groups))
	}
	for i, group := range groups {
		child := *r // configuration only: Initialize has not started any reader/goroutine.
		child.children = nil
		child.selectedDesc, child.nativeRenditions = group, ids
		child.PathFormat = strings.Replace(r.PathFormat, "/rend_source/", "/"+ids[i]+"/", 1)
		child.Initialize()
		r.children = append(r.children, &child)
	}
	return true
}

func (ri *recorderInstance) recordingMedias() []*description.Media {
	r := ri.parent.(*Recorder)
	if r.selectedDesc != nil {
		return r.selectedDesc.Medias
	}
	return ri.stream.OrigDesc.Medias
}
