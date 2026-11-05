package recordbinding

import (
	"net/http"
	"strings"
	"testing"
)

func TestNativeBindingIsPublisherBoundAndConsumedOnce(t *testing.T) {
	t.Setenv("MEDIA_NATIVE_RECORDING", "true")
	h := http.Header{}
	h.Set("X-Media-Recording-Session", "ses_00000000-0000-4000-8000-000000000014")
	h.Set("X-Media-Recording-Source-Generation", "2")
	Remember("publisher-one", h)
	if _, ok := Take("publisher-two"); ok {
		t.Fatal("binding crossed publishers")
	}
	path, ok := Take("publisher-one")
	if !ok || !strings.Contains(path, "/2/rend_source/") || strings.Contains(path, "sk_") {
		t.Fatal("safe recording path missing")
	}
	if _, ok := Take("publisher-one"); ok {
		t.Fatal("binding reused")
	}
	h.Set("X-Media-Recording-Session", "live/sk_secret")
	Remember("publisher-one", h)
	if _, ok := Take("publisher-one"); ok {
		t.Fatal("secret path accepted")
	}
}
