
## Q-analyst-1 · analyst → tester
The Analyst role requires src/features/TD2-job-queue.feature, but the brief explicitly excludes functional *IT tests. I will tag these queue-caller contract scenarios @TD2 @unit_queue and map them to Developer-owned JobQueueTest, with no new Cucumber steps. CucumberIT currently selects every feature except @T8, so it would select undefined TD2 steps. Please exclude @TD2 from that runner (preserving the existing @T8 exclusion) in your owned src/it zone, or coordinate another approved selection mechanism with the orchestrator. This is runner selection only, not a request for TD2 functional tests. Please confirm the handoff; Analyst will not edit src/it.

## Q-analyst-2 · analyst → architect
The task and brief scope the completion-order guarantee to completion performed by JobQueue.run. Shutdown cancellation remains unchanged, and callers can also complete/cancel the mutable returned future themselves. Please qualify api.md and data-flow.md guarantees accordingly: a task is removed before run publishes normal/exceptional completion; this does not promise ordering for close()-initiated or caller-initiated cancellation/completion. No new cancellation contract is requested.

## A: Q-architect · architect (answer to Q-analyst-2)
Agreed. `api.md` now scopes the guarantee to completion performed by `JobQueue.run` and lists
`close()`-initiated and caller-initiated cancellation/completion as out of scope with no new contract.
`data-flow.md` invariant is qualified the same way. No design change.

## A: Q-analyst-1 · orchestrator
Agreed, in line with the brief (no functional `*IT` tests in TD2). The Tester is not running yet. When the Tester starts, it will exclude `@TD2` in `CucumberIT` (keeping `not @T8`, for example `not @T8 and not @TD2`) in its Step 2, with a one-line comment that the scenarios are covered by the Developer-owned `JobQueueTest`. Runner selection only. Write the scenarios in `src/features/TD2-job-queue.feature` as planned, tagged `@TD2 @unit_queue`.

## TD2 Analyst design handoff · analyst
Ready for design review: tasks/TD2/requirements.md (R1–R7, acceptance criteria, evidence map, commands and exclusions), tasks/TD2/research.md (dated official Java 27 and local sources), and src/features/TD2-job-queue.feature (10 scenario definitions / 15 expanded cases, @TD2 @unit_queue and requirement tags).

Validation completed: installed Gherkin 42.0.1 parser compiled all 15 cases with no parse errors or unexpanded outline parameters; every case has selection/requirement tags and R1–R7 are covered. Local Markdown links resolve; new artifact whitespace checks and git diff --check pass. Temporary parser helper: /tmp/ValidateTd2Feature.java (not a project artifact).

Q-analyst-2 is resolved by Architect's qualified run-completion contract. Q-analyst-1 is resolved by the orchestrator's approved Tester handoff: exclude @TD2 while retaining @T8 before full verification. Error callback examples specify the contract; a new dedicated Error callback unit test remains optional as agreed in api.md. No production/unit/IT code was changed by Analyst, and no red/green tests, 200-run loop, coverage or Maven verification were run here. This completes the Analyst's initial deliverables, not TD2 implementation or design approval.

## A: Q-analyst-1 · tester
Confirmed for Step 2: exclude @TD2 in CucumberIT while retaining not @T8, with the approved one-line comment identifying Developer-owned JobQueueTest coverage. No TD2 functional IT or step definitions will be added. Step 1 makes no runner changes.

## Q-tester-1 · tester → architect
The api.md deterministic-test recipe step 4 currently says wait on last.get(timeout), then await the callback latch. This can undermine deterministic red evidence: OpenJDK CompletableFuture.timedGet calls postComplete(), so the get waiter may claim/run the non-async callback concurrently with the completing worker; the worker can then finish complete() and remove the future before the callback reads size(). A pre-registered callback alone does not force worker-thread execution when the test also waits on the original future. Please require: register callback while predecessor is gated; release predecessor; first await/assert the callback CountDownLatch with a timeout (without get/join on the original future); only after the callback observation, call last.get(timeout) to assert normal/original exceptional completion. This keeps the callback on the worker completion path and makes pre-fix size 1 deterministic. No public-contract or scope change is needed. Source: https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/concurrent/CompletableFuture.java (timedGet/postComplete). All feature scenarios otherwise have observable contracts through submit/size/close, returned futures and existing logging; Error callback proof remains optional as agreed.

## A: Q-tester-1 · architect
Agreed. `api.md` recipe now: register the callback while the predecessor is gated, release it, first await
the callback latch (no `get`/`join` on the last future), then call `last.get(timeout)` and assert
`observed` is 0. No contract or scope change.

## Q-tester-1 resolved · tester
Reviewed the Architect's answer and updated api.md steps 4–5: callback latch first, original future get second. This resolves the deterministic-proof concern. Step 1 review covers all 10 scenario definitions / 15 expanded cases against submit/size/close, returned futures and logging. Success, RuntimeException and Error completion ordering are specified; shutdown and caller-initiated completion retain their scoped exclusions. No remaining tester design questions. No tests or runner changes made in Step 1.
