// Package recordbinding passes the authorized public session/generation to the
// native recorder without changing engine configuration or retaining an ingest key.
package recordbinding

import (
	"fmt"
	"net/http"
	"os"
	"regexp"
	"strconv"
	"sync"
	"time"
)

var sessionID = regexp.MustCompile(`^ses_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
var mu sync.Mutex

type binding struct {
	path    string
	expires time.Time
}

var bindings = map[string]binding{}

func Enabled() bool { return os.Getenv("MEDIA_NATIVE_RECORDING") == "true" }
func Remember(publisher string, headers http.Header) {
	if !Enabled() {
		return
	}
	session := headers.Get("X-Media-Recording-Session")
	generation, err := strconv.ParseInt(headers.Get("X-Media-Recording-Source-Generation"), 10, 64)
	if !sessionID.MatchString(session) || err != nil || generation < 1 || len(headers.Values("X-Media-Recording-Session")) != 1 || len(headers.Values("X-Media-Recording-Source-Generation")) != 1 {
		return
	}
	mu.Lock()
	defer mu.Unlock()
	now := time.Now()
	for id, value := range bindings {
		if !now.Before(value.expires) {
			delete(bindings, id)
		}
	}
	if len(bindings) >= 10000 {
		return
	}
	bindings[publisher] = binding{fmt.Sprintf("/data/native/%s/%d/rend_source/%%s-%%f", session, generation), now.Add(2 * time.Minute)}
}
func Take(publisher string) (string, bool) {
	mu.Lock()
	defer mu.Unlock()
	value, ok := bindings[publisher]
	delete(bindings, publisher)
	return value.path, ok && time.Now().Before(value.expires)
}
