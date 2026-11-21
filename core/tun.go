package nrcore

import (
	"net"
	"net/netip"
	"strings"

	C "github.com/metacubex/mihomo/constant"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/listener/sing_tun"
	"github.com/metacubex/mihomo/tunnel"
)

// StartTun binds mihomo's tun listener to the file descriptor of the tun
// interface created by the Android VpnService builder.
//
// Android owns the interface: routes, address and MTU are configured by the
// VPN builder, so AutoRoute and AutoDetectInterface must stay false and the
// outbound sockets are protected through the socket hook instead (pitfall
// P3/P4 in docs/AI_DEVELOPER_GUIDE.md).
//
// Arguments:
//   - fd:      the tun fd from VpnService (one-shot resource — pitfall P2)
//   - stack:   "system" | "gvisor" | "mixed" (empty -> system)
//   - gateway: VPN address prefixes, e.g. "172.19.0.1/30,fdfe:dcba:9876::1/126"
//   - portal:  reserved (unused, kept for signature stability)
//   - dns:     addresses to hijack DNS to, e.g. "172.19.0.2" ("" -> none)
//
// The returned listener is tracked internally; StopTun closes it. The tun
// listener is created outside of the YAML config because the fd can only
// come from the running VpnService.
func StartTun(fd int32, stack, gateway, portal, dns string) error {
	options, err := buildTunOptions(int(fd), stack, gateway, dns)
	if err != nil {
		return err
	}
	listener, err := sing_tun.New(options, tunnel.Tunnel)
	if err != nil {
		return err
	}
	tunGate.swap(listener)
	return nil
}

// buildTunOptions constructs LC.Tun from the Android VPN parameters. Kept
// pure for unit testing.
func buildTunOptions(fd int, stack, gateway, dns string) (LC.Tun, error) {
	tunStack, ok := C.StackTypeMapping[strings.ToLower(stack)]
	if !ok {
		tunStack = C.TunSystem
	}

	var prefix4, prefix6 []netip.Prefix
	for _, part := range strings.Split(gateway, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		prefix, err := netip.ParsePrefix(part)
		if err != nil {
			return LC.Tun{}, err
		}
		if prefix.Addr().Is4() {
			prefix4 = append(prefix4, prefix)
		} else {
			prefix6 = append(prefix6, prefix)
		}
	}

	var dnsHijack []string
	for _, part := range strings.Split(dns, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		dnsHijack = append(dnsHijack, net.JoinHostPort(part, "53"))
	}

	return LC.Tun{
		Enable:              true,
		Device:              sing_tun.InterfaceName,
		Stack:               tunStack,
		DNSHijack:           dnsHijack,
		AutoRoute:           false,
		AutoDetectInterface: false,
		Inet4Address:        prefix4,
		Inet6Address:        prefix6,
		MTU:                 TunMTU,
		FileDescriptor:      fd,
	}, nil
}
