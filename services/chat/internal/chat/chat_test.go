package chat

import (
	"strings"
	"testing"
)

func TestNormalizeTextAppliesNFCAndTrimsUnicodeWhitespace(t *testing.T) {
	// "e" + acento combinante se compone a "é"; NBSP e ideographic space son whitespace Unicode.
	got, err := NormalizeText(" 　 café \n")
	if err != nil {
		t.Fatalf("unexpected error %v", err)
	}
	if got != "café" {
		t.Fatalf("got %q", got)
	}
}

func TestNormalizeTextBoundaries(t *testing.T) {
	cases := []struct {
		name string
		in   string
		code string
	}{
		{"vacío", "", CodeMessageEmpty},
		{"solo espacios", " \t  ", CodeMessageEmpty},
		{"500 puntos de código", strings.Repeat("a", 500), ""},
		{"500 emojis de 4 bytes", strings.Repeat("😀", 500), ""},
		{"501 puntos de código", strings.Repeat("a", 501), CodeMessageTooLong},
		{"501 emojis", strings.Repeat("😀", 501), CodeMessageTooLong},
		{"500 tras recortar", "  " + strings.Repeat("b", 500) + "  ", ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := NormalizeText(tc.in)
			switch {
			case tc.code == "" && err != nil:
				t.Fatalf("unexpected error %v", err)
			case tc.code != "" && (err == nil || err.Code != tc.code):
				t.Fatalf("want %s, got %v", tc.code, err)
			}
		})
	}
}

func TestNormalizeTextKeepsMarkupAsPlainText(t *testing.T) {
	got, err := NormalizeText("<script>alert(1)</script>")
	if err != nil || got != "<script>alert(1)</script>" {
		t.Fatalf("got %q %v", got, err)
	}
}

func TestValidClientMessageID(t *testing.T) {
	if !ValidClientMessageID("3f1c2e7a-9b1d-4c55-8a0e-2b7d9c1e4f60") {
		t.Fatal("UUID canónico rechazado")
	}
	for _, bad := range []string{"", "abc", "3f1c2e7a9b1d4c558a0e2b7d9c1e4f60", "{3f1c2e7a-9b1d-4c55-8a0e-2b7d9c1e4f60}"} {
		if ValidClientMessageID(bad) {
			t.Fatalf("aceptó %q", bad)
		}
	}
}

func TestRoomStatusFromSession(t *testing.T) {
	want := map[string]RoomStatus{"PREPARING": RoomNotOpen, "LIVE": RoomOpen, "RECONNECT_GRACE": RoomOpen, "ENDED": RoomReadOnly}
	for in, out := range want {
		if got, ok := RoomStatusFromSession(in); !ok || got != out {
			t.Fatalf("%s -> %s", in, got)
		}
	}
	if _, ok := RoomStatusFromSession("OFFLINE"); ok {
		t.Fatal("estado desconocido aceptado")
	}
}
