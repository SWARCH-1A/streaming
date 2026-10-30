package recorder

// This Media-owned inventory contains public session identifiers, never ingest paths.
// It is compiled into the pinned engine by patch_recording.py. A failed inventory
// does not stop the independent live stream and can never attest a complete VOD.
import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var nativeDirectory = regexp.MustCompile(`^/data/native/(ses_[0-9a-f-]{36})/([1-9][0-9]*)/(rend_source)$`)
var nativeFilename = regexp.MustCompile(`^([0-9]+)-([0-9]{6})\.ts$`)

type nativeEntry struct {
	Sequence   int64     `json:"sequence"`
	File       string    `json:"file"`
	Started    time.Time `json:"startedAtUtc"`
	DurationMS int64     `json:"durationMs"`
	Size       int64     `json:"byteLength"`
	Hash       string    `json:"sha256"`
	Complete   bool      `json:"complete"`
}
type nativeInventory struct {
	Version  int           `json:"version"`
	Closed   bool          `json:"closed"`
	Failure  string        `json:"failure"`
	Segments []nativeEntry `json:"segments"`
}
type nativeJournal struct {
	dir    string
	data   nativeInventory
	broken bool
}

func (r *Recorder) initNativeJournal() {
	dir := filepath.Dir(r.PathFormat)
	if !nativeDirectory.MatchString(dir) {
		return
	}
	j := &nativeJournal{dir: dir, data: nativeInventory{Version: 1, Segments: []nativeEntry{}}}
	r.journal = j
	// An engine restart cannot silently replace an existing generation's evidence.
	if _, err := os.Stat(filepath.Join(dir, "index.json")); !errors.Is(err, os.ErrNotExist) {
		j.broken = true
	} else {
		j.save()
	}
	create, complete := r.OnSegmentCreate, r.OnSegmentComplete
	r.OnSegmentCreate = func(path string) { j.create(path); create(path) }
	r.OnSegmentComplete = func(path string, duration time.Duration) { j.complete(path, duration); complete(path, duration) }
}
func (j *nativeJournal) save() {
	if j.broken {
		return
	}
	if os.MkdirAll(j.dir, 0700) != nil {
		j.broken = true
		return
	}
	raw, err := json.Marshal(j.data)
	if err != nil {
		j.broken = true
		return
	}
	tmp, err := os.CreateTemp(j.dir, ".inventory-")
	if err != nil {
		j.broken = true
		return
	}
	defer os.Remove(tmp.Name())
	_, err = tmp.Write(raw)
	if err == nil {
		err = tmp.Sync()
	}
	closed := tmp.Close()
	if err == nil {
		err = closed
	}
	if err == nil {
		err = os.Rename(tmp.Name(), filepath.Join(j.dir, "index.json"))
	}
	if err == nil {
		var dir *os.File
		dir, err = os.Open(j.dir)
		if err == nil {
			err = dir.Sync()
			dir.Close()
		}
	}
	if err != nil {
		j.broken = true
	}
}
func (j *nativeJournal) fail(code string) {
	if j.data.Failure == "" {
		j.data.Failure = code
	}
	j.save()
}
func (j *nativeJournal) create(path string) {
	if j.broken {
		return
	}
	name := filepath.Base(path)
	match := nativeFilename.FindStringSubmatch(name)
	if filepath.Dir(path) != j.dir || match == nil {
		j.fail("CAPTURE_NATIVE_PATH_INVALID")
		return
	}
	seconds, _ := strconv.ParseInt(match[1], 10, 64)
	microseconds, _ := strconv.ParseInt(match[2], 10, 64)
	for _, entry := range j.data.Segments {
		if entry.File == name {
			j.fail("CAPTURE_NATIVE_IDENTITY_CONFLICT")
			return
		}
	}
	j.data.Segments = append(j.data.Segments, nativeEntry{Sequence: int64(len(j.data.Segments)), File: name, Started: time.Unix(seconds, microseconds*1000).UTC()})
	j.save()
}
func (j *nativeJournal) complete(path string, duration time.Duration) {
	if j.broken {
		return
	}
	for i := range j.data.Segments {
		entry := &j.data.Segments[i]
		if filepath.Join(j.dir, entry.File) != path {
			continue
		}
		file, err := os.Open(path)
		if err != nil {
			j.fail("CAPTURE_NATIVE_OBJECT_MISSING")
			return
		}
		err = file.Sync() // persist bytes before a confirmed inventory entry
		h := sha256.New()
		var size int64
		if err == nil {
			size, err = io.Copy(h, io.LimitReader(file, 64<<20+1))
		}
		file.Close()
		if err != nil || size <= 0 || size > 64<<20 || duration <= 0 || duration > 60*time.Second {
			j.fail("CAPTURE_NATIVE_OBJECT_INVALID")
			return
		}
		entry.DurationMS = (duration + 500*time.Microsecond).Milliseconds()
		entry.Size = size
		entry.Hash = hex.EncodeToString(h.Sum(nil))
		entry.Complete = true
		j.save()
		return
	}
	j.fail("CAPTURE_NATIVE_IDENTITY_MISSING")
}
func (j *nativeJournal) seal() {
	if j.broken {
		return
	}
	if len(j.data.Segments) == 0 {
		j.fail("CAPTURE_EMPTY")
	}
	for _, entry := range j.data.Segments {
		if !entry.Complete {
			j.fail("CAPTURE_NATIVE_OBJECT_INCOMPLETE")
		}
	}
	j.data.Closed = true
	j.save()
}

func (j *nativeJournal) retired() bool { return nativeRetired(j.dir) }
func nativeRetired(dir string) bool {
	if !nativeDirectory.MatchString(dir) {
		return false
	}
	info, err := os.Stat(filepath.Join(filepath.Dir(filepath.Dir(dir)), "retired"))
	return err == nil && !info.IsDir()
}

func nativeRecordingWrite(path string, extra int) error {
	if !nativeDirectory.MatchString(filepath.Dir(path)) {
		return nil
	}
	if nativeRetired(filepath.Dir(path)) {
		return fmt.Errorf("native recording retired")
	}
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	if info.Size()+int64(extra) > 64<<20 {
		return fmt.Errorf("native recording file too large")
	}
	return nil
}

// Bound native disk use without blocking or rejecting the live reader. Recording
// errors are retained in the inventory and handled by MediaMTX's own recorder loop.
func nativeRecordingSpace(path string) error {
	if !nativeDirectory.MatchString(filepath.Dir(path)) {
		return nil
	}
	if nativeRetired(filepath.Dir(path)) {
		return fmt.Errorf("native recording retired")
	}
	limit := int64(8 << 30)
	if n, err := strconv.ParseInt(os.Getenv("MEDIA_RECORDING_MAX_BYTES"), 10, 64); err == nil && n > 0 {
		limit = n
	}
	var used int64
	err := filepath.WalkDir("/data/native", func(path string, entry os.DirEntry, err error) error {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		if err != nil {
			return err
		}
		if !entry.IsDir() && strings.HasSuffix(path, ".ts") {
			info, e := entry.Info()
			if errors.Is(e, os.ErrNotExist) {
				return nil
			}
			if e != nil {
				return e
			}
			used += info.Size()
		}
		return nil
	})
	if err != nil {
		return err
	}
	if used >= limit {
		return fmt.Errorf("native recording buffer full")
	}
	return nil
}
