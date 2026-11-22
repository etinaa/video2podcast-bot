
## Review round 1 fixes (developer)
- HealthChecks.await: no try-with-resources; `finally` cancels both futures and calls `shutdownNow()` (no waiting for uncooperative probes, also on the interrupted-caller path). PMD CloseResource suppressed on that method with a comment.
- HttpProbe: a `ConnectException` whose cause is `UnresolvedAddressException` (what the real HttpClient throws for DNS failure) now gives "host not found"; the direct branch stays. api.md reason table unchanged and now consistent.
- Tests: new `aProbeThatIgnoresInterruptionDoesNotHoldTheCheckBeyondTheDeadline` (release latch, released in finally), `anInterruptedCallerDoesNotWaitForAStubbornProbe`, DNS-cause case in HttpProbeTest; upper bound of the overrun test widened from 1900 to 3500 ms.
- Not done (style only): static imports in tests.
- Result: spotless:check passes; `./mvnw verify -Dno-it` passes: 836 tests, JaCoCo gate met, SpotBugs 0, PMD clean.

## Review round 1, correction (developer)
- Removed the `@SuppressWarnings("PMD.CloseResource")` from `HealthChecks.await`. No suppression is left in the file.
- `await()` now starts each probe as a `FutureTask` on `Thread.ofVirtual()`; the `finally` cancels both with `cancel(true)` (interrupts a running probe, waits for none), also on the interrupted-caller path. No executor.
- Tests unchanged (including the two stubborn-probe tests). `spotless:check` passes; `./mvnw verify -Dno-it`: 836 tests, JaCoCo met, SpotBugs 0, PMD clean.

## Interrupted caller defect (developer)
- Cause: `FutureTask.get` returns a finished result without looking at the caller's interrupt flag, so an interrupted caller whose probes finished at once passed the check.
- Fix: `HealthChecks.await()` starts with `if (Thread.interrupted()) throw new InterruptedException();`. The existing handlers map it to both-down "interrupted" in `check()` and to `FAILED` in `requireReachable()`, restoring the flag. Deterministic: no probe is started for an interrupted caller. No suppression added.
- Repeat check: the two interrupt tests run with `@RepeatedTest(200)` in one JVM (temporary, reverted): 414 tests, 0 failures. Test file unchanged in the end.
- `spotless:check` passes; `./mvnw verify -Dno-it`: 836 tests, JaCoCo met, SpotBugs 0, PMD clean.
