package vod

import (
	"context"
	"encoding/json"
	"io"
	"math"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// ProbeMedia checks each stored segment against declared dimensions, codec and duration.
// Media remains responsible for segment alignment and encoder keyframes across renditions.
func (s *Service) ProbeMedia(ctx context.Context, r Rendition, seg Segment) error {
	file, e := os.CreateTemp("", "vod-probe-*.mp4")
	if e != nil {
		return e
	}
	defer os.Remove(file.Name())
	defer file.Close()
	refs := []ObjectRef{}
	if seg.Initialization != nil {
		refs = append(refs, *seg.Initialization)
	}
	refs = append(refs, seg.Object)
	for _, ref := range refs {
		body, _, err := s.Objects.Get(ctx, ref, "")
		if err != nil {
			return err
		}
		_, err = io.Copy(file, io.LimitReader(body, ref.ByteLength+1))
		body.Close()
		if err != nil {
			return err
		}
	}
	if e = file.Close(); e != nil {
		return e
	}
	budget, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	command := exec.CommandContext(budget, "ffprobe", "-v", "error", "-protocol_whitelist", "file,crypto,data", "-show_entries", "stream=codec_type,width,height,codec_name:format=duration,start_time", "-of", "json", file.Name())
	raw, e := command.Output()
	if e != nil {
		return Fail(422, "MEDIA_INVALID")
	}
	var result struct {
		Format struct {
			Duration string `json:"duration"`
			Start    string `json:"start_time"`
		} `json:"format"`
		Streams []struct {
			Width  int    `json:"width"`
			Height int    `json:"height"`
			Codec  string `json:"codec_name"`
			Type   string `json:"codec_type"`
		} `json:"streams"`
	}

	if json.Unmarshal(raw, &result) != nil {
		return Fail(422, "MEDIA_INVALID")
	}
	video, audio := 0, 0
	codecs := map[string]string{"avc1": "h264", "avc3": "h264", "hvc1": "hevc", "hev1": "hevc", "av01": "av1", "vp09": "vp9", "mp4a": "aac", "ac-3": "ac3", "ec-3": "eac3", "opus": "opus"}
	for _, stream := range result.Streams {
		switch stream.Type {
		case "video":
			video++
			if stream.Width != r.Width || stream.Height != r.Height {
				return Fail(422, "MEDIA_DIMENSIONS_CONFLICT")
			}
			if codecs[strings.Split(r.VideoCodec, ".")[0]] != stream.Codec {
				return Fail(422, "MEDIA_CODEC_CONFLICT")
			}
		case "audio":
			audio++
			if r.AudioCodec == nil || codecs[strings.Split(*r.AudioCodec, ".")[0]] != stream.Codec {
				return Fail(422, "MEDIA_CODEC_CONFLICT")
			}
		}
	}
	if video != 1 || (r.AudioCodec != nil && audio != 1) || (r.AudioCodec == nil && audio != 0) {
		return Fail(422, "MEDIA_STREAMS_CONFLICT")
	}
	duration, err := strconv.ParseFloat(result.Format.Duration, 64)
	start, _ := strconv.ParseFloat(result.Format.Start, 64)
	if seg.Initialization != nil {
		duration -= start
	}
	if err != nil || math.IsNaN(duration) || math.IsInf(duration, 0) || math.Abs(duration*1000-float64(seg.DurationMS)) > 250 {
		return Fail(422, "MEDIA_DURATION_CONFLICT")
	}
	command = exec.CommandContext(budget, "ffmpeg", "-nostdin", "-v", "error", "-protocol_whitelist", "file,crypto,data", "-i", file.Name(), "-frames:v", "1", "-f", "null", "-")
	if command.Run() != nil {
		return Fail(422, "MEDIA_NOT_DECODABLE")
	}
	return nil
}
