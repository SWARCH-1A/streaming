package recorder

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestNativeJournalRequiresEveryCreatedFileToComplete(t *testing.T) {
	dir := t.TempDir()
	j := &nativeJournal{dir: dir, data: nativeInventory{Version: 1, Segments: []nativeEntry{}}}
	file := filepath.Join(dir, "1793275200-000000.ts")
	if err := os.WriteFile(file, []byte("native recorder fixture"), 0600); err != nil {
		t.Fatal(err)
	}
	j.create(file)
	j.seal()
	if j.data.Failure != "CAPTURE_NATIVE_OBJECT_INCOMPLETE" {
		t.Fatal("uncertified tail accepted")
	}
}
func TestNativeJournalConfirmsBytesBeforeSealing(t *testing.T) {
	dir := t.TempDir()
	j := &nativeJournal{dir: dir, data: nativeInventory{Version: 1, Segments: []nativeEntry{}}}
	file := filepath.Join(dir, "1793275200-500000.ts")
	data := []byte("native recorder fixture")
	if err := os.WriteFile(file, data, 0600); err != nil {
		t.Fatal(err)
	}
	j.create(file)
	j.complete(file, 2*time.Second)
	if j.data.Closed || !j.data.Segments[0].Complete {
		t.Fatal("completion is not generation closure")
	}
	j.seal()
	raw, err := os.ReadFile(filepath.Join(dir, "index.json"))
	if err != nil {
		t.Fatal(err)
	}
	var restored nativeInventory
	if json.Unmarshal(raw, &restored) != nil || !restored.Closed || restored.Failure != "" || restored.Segments[0].Size != int64(len(data)) || len(restored.Segments[0].Hash) != 64 || restored.Segments[0].Started.Nanosecond() != 500000000 {
		t.Fatalf("bad durable inventory: %s", raw)
	}
}
func TestNativeJournalRestartCannotAttestCompleteRecording(t *testing.T) {
	j := &nativeJournal{dir: t.TempDir(), data: nativeInventory{Version: 1, Segments: []nativeEntry{}}}
	j.fail("CAPTURE_RECORDER_RESTARTED")
	j.seal()
	if j.data.Failure != "CAPTURE_RECORDER_RESTARTED" {
		t.Fatal("restart failure lost")
	}
	if nativeDirectory.MatchString("/data/native/live/sk_secret/1/rend_source") {
		t.Fatal("ingest key path allowed")
	}
}
