// Publishes actual source-encoded MPEG-TS tracks over one Enhanced RTMPS connection.
// The private target URL arrives on stdin and is never printed or passed in argv.
package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"net/url"
	"os"
	"sort"
	"strings"
	"time"

	"github.com/bluenviron/gortmplib"
	rtmp "github.com/bluenviron/gortmplib/pkg/codecs"
	"github.com/bluenviron/mediacommon/v2/pkg/formats/mpegts"
	ts "github.com/bluenviron/mediacommon/v2/pkg/formats/mpegts/codecs"
)

type event struct {
	track    *gortmplib.Track
	pts, dts int64
	data     [][]byte
	audio    bool
}

func run() error {
	if len(os.Args) != 3 {
		return fmt.Errorf("usage: publisher source.ts ca.crt; target URL on stdin")
	}
	file, err := os.Open(os.Args[1])
	if err != nil {
		return err
	}
	defer file.Close()
	reader := &mpegts.Reader{R: file}
	if err = reader.Initialize(); err != nil {
		return err
	}
	var tracks []*gortmplib.Track
	var events []event
	for _, source := range reader.Tracks() {
		switch codec := source.Codec.(type) {
		case *ts.H264:
			c := &rtmp.H264{}
			track := &gortmplib.Track{Codec: c}
			tracks = append(tracks, track)
			reader.OnDataH264(source, func(pts, dts int64, data [][]byte) error {
				for _, nalu := range data {
					if len(nalu) > 0 {
						switch nalu[0] & 31 {
						case 7:
							c.SPS = nalu
						case 8:
							c.PPS = nalu
						}
					}
				}
				events = append(events, event{track: track, pts: pts, dts: dts, data: data})
				return nil
			})
		case *ts.MPEG4Audio:
			track := &gortmplib.Track{Codec: &rtmp.MPEG4Audio{Config: &codec.Config}}
			tracks = append(tracks, track)
			reader.OnDataMPEG4Audio(source, func(pts int64, aus [][]byte) error {
				for i, au := range aus {
					t := pts + int64(i)*1024*90000/int64(codec.SampleRate)
					events = append(events, event{track: track, pts: t, dts: t, data: [][]byte{au}, audio: true})
				}
				return nil
			})
		default:
			return fmt.Errorf("unsupported publisher fixture codec")
		}
	}
	for {
		err = reader.Read()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
	}
	if len(events) == 0 {
		return fmt.Errorf("empty source")
	}
	sort.SliceStable(events, func(i, j int) bool { return events[i].dts < events[j].dts })
	target, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil {
		return fmt.Errorf("target unavailable")
	}
	u, err := url.Parse(strings.TrimSpace(target))
	if err != nil || u.Scheme != "rtmps" {
		return fmt.Errorf("RTMPS target required")
	}
	pem, err := os.ReadFile(os.Args[2])
	if err != nil {
		return err
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(pem) {
		return fmt.Errorf("CA invalid")
	}
	client := &gortmplib.Client{URL: u, Publish: true, TLSConfig: &tls.Config{RootCAs: roots, MinVersion: tls.VersionTLS12}}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err = client.Initialize(ctx); err != nil {
		return fmt.Errorf("publish connection failed")
	}
	defer client.Close()
	writer := &gortmplib.Writer{Conn: client, Tracks: tracks}
	if err = writer.Initialize(); err != nil {
		return fmt.Errorf("track announcement failed")
	}
	start, first := time.Now(), events[0].dts
	fmt.Printf("Publishing %d received tracks through one RTMPS source\n", len(tracks))
	for _, e := range events {
		dts, pts := time.Duration(e.dts-first)*time.Second/90000, time.Duration(e.pts-first)*time.Second/90000
		if delay := time.Until(start.Add(dts)); delay > 0 {
			time.Sleep(delay)
		}
		_ = client.NetConn().SetWriteDeadline(time.Now().Add(5 * time.Second))
		if e.audio {
			err = writer.WriteMPEG4Audio(e.track, pts, e.data[0])
		} else {
			err = writer.WriteH264(e.track, pts, dts, e.data)
		}
		if err != nil {
			return fmt.Errorf("source write failed")
		}
	}
	return nil
}
func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
