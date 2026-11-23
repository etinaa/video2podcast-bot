# TD11 tester — helper API ready

`src/it/java/com/video2podcast/functional/WireMocks.java` now owns all WireMock construction in `src/it`.

- `static WireMockServer scenarioServer(Extension... extensions)` returns an already-started server, caller stops it.
- `static WireMockServer scenarioServer(int preferredPort, Extension... extensions)` attempts the given port first (0 = OS-assigned). Used to exercise a real occupied port deterministically.
- Both explicitly configure `wireMockConfig().bindAddress("127.0.0.1")` and allow **3 total startup attempts**. On a runtime startup failure, attempt to stop the failed server. Retry only if its cause chain contains `BindException` or `Address already in use`; subsequent attempts use port 0. Rethrow non-bind failures and the final startup failure. Cleanup exceptions are attached to the original failure without masking the startup cause or blocking bind retries.
- Shared TELEGRAM/WHISPER/OLLAMA startup delegates to the same helper, retaining shutdown hooks. `url(server)` delegates to `server.baseUrl()`.
- PipelineWorld, TranscriptWorld, and AudioSupport already use `scenarioServer`, so no duplicate startup logic or caller changes are needed.

Developer: mirror the same loopback and bind-only bounded retry semantics in TelegramConfigTest; no dependency on src/it is needed.

Regression RED confirmed in plain `./mvnw verify`: WireMocksIT's occupied-loopback test observed the original silent port reuse, and the bind-address test observed wildcard binding. Full RED log currently at target/tester-verify.log; GREEN verification follows once this run finishes. Maven slot remains with tester.

## RED completed

- Plain verify finished with the intentional regression failures: 867 unit tests passed; 445 integration tests, 2 failures (both WireMocksIT), 0 errors, 5 pre-existing skips. CucumberIT itself: 403 scenarios, 0 failures/errors, 5 skips.
- Occupied-port regression observed silent reuse of port 65421; shared/scenario binding test observed 0.0.0.0 instead of 127.0.0.1.
- Evidence preserved: target/td11-tester-red-verify.log and target/td11-wireMocks-red.txt.
- Spotless also flagged helper formatting while RED finished; corrected before GREEN. The initially attempted spotlessFiles globs matched no paths; the plugin matches a regex against absolute paths. Scope is `.*src/it/.*WireMocks.*`.
- Developer temporarily owns the Maven slot for formatting/unit checks. GREEN plain verify is pending that handoff.

## GREEN started

Developer reported DONE and returned the Maven slot. `./mvnw spotless:apply '-DspotlessFiles=.*src/it/.*WireMocks.*'` succeeded and confirmed exactly 2 integration files clean. Plain verify is running with the loopback/retry implementation and the extension-preserving collision regression; log: target/tester-verify.log.

## Failed-bind cleanup correction

The first GREEN attempt exposed another real failure in WireMocksIT: after a failed bind, WireMock/Jetty `stop()` sneaky-throws checked `ExecutionException(IllegalStateException: STOPPED)`. A catch of RuntimeException does not catch it, and throwing on cleanup failure blocks the required retry. The helper now catches Exception during cleanup and attaches it to the original startup failure, then uses the original startup cause to choose retry/rethrow. Developer notified to mirror this.

Evidence: target/td11-wireMocks-cleanup-failure.txt and target/td11-tester-cleanup-failure-verify.log. The known-failing verify was interrupted (exit 130) after the regression error was recorded; no second Maven was started concurrently. A fresh full plain verify follows the correction.

## Retry confirmed; URL assertion corrected

The corrected helper chose a different port and returned the extension's real HTTP 200/body, proving retry plus extension preservation. The next assertion incorrectly expected WireMock.baseUrl() to contain the literal host 127.0.0.1; WireMock returns localhost independently of its bind address. Corrected that assertion to verify the returned URL resolves to a loopback address. Exact IPv4 binding remains checked independently against server options. Interrupted this known-failing run (exit 130), archived target/td11-tester-url-assertion-verify.log, and formatted the test before the next full plain verify.

## Final result — DONE

Plain `./mvnw verify` completed **BUILD SUCCESS**, exit 0, on 2026-11-23 at 17:25:41 (+02:00), duration 7:08. No clean was run; Maven runs were sequential.

- Unit tests: **867**, 0 failures/errors/skips.
- Integration tests: **445**, 0 failures/errors, 5 existing skips. Included CucumberIT: 403 scenarios, 0 failures/errors, 5 existing skips.
- WireMocksIT: **2 passed**, covering real held-port retry, extension preservation, real HTTP response/journal, and shared/scenario IPv4 loopback binding.
- Spotless, JaCoCo coverage gates, SpotBugs, PMD, and Failsafe verification passed.
- Final evidence: `target/td11-tester-green-verify.log`; regression result: `target/failsafe-reports/com.video2podcast.functional.WireMocksIT.txt`.

Tester changes: `src/it/java/com/video2podcast/functional/WireMocks.java` and new `WireMocksIT.java`, plus TD11 comms. All `src/it` WireMock construction is centralized in WireMocks; PipelineWorld, TranscriptWorld, and AudioSupport already delegate to it. No production changes, suppression annotations/configuration, timeout increases, or feature changes. Index/branch left untouched for orchestrator.

Reviewed the final helper/test diff: startup retries are capped at 3 and restricted to bind failures, original failures retain cleanup diagnostics, all attempts preserve extensions, and each test-owned socket/server/client is closed. Helper API was shared with developer before final verification; developer's mirrored cleanup correction is included in this successful full run.

Maven slot released to orchestrator. This is one successful plain verify; repeated clean/mutation gates were not run by tester, per the explicit plain-verify/no-clean instruction.

STATUS: DONE
