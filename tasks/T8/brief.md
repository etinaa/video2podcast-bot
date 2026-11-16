# T8 brief

## Owner's original words
`/orchestrate T8` (task T8 already existed in `tasks/todo.md` as `[ ]`).

## Questions and answers
1. Scope: keep `AudioPreparer` / `encodeAacMono` (50 MB rule) out of T8, for T13? **Yes, out of scope.**
2. Depends on: drop T3 (host-service fixtures, unused by T8)? **Yes, only T1.**
3. Timeouts: `ProcessRunner` as an interface with `run(args, timeout)`, configurable under `jobs.*`, defaults metadata 1 min, download 30 min, ffmpeg 30 min, kill process tree on timeout? **Yes.**
4. URL validation: http(s) only, host required, max 2048, no whitespace/control chars, no userinfo, reject localhost and literal loopback/private/link-local IPs, no DNS resolution, `--` before URL? **Yes.**
5. Error mapping: `JobFailure` with `Kind` (PRIVATE, UNSUPPORTED, TOO_LONG, FAILED, TIMEOUT) and safe message, stderr substring table, raw stderr only in log, live streams (no duration) rejected as UNSUPPORTED? **Yes, live streams rejected.**

## Agreed task
See T8 in `tasks/todo.md` (status `[~]`). No functional `*IT` tests in T8: it has no user-visible behavior yet.
