package hls

import (
	"bytes"
	"fmt"
	"net/http"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"time"

	"github.com/bluenviron/gohlslib/v2"
	"github.com/bluenviron/mediamtx/internal/logger"
	"github.com/bluenviron/mediamtx/internal/protocols/hls"
	"github.com/bluenviron/mediamtx/internal/rendition"
	"github.com/bluenviron/mediamtx/internal/stream"
)

func (mi *muxerInstance) initializeRenditions(directory string) (err error) {
	originals, outputs, err := rendition.Groups(mi.stream.OrigDesc, mi.stream.OutDescCopy())
	if err != nil {
		return err
	}
	defer func() {
		if err != nil {
			for _, m := range mi.hmuxers {
				m.Close()
			}
		}
	}()
	for i := range originals {
		dir := directory
		if dir != "" && len(originals) > 1 {
			dir = filepath.Join(dir, rendition.ID(i, len(originals)))
		}
		m := &gohlslib.Muxer{
			Variant: gohlslib.MuxerVariant(mi.variant), SegmentCount: mi.segmentCount,
			SegmentMinDuration: time.Duration(mi.segmentDuration), PartMinDuration: time.Duration(mi.partDuration),
			SegmentMaxSize: uint64(mi.segmentMaxSize), Directory: dir,
			OnEncodeError: func(err error) { mi.Log(logger.Warn, "%v", err) },
		}
		reader := &stream.Reader{SkipOutboundBytes: true, Parent: mi}
		if err = hls.FromStream(originals[i], outputs[i], reader, m); err != nil {
			return err
		}
		if err = m.Start(); err != nil {
			return err
		}
		mi.hmuxers, mi.readers = append(mi.hmuxers, m), append(mi.readers, reader)
	}
	mi.hmuxer, mi.reader = mi.hmuxers[0], mi.readers[0]
	for _, reader := range mi.readers {
		mi.stream.AddReader(reader)
	}
	return nil
}

func (mi *muxerInstance) waitRenditions() error {
	cases := []reflect.SelectCase{{Dir: reflect.SelectRecv, Chan: reflect.ValueOf(mi.ctx.Done())}}
	for _, reader := range mi.readers {
		cases = append(cases, reflect.SelectCase{Dir: reflect.SelectRecv, Chan: reflect.ValueOf(reader.Error())})
	}
	chosen, value, ok := reflect.Select(cases)
	if chosen == 0 || !ok {
		return fmt.Errorf("terminated")
	}
	return value.Interface().(error)
}

type playlistResponse struct {
	header http.Header
	status int
	body   bytes.Buffer
}

func (w *playlistResponse) Header() http.Header { return w.header }
func (w *playlistResponse) WriteHeader(code int) {
	if w.status == 0 {
		w.status = code
	}
}
func (w *playlistResponse) Write(data []byte) (int, error) {
	if w.status == 0 {
		w.status = 200
	}
	return w.body.Write(data)
}

// Prefix every URI, including init maps and LL-HLS parts. Flat names preserve the
// public adapter's path boundary and prevent one rendition serving another's bytes.
func prefixPlaylist(body, prefix string) string {
	lines := strings.Split(body, "\n")
	for i, line := range lines {
		if line != "" && !strings.HasPrefix(line, "#") {
			lines[i] = prefix + line
			continue
		}
		lines[i] = strings.ReplaceAll(line, `URI="`, `URI="`+prefix)
	}
	return strings.Join(lines, "\n")
}

func (mi *muxerInstance) serveRenditions(w http.ResponseWriter, req *http.Request) {
	if len(mi.hmuxers) == 1 {
		mi.hmuxer.Handle(w, req)
		return
	}
	file := filepath.Base(req.URL.Path)
	if file == "index.m3u8" {
		var result strings.Builder
		result.WriteString("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
		for i, muxer := range mi.hmuxers {
			response := &playlistResponse{header: http.Header{}}
			clone := req.Clone(req.Context())
			u := *req.URL
			clone.URL = &u
			muxer.Handle(response, clone)
			if response.status != 200 {
				w.WriteHeader(http.StatusServiceUnavailable)
				return
			}
			// Keep the encoder-derived bandwidth, codecs and resolution from gohlslib.
			lines := strings.Split(prefixPlaylist(response.body.String(), fmt.Sprintf("rend_%d__", i)), "\n")
			found := false
			for j, line := range lines {
				if strings.HasPrefix(line, "#EXT-X-STREAM-INF:") && j+1 < len(lines) {
					if found {
						w.WriteHeader(http.StatusServiceUnavailable)
						return
					}
					line = strings.ReplaceAll(line, `AUDIO="audio"`, fmt.Sprintf(`AUDIO="audio_rend_%d"`, i))
					result.WriteString(line + "\n" + lines[j+1] + "\n")
					found = true
				}
				if strings.HasPrefix(line, "#EXT-X-MEDIA:") {
					result.WriteString(strings.ReplaceAll(line, `GROUP-ID="audio"`, fmt.Sprintf(`GROUP-ID="audio_rend_%d"`, i)) + "\n")
				}
			}
			if !found {
				w.WriteHeader(http.StatusServiceUnavailable)
				return
			}
		}
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(result.String()))
		return
	}
	prefix, rest, ok := strings.Cut(file, "__")
	index, err := strconv.Atoi(strings.TrimPrefix(prefix, "rend_"))
	if !ok || err != nil || prefix != fmt.Sprintf("rend_%d", index) || index < 0 || index >= len(mi.hmuxers) || rest == "" {
		w.WriteHeader(404)
		return
	}
	clone := req.Clone(req.Context())
	u := *req.URL
	clone.URL = &u
	u.Path = strings.TrimSuffix(u.Path, file) + rest
	u.RawPath = ""
	// Child playlist references must stay in the same rendition namespace.
	if strings.HasSuffix(rest, ".m3u8") {
		response := &playlistResponse{header: http.Header{}}
		mi.hmuxers[index].Handle(response, clone)
		for k, values := range response.header {
			w.Header()[k] = values
		}
		w.WriteHeader(response.status)
		_, _ = w.Write([]byte(prefixPlaylist(response.body.String(), prefix+"__")))
	} else {
		mi.hmuxers[index].Handle(w, clone)
	}
}
