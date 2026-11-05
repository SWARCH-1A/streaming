package vod

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
)

func TestSubtitleProfileSharedCorpus(t *testing.T) {
	raw, err := os.ReadFile("testdata/webvtt-profile.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Name, Text string
		DurationMs int64
		Valid      bool
	}
	if err = json.Unmarshal(raw, &cases); err != nil {
		t.Fatal(err)
	}
	for _, tc := range cases {
		t.Run(tc.Name, func(t *testing.T) {
			if got := ValidateWebVTT([]byte(tc.Text), tc.DurationMs) == nil; got != tc.Valid {
				t.Fatalf("valid=%v, expected=%v", got, tc.Valid)
			}
		})
	}
}

func TestSubtitleProfileLimits(t *testing.T) {
	cue := "00:00.000 --> 00:01.000\nTexto\n\n"
	for _, count := range []int{10000, 10001} {
		if got := ValidateWebVTT([]byte("WEBVTT\n\n"+strings.Repeat(cue, count)), 1000) == nil; got != (count == 10000) {
			t.Fatalf("cue count %d", count)
		}
	}
	for _, raw := range [][]byte{{0xff, 0xfe}, append([]byte("WEBVTT\n\n00:00.000 --> 00:01.000\n"), 0xc0, 0xaf)} {
		if ValidateWebVTT(raw, 1000) == nil {
			t.Fatal("invalid UTF-8 accepted")
		}
	}
	prefix := "WEBVTT\n\n00:00.000 --> 00:01.000\n"
	boundary := []byte(prefix + strings.Repeat("a", (1<<20)-len(prefix)))
	if err := ValidateWebVTT(boundary, 1000); err != nil {
		t.Fatalf("exactly 1 MiB rejected: %v", err)
	}
	if ValidateWebVTT(append(boundary, 'a'), 1000) == nil {
		t.Fatal("more than 1 MiB accepted")
	}
}

// Keep the provider's RE2 validation aligned with the Python/Ajv language vectors
// in tests/contracts/fixtures/subtitles/languages.json, including final newlines.
func TestSubtitleLanguageProfile(t *testing.T) {
	for _, value := range []string{"es\n", "es\r", "es\r\n", "es\u2028", "es\u2029", "es ", " es", "es\t", "es\x00", "es_CO", "en-US-extra", "", "ES"} {
		if language.MatchString(value) {
			t.Errorf("invalid language accepted: %q", value)
		}
	}
	for _, value := range []string{"es", "en-US", "zh-Hant-TW", "es-419"} {
		if !language.MatchString(value) {
			t.Errorf("valid language rejected: %q", value)
		}
	}
}
