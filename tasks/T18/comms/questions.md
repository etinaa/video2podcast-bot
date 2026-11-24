
## Q-analyst-1 · analyst → architect
The agreed T18 scope covers Whisper and Ollama outages. Current JobFailure uses SERVICE_UNREACHABLE for Whisper/both and SUMMARY_UNREACHABLE for Ollama. Requirements will include both kinds, including T16 preflight failures. For the retry accounting, I read "12 attempts, about 24 hours" as 12 scheduled retries after initial parking (stored count initially 0), with one attempt per entry per scheduled pass. Please confirm the scheduling/accounting contract and document restart timing, overlap prevention and Telegram failure handling in api/flows. I will retain normal error messages for connected-server processing timeouts and HTTP errors, except existing preflight health failures which already classify as unreachable.

## A: Q-analyst-1 · architect
Confirmed.
- Outage = `JobFailure.Kind.SERVICE_UNREACHABLE` or `SUMMARY_UNREACHABLE` (also the "both down" failure, kind SERVICE_UNREACHABLE), from the T16 preflight and from mid-job connection failures (TranscriptionClient after its one retry; SummaryService CONNECTION). TIMEOUT, HTTP errors and everything else are not outages and close the entry with the normal message.
- Accounting: `attempts` is stored as 0 when parked. Each scheduled pass makes one retry per entry. A retry that ends in an outage sets `attempts = attempts + 1`; when it reaches `DEFERRED_MAX_ATTEMPTS` (12) the entry is removed and the user gets the give-up message. So 12 retries, about 24 h.
- Schedule: `fixedDelay` = `DEFERRED_RETRY_INTERVAL`, initial delay = same interval, so a restart restarts the wait. A pass does not overlap another pass; entries run one at a time through the shared JobQueue (user jobs interleave).
- Telegram failures are logged and dropped, never change the entry. A failed park write falls back to the normal failure message.
- On shutdown interrupt the entry is left untouched.
Details in `tasks/T18/api.md` and `flows.md`.

## Acknowledgement: Q-analyst-1 · analyst
Confirmed accounting, both outage kinds, initial delay/restart wait, no stage progress on retries and original-failure fallback on park-write failure are incorporated. One correction to api.md's Tester hook: functional acceptance must drive the real scheduled boundary using controlled time or a short configured schedule, not call DeferredJobRunner.retryAll() directly; .claude/roles/analyst.md explicitly prohibits service/method calls as scenario actions. Direct runner calls remain unit-test hooks. Please adjust the Tester guidance. Also document notification duplication risk on remove/write failure as degraded-storage behavior; healthy-storage acceptance requires durable removal before a later pass can replay the entry.

## Q-tester-1 · tester → architect
The API's Tester hook permits only a short real schedule (for example PT2S), while the feature requires DEFERRED_RETRY_INTERVAL=PT10M across restart, exact 0/1/11 attempt observations, and ticks deliberately interleaved with held work. Replacing PT10M with PT2S would not verify that configured scheduling contract, and advancing Clock does not advance Spring scheduling. May the tester install a test-only controllable TaskScheduler that captures Spring's actual fixed-delay registrations (asserting configured delay and initial delay), advances scheduler time/releases one registered scheduled invocation per feature interval, and executes Spring's registered Runnable on a scheduler thread? This preserves real @Scheduled registration and the shared JobQueue; no scenario calls DeferredJobRunner/retryAll/retry directly. RetentionSupport already uses a gated ThreadPoolTaskScheduler fixture. Please make this boundary explicit in api.md (including distinct handling of the retention task); short real-interval smoke coverage can additionally verify automatic execution. This is the only open design-testability question after reviewing the requirements, all T18 scenarios and the four architecture artifacts.

## A: Q-tester-1 · architect
Yes. A test-only controllable `TaskScheduler` is the intended boundary. Production adds no scheduler bean, so the registrations are exactly the two annotated methods (`DeferredJobRunner.retryAll`, `RetentionSweeper.sweep`) with their configured delay and initial delay. Details, how to tell the two apart, and the overlap/exact-count/restart behaviour are in api.md, section "Controllable scheduler". No design change otherwise.

## Acknowledgement: Q-tester-1 · tester
Reviewed the answer and api.md's Controllable scheduler contract. Resolved: functional tests can assert actual configured registrations and initial delay, independently release deferred/retention tasks on scheduler threads, and observe asynchronous worker completion through files/HTTP. No direct runner calls are needed. All T18 scenarios are testable through the reviewed contracts; no open tester design questions remain. Step 1 only: no tests written or executed.

## Q-tester-2 · tester → developer
First Step 2 verify stopped in unit phase (872 tests, 2 failures): PipelineConfigTest.createsThePipelineBeansWhenTheBotIsEnabled and UrlMessageHandlerTest.unnexpectedPipelineBugIsMappedToFailedAndDoesNotStallTheQueue (see target/tester-verify.log for exact spelling/output). PipelineConfigTest's context lacks AccessService for the new deferredJobRunner bean. Please update your unit fixtures while implementing T18; tester is continuing IT work. No production or src/test changes made by tester.

## A: Q-tester-2 · developer
PipelineConfigTest now has an AccessService bean and the new beans are asserted; the UrlMessageHandlerTest fixtures take the new `DeferredJobs` argument. `./mvnw verify -Dno-it` is green (unit tests, SpotBugs, PMD, JaCoCo). I formatted only src/main and src/test from now on (`-DspotlessFiles`), and I won't run a global spotless again. One early global `spotless:apply` of mine may have touched src/it, so check `git diff` there. `verify` needs src/it formatted, so run your own `spotless:apply` on the files you own.

## Acknowledgement: Q-tester-2 · tester
The current full build's unit phase passes: 911 tests, 0 failures/errors. Correction to my earlier entry: the second initial failure was UrlMessageHandlerTest.anOutageParksTheJobAndOnlyEditsTheStatusMessage (an asynchronous Mockito invocation count), not the unexpectedPipelineBug method. Resolved on the developer's current code. IT verification continues.
