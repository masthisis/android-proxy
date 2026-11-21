// Package nrcore embeds the mihomo engine (Clash.Meta, MIT-licensed) inside
// the NeuroRift for Android app process and exposes the minimum
// gomobile-bindable surface the app needs.
//
// Design (docs/ARCHITECTURE.md): the app is a supervisor and orchestrator —
// mihomo carries 100% of the traffic. The Android VPN layer hands the tun
// file descriptor from VpnService to mihomo's tun listener (LC.Tun.
// FileDescriptor), and every outbound socket mihomo opens is protected via
// VpnService.protect() through the ProtectHook callback. The app talks to
// the core over its external-controller REST API, exactly like the Windows
// sibling talks to its child process.
//
// The fd-handoff architecture follows the approach proven by
// ClashMetaForAndroid (GPL-3.0, design credit only — no code copied); every
// line here is original, written against mihomo's public APIs.
package nrcore

import (
	"errors"
	"io"
	"strconv"
	"strings"
	"sync"
	"syscall"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/process"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
)

// ProtectHook is implemented on the Kotlin side and must call
// VpnService.protect(fd) for the given file descriptor. It is invoked for
// every outbound socket mihomo creates; without it, connections loop back
// into the tun device and hang forever.
type ProtectHook interface {
	Protect(fd int32) bool
}

// UidResolver resolves a socket tuple to the owning Android uid, used by
// mihomo's process resolver (per-app rule support). Return a negative value
// when the lookup fails.
type UidResolver interface {
	ResolveUid(protocol int32, source string, target string) int32
}

// Tun stack identifiers accepted by StartTun. Mirrors mihomo's stack config.
const (
	StackSystem = "system"
	StackGvisor = "gvisor"
	StackMixed  = "mixed"
)

// TunMTU matches the tun interface created by the VPN builder.
const TunMTU = 9000

var (
	errNotProtected = errors.New("nrcore: socket not protected by VpnService")
	errBlocked      = errors.New("nrcore: connection blocked by fd-limit guard")

	hooks   = &hookSet{}
	tunGate = &tunGateState{}
)

// hookSet owns the Kotlin callbacks and adapts them to mihomo's hooks.
type hookSet struct {
	mu      sync.RWMutex
	protect ProtectHook
	uid     UidResolver
}

func (h *hookSet) set(protect ProtectHook, uid UidResolver) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.protect = protect
	h.uid = uid
}

func (h *hookSet) protectFd(fd int32) bool {
	h.mu.RLock()
	protect := h.protect
	h.mu.RUnlock()
	if protect == nil {
		return false
	}
	return protect.Protect(fd)
}

// resolvePackageName adapts UidResolver to mihomo's package resolver.
// Android per-app routing happens at the VPN layer; the uid is exposed to
// mihomo rules as a "uid:<n>" pseudo process name.
func (h *hookSet) resolvePackageName(metadata *C.Metadata) (string, error) {
	if metadata == nil || metadata.RawSrcAddr == nil || metadata.RawDstAddr == nil {
		return "", process.ErrInvalidNetwork
	}
	h.mu.RLock()
	uid := h.uid
	h.mu.RUnlock()
	if uid == nil {
		return "", process.ErrInvalidNetwork
	}
	n := uid.ResolveUid(protocolOf(metadata.RawSrcAddr.Network()), metadata.RawSrcAddr.String(), metadata.RawDstAddr.String())
	if n < 0 {
		return "", process.ErrInvalidNetwork
	}
	return "uid:" + strconv.FormatInt(int64(n), 10), nil
}

func protocolOf(network string) int32 {
	switch network {
	case "udp", "udp4", "udp6":
		return 17 // IPPROTO_UDP
	default:
		return 6 // IPPROTO_TCP
	}
}

// socketHook is installed as dialer.DefaultSocketHook: every outbound socket
// mihomo creates passes through here. It enforces the fd-limit connection
// guard and protects the socket so it bypasses the VPN tunnel.
func (h *hookSet) socketHook(network, address string, conn syscall.RawConn) error {
	if !guardHasHeadroom() {
		return errBlocked
	}
	var protectErr error
	controlErr := conn.Control(func(fd uintptr) {
		if !h.protectFd(int32(fd)) {
			protectErr = errNotProtected
		}
	})
	if controlErr != nil {
		return controlErr
	}
	return protectErr
}

// tunGateState serializes tun listener lifecycle.
type tunGateState struct {
	mu     sync.Mutex
	closer io.Closer
}

func (g *tunGateState) swap(c io.Closer) (io.Closer, error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	old := g.closer
	g.closer = c
	if old != nil {
		return old, old.Close()
	}
	return nil, nil
}

// Init sets the mihomo home directory, stamps the core version and installs
// the platform hooks. Must be called once per app process before
// ApplyConfig. protect may be nil in core-only tests (then outbound sockets
// are left unprotected and StartTun must not be used).
func Init(homeDir, version string, protect ProtectHook, uid UidResolver) error {
	if homeDir != "" {
		C.SetHomeDir(homeDir)
	}
	if version != "" {
		C.Version = strings.ToLower(version)
	}
	hooks.set(protect, uid)
	dialer.DefaultSocketHook = hooks.socketHook
	process.DefaultPackageNameResolver = hooks.resolvePackageName
	return nil
}

// ApplyConfig parses and applies the generated runtime YAML config (mihomo
// hot apply). The external controller declared in the config starts here —
// the Kotlin control plane drives everything through it afterwards.
func ApplyConfig(yamlConfig string) error {
	cfg, err := executor.ParseWithBytes([]byte(yamlConfig))
	if err != nil {
		return err
	}
	hub.ApplyConfig(cfg)
	return nil
}

// Version reports the core version string set by Init (or mihomo's default).
func Version() string {
	return C.Version
}

// StopTun closes the active tun listener (if any). Safe to call repeatedly.
func StopTun() error {
	_, err := tunGate.swap(nil)
	return err
}

// Stop tears the tun listener down and stops the core's inbound listeners
// and tunnels by applying an inert config. The process keeps the external
// controller alive so the app can query status afterwards.
func Stop() error {
	if err := StopTun(); err != nil {
		return err
	}
	cfg, err := executor.ParseWithBytes([]byte(inertConfig))
	if err != nil {
		return err
	}
	executor.ApplyConfig(cfg, true)
	return nil
}

const inertConfig = `
mixed-port: 0
external-controller: ""
mode: direct
log-level: silent
`
