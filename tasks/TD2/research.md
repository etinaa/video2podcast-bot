# TD2 research

Researched: 2026-11-17. Scope: completion/count ordering and deterministic unit proof.
No host-service, Telegram, media or production calls were needed.

## Official Java sources

| Source | Fact used | Accessed |
|---|---|---|
| [Java 27 CompletableFuture — class contract](https://docs.oracle.com/en/java/javase/27/docs/api/java.base/java/util/concurrent/CompletableFuture.html) | Non-async completion actions may execute in the completing thread. Normal, exceptional and cancelled completion compete; only one succeeds. | 2026-11-17 |
| [Java 27 CompletableFuture — whenComplete](https://docs.oracle.com/en/java/javase/27/docs/api/java.base/java/util/concurrent/CompletableFuture.html#whenComplete(java.util.function.BiConsumer)) | The action receives the result/exception when the original stage completes. The returned stage completes after the action returns. | 2026-11-17 |
| [Java 27 CompletableFuture — timed get](https://docs.oracle.com/en/java/javase/27/docs/api/java.base/java/util/concurrent/CompletableFuture.html#get(long,java.util.concurrent.TimeUnit)) | Timed get waits for that future's result, with exceptional completion reported through ExecutionException. | 2026-11-17 |
| [Java 27 CountDownLatch](https://docs.oracle.com/en/java/javase/27/docs/api/java.base/java/util/concurrent/CountDownLatch.html) | A latch gates work until countDown; timed await reports timeout. Actions before countDown happen-before actions following a successful corresponding await. | 2026-11-17 |

Application to TD2 (inference from these contracts): publish count cleanup before completing the
future. Register the non-async callback while work is gated, and wait separately for its completion
before inspecting the recorded size. Waiting only for the original future can race callback execution.
Assert timeout results so failed coordination cannot masquerade as a size regression.

## Repository evidence

All local sources below were read on 2026-11-17. Paths identify the shared working tree at analysis
time, not an immutable revision; other roles may subsequently update their owned files.

| Source | Fact used |
|---|---|
| [brief.md](brief.md) and [TD2 in todo.md](../todo.md) | Owner selected the production fix; success and failure callbacks must provide deterministic proof. Keep the original test unchanged and run it 200 times locally. No functional *IT tests. |
| [SPEC.md](../../SPEC.md) and [plan.md](../plan.md) | One shared worker, FIFO, no concurrent task execution; unit-derived 90% line and branch gates. No new product flow is needed for TD2. |
| [JobQueue.java](../../src/main/java/com/video2podcast/job/JobQueue.java) | At analysis time, normal and RuntimeException branches complete before finally removes the future; the Error fallback also completes before removal. Shutdown has a separate cancel-then-remove path. |
| [JobQueueTest.java](../../src/test/java/com/video2podcast/job/JobQueueTest.java) | The existing immediate size assertion exposes the race. Existing tests cover FIFO, no overlap, RuntimeException/Error continuation, graceful/forced/interrupted close and rejection. They do not pre-register a last-task callback to observe size. |
| [whoami.feature](../../src/features/whoami.feature) and [T8-media.feature](../../src/features/T8-media.feature) | English Gherkin and requirement tags are established. Existing Telegram/media step vocabulary cannot express queue-future observations, so TD2 uses consistent queue-caller vocabulary. |
| [CucumberIT.java](../../src/it/java/com/video2podcast/functional/CucumberIT.java) | At analysis time, all feature resources except @T8 are selected. A newly tagged @TD2 file would still run without an explicit exclusion. |
| [api.md](api.md), [data-flow.md](data-flow.md), [components.md](components.md), [flows.md](flows.md) | Architect preserves current abnormal failure semantics and requires callback tests for success/RuntimeException. A new Error callback test is optional; production ordering still covers Error. |
| [comms/questions.md](comms/questions.md) | Q-analyst-2 clarified run-published completion versus shutdown/caller cancellation. A: Q-analyst-1 authorizes Tester-owned exclusion of @TD2 while preserving @T8, with no new functional steps. |

## Conclusions for the handoff

1. The count race is production ordering, not a reason to weaken the existing size assertion.
2. Gate a predecessor, register the last task's callback, release, record size inside the callback,
   and await callback completion. A following sentinel task or async callback can mask the defect.
3. Preserve original RuntimeException identity/logging and the existing abnormal Error fallback.
4. Scope the guarantee to completion published by run. Returned futures remain caller-mutable;
   shutdown cancellation retains its old ordering and is not strengthened by TD2.
5. [TD2-job-queue.feature](../../src/features/TD2-job-queue.feature) is unit-level contract documentation.
   Tester must implement the approved runner exclusion before unfiltered full verification.

## Evidence limits

No implementation or unit-test files were changed by Analyst. No tests, 200-run loop, coverage checks
or full Maven verification were executed in this analysis step. Feature syntax, requirement coverage
and document consistency are checked separately; they do not establish that the defect is fixed.
