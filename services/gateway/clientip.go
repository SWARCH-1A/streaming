package main

import (
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"strings"
)

// trustedProxies reconstructs the observed client address. X-Forwarded-For is read only when the
// direct peer is a configured proxy (Caddy); otherwise the peer itself is the client. Core trusts
// the gateway address and applies the same right-to-left walk, so per-IP quotas stay per client.
type trustedProxies []netip.Prefix

func parseTrustedProxies(csv string) (trustedProxies, error) {
	var result trustedProxies
	for _, entry := range strings.Split(csv, ",") {
		value := strings.TrimSpace(entry)
		if value == "" {
			continue
		}
		if !strings.Contains(value, "/") {
			address, e := netip.ParseAddr(value)
			if e != nil {
				return nil, fmt.Errorf("invalid trusted proxy")
			}
			result = append(result, netip.PrefixFrom(address.Unmap(), address.Unmap().BitLen()))
			continue
		}
		prefix, e := netip.ParsePrefix(value)
		if e != nil {
			return nil, fmt.Errorf("invalid trusted proxy")
		}
		result = append(result, prefix.Masked())
	}
	return result, nil
}

func (t trustedProxies) contains(address netip.Addr) bool {
	address = address.Unmap()
	for _, prefix := range t {
		if prefix.Contains(address) {
			return true
		}
	}
	return false
}

func peerAddress(r *http.Request) (netip.Addr, bool) {
	host, _, e := net.SplitHostPort(r.RemoteAddr)
	if e != nil {
		host = r.RemoteAddr
	}
	address, e := netip.ParseAddr(host)
	if e != nil {
		return netip.Addr{}, false
	}
	return address.Unmap(), true
}

// forwardedFor returns the single client address to forward, or "" when the peer is unknown.
// A forged header from an untrusted peer, or a malformed entry, never selects an address.
func (t trustedProxies) forwardedFor(r *http.Request) string {
	peer, ok := peerAddress(r)
	if !ok {
		return ""
	}
	if len(t) == 0 || !t.contains(peer) {
		return peer.String()
	}
	var chain []string
	for _, header := range r.Header.Values("X-Forwarded-For") {
		for _, part := range strings.Split(header, ",") {
			chain = append(chain, strings.TrimSpace(part))
		}
	}
	for i := len(chain) - 1; i >= 0; i-- {
		address, e := netip.ParseAddr(chain[i])
		if e != nil {
			return peer.String()
		}
		if !t.contains(address) {
			return address.Unmap().String()
		}
	}
	return peer.String()
}
