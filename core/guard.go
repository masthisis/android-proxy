package nrcore

import (
	"syscall"
)

// Connection guard: under memory pressure Android kills processes with too
// many open fds; a VPN app hitting the ceiling would die with it. Once the
// process holds ~3/4 of its fd limit, new outbound connections are refused
// until some close — a survivable error for the affected request instead of
// a process death. (Behavioural parity with the reference Android clients;
// original implementation.)
var (
	guardNullFd   int
	guardMaxFd    int
	guardHeadroom int
)

func init() {
	fd, err := syscall.Open("/dev/null", syscall.O_WRONLY, 0o644)
	if err != nil {
		guardMaxFd = -1 // guard disabled
		guardHeadroom = -1
		return
	}
	guardNullFd = fd

	var limit syscall.Rlimit
	if err := syscall.Getrlimit(syscall.RLIMIT_NOFILE, &limit); err != nil {
		guardMaxFd = 1024
	} else {
		guardMaxFd = int(limit.Cur)
	}
	guardHeadroom = guardMaxFd / 4 * 3
}

// guardHasHeadroom reports whether a new socket may be opened.
func guardHasHeadroom() bool {
	if guardMaxFd <= 0 {
		return true
	}
	fd, err := syscall.Dup(guardNullFd)
	if err != nil {
		// Cannot even dup /dev/null — out of fds right now.
		return false
	}
	_ = syscall.Close(fd)
	return fd <= guardHeadroom
}
