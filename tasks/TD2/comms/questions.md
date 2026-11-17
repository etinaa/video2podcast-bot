
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

## TD2 Step 2 runner handoff · tester
CucumberIT now filters `not @T8 and not @TD2`, with the approved one-line comment that TD2 scenarios are covered by Developer-owned JobQueueTest. No TD2 step definitions or functional IT classes were added.

Validation with JAVA_HOME=/Users/andreietin/.sdkman/candidates/java/current:
- `./mvnw spotless:apply -DspotlessFiles=src/it/java/com/video2podcast/functional/CucumberIT.java` passed (target/td2-tester-spotless.log).
- Initial sandboxed `./mvnw verify` failed from sandbox restrictions: Mockito attachment, WireMock socket binding, and process-descendant sysctl access (target/td2-tester-step2-verify.log).
- After permission approval, unsandboxed `./mvnw verify` passed, including unit/functional tests, coverage, Spotless, SpotBugs and PMD (target/td2-tester-step2-verify-unsandboxed.log).
- Runner diff whitespace check passed.

This is working-tree verification: latest commit remains 651fcc3 (TD2: design), and Developer-owned JobQueue/JobQueueTest changes are uncommitted. Post-commit checking of Developer tests and evidence remains for the orchestrator's Step 3 handoff. Step 2 runner work is complete.

## TD2 Step 3 committed-test review · tester
Reviewed commit 866912c against requirements R1–R7, all 10 scenario definitions / 15 expanded cases, and the updated api.md recipe. Developer-owned source/test files match the commit; only the approved CucumberIT filter remains modified in the tester zone. No Developer or Analyst files changed by Tester.

Mandatory TD2 race proof is satisfied: success and RuntimeException callbacks are registered behind a gated predecessor, record size in AtomicInteger(-1), assert bounded callback-latch completion before get, and assert zero. RuntimeException identity is asserted both through get and the callback. The optional Error callback test is also present and checks zero plus the abnormal IllegalStateException outcome. Production removal precedes completion on all three paths; RuntimeException logging, Error propagation, FIFO and shutdown code remain intact. The original sizeCountsWaitingAndRunningTasksUntilTheyFinish method is unchanged.

Scenario coverage gaps (no observed production defect; mandatory backlog callback tests are present):
1. R1 / scenario "A completed task leaves remaining work counted": JobQueueTest never holds B running after A completes and asserts size == 1. The existing unchanged test checks 2 before release and 0 after both finish. Thus preservation of other work is supported by reviewing open.remove(result), but this scenario has no direct automated assertion. A Developer-owned follow-up could gate B, await A and B's start, assert 1, then release B.
2. R3/R5 / scenario "A runtime failure keeps the existing error log": no unit assertion captures the ERROR event, message and original throwable. The unchanged LOG.error call and execution logs provide review/runtime evidence, but not a regression assertion for this scenario.

The success callback's null result/no-exception and Error callback's abnormal exception are checked via the original future outcome rather than directly recording those callback arguments. RuntimeException callback identity is asserted directly. This is narrower assertion coverage than the literal callback example table, not a failure of the completion/count ordering tests.

Inspected evidence logs: td2-red.log (12 tests / 2 callback failures observing 1), td2-green.log (12 / 0), td2-red2.log (13 / 3 callback failures), td2-repeat200.log (211 / 0, including 200 temporary repetitions of the original method). No repetition annotation remains committed. The 200-run mechanism was a temporary RepeatedTest rather than 200 separate Maven invocations; evidence.md discloses this and both Developer reviewers accepted it.

Post-commit verification with JAVA_HOME=/Users/andreietin/.sdkman/candidates/java/current, outside the sandbox for required sockets/attachment/process inspection: `./mvnw verify` PASSED. Log: target/td2-tester-step3-verify.log. 323 unit tests passed; JobQueueTest: 13 passed; functional summary: 70 tests, 0 failures/errors, 51 excluded/skipped cases. Coverage, Spotless, SpotBugs and PMD passed. CucumberIT retains `not @T8 and not @TD2`; no undefined TD2 steps were selected. This run is verify, not clean verify; the separate project clean-verify gate is not claimed here.

Step 3 review finished. No failing test or mandatory race-test gap requires a Tester-zone change; scenario assertion gaps above are reported for Developer/orchestrator disposition.
