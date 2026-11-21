package nrcore

import (
	"net"
	"net/netip"
	"strings"
	"testing"

	C "github.com/metacubex/mihomo/constant"
)

func TestBuildTunOptionsAndroidSemantics(t *testing.T) {
	opts, err := buildTunOptions(23, "gvisor", "172.19.0.1/30,fdfe:dcba:9876::1/126", "172.19.0.2")
	if err != nil {
		t.Fatalf("buildTunOptions: %v", err)
	}

	// Android owns the interface: routes and auto-detect must stay off
	// (pitfall P3) and the fd from VpnService must be used directly.
	if !opts.Enable {
		t.Fatal("tun must be enabled")
	}
	if opts.AutoRoute {
		t.Fatal("AutoRoute must be false on Android (VPN builder owns routes)")
	}
	if opts.AutoDetectInterface {
		t.Fatal("AutoDetectInterface must be false on Android (protect() owns sockets)")
	}
	if opts.FileDescriptor != 23 {
		t.Fatalf("FileDescriptor = %d, want 23", opts.FileDescriptor)
	}
	if opts.MTU != TunMTU {
		t.Fatalf("MTU = %d, want %d", opts.MTU, TunMTU)
	}
	if len(opts.Inet4Address) != 1 || len(opts.Inet6Address) != 1 {
		t.Fatalf("prefix split wrong: v4=%d v6=%d", len(opts.Inet4Address), len(opts.Inet6Address))
	}
	if len(opts.DNSHijack) != 1 || opts.DNSHijack[0] != "172.19.0.2:53" {
		t.Fatalf("DNSHijack = %v", opts.DNSHijack)
	}
	if got := opts.Stack.String(); got != "gVisor" {
		t.Fatalf("Stack = %s", got)
	}
}

func TestBuildTunOptionsDefaults(t *testing.T) {
	opts, err := buildTunOptions(7, "not-a-stack", "", "")
	if err != nil {
		t.Fatalf("buildTunOptions: %v", err)
	}
	if got := opts.Stack.String(); got != "System" {
		t.Fatalf("unknown stack must fall back to system, got %s", got)
	}
	if len(opts.DNSHijack) != 0 {
		t.Fatalf("empty dns must produce no hijack, got %v", opts.DNSHijack)
	}
	if len(opts.Inet4Address) != 0 || len(opts.Inet6Address) != 0 {
		t.Fatal("empty gateway must produce no prefixes")
	}
}

func TestBuildTunOptionsRejectsBadGateway(t *testing.T) {
	_, err := buildTunOptions(7, "system", "not-a-prefix", "")
	if err == nil || !strings.Contains(err.Error(), "ParsePrefix") {
		t.Fatalf("bad gateway must error, got %v", err)
	}
}

func TestGuardHasHeadroom(t *testing.T) {
	// A healthy process always has headroom.
	if !guardHasHeadroom() {
		t.Fatal("healthy process must have fd headroom")
	}
}

type fakeUidResolver struct{ uid int32 }

func (f *fakeUidResolver) ResolveUid(protocol int32, source, target string) int32 {
	return f.uid
}

func TestResolveUidPseudoName(t *testing.T) {
	fake := &fakeUidResolver{uid: 10042}
	hooks.set(nil, fake)
	defer hooks.set(nil, nil)

	src, _ := netip.ParseAddrPort("192.0.2.10:443")
	dst, _ := netip.ParseAddrPort("198.51.100.5:80")
	md := C.Metadata{
		NetWork:    C.TCP,
		RawSrcAddr: &net.TCPAddr{IP: src.Addr().AsSlice(), Port: int(src.Port())},
		RawDstAddr: &net.TCPAddr{IP: dst.Addr().AsSlice(), Port: int(dst.Port())},
	}
	name, err := hooks.resolvePackageName(&md)
	if err != nil {
		t.Fatalf("resolvePackageName: %v", err)
	}
	if name != "uid:10042" {
		t.Fatalf("pseudo name = %q, want uid:10042", name)
	}
}
