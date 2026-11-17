# TD2 requirements — queue size at task completion

Status: ready for design review. Date: 2026-11-17.

## Objective and sources

Remove the production race behind `JobQueueTest.sizeCountsWaitingAndRunningTasksUntilTheyFinish`.
A queue caller must observe a finished task removed from the count when the queue publishes its
completion, including inside its completion callback. T11 will consume this count for queue position;
TD2 adds no Telegram behaviour.

Authority: [TD2 brief](brief.md), [TD2 backlog acceptance](../todo.md),
[SPEC.md](../../SPEC.md) (single shared worker, first come first served, independent 90% line/branch
coverage gates), and the [architecture contract](api.md). The brief's agreed production fix and
unit-test proof take precedence over a test-side wait. No unresolved product questions.

## Requirements and acceptance criteria

### R1 — Count waiting and running tasks

- `size()` counts accepted tasks that are waiting or still executing. It does not drop a task merely
  because that task has started.
- With task A blocked while running and task B waiting, the count is 2. After A finishes and B is
  running but blocked, it is 1. After B finishes and its future completes, it is 0.
- Completion of A removes only A: other waiting/running work remains counted.
- These observations use controlled tasks and no unrelated concurrent submissions. The guarantee
  does not promise a permanently zero count if another caller submits work afterward.

### R2 — Publish successful completion after updating the count

- On normal return from a task, `JobQueue.run` removes that task from `open` before completing its
  returned future normally with `null`.
- After a caller's bounded `get()` on the last task returns, `size()` is 0 without polling, retries or
  an added delay for count cleanup.
- A non-async `whenComplete` callback registered before the last task can finish observes 0 inside
  the callback. For success its result and exception arguments are both `null`.

### R3 — Publish RuntimeException failure after updating the count

- If a task throws `IllegalStateException("boom")` (representing `RuntimeException`), it stops counting
  before its future is completed exceptionally. Other RuntimeException subtypes use the same rule.
- The last task's pre-registered `whenComplete` callback observes 0 and the original exception;
  bounded `get()` fails with `ExecutionException` whose cause is that original exception.
- Keep the existing `Queued task failed` error logging with the thrown exception. Later submitted
  tasks still execute; the failed task's result stays exceptional.

### R4 — Update the count on the Error path without changing failure semantics

- If a task throws `AssertionError("boom")` (representing `Error`), removal precedes the exceptional
  completion performed by `run`, including the abnormal-exit fallback. Other Error subtypes use
  the same ordering rule.
- The last task's completion callback and a caller observing its exceptional completion see 0.
- Preserve the existing future failure: `IllegalStateException("Queued task ended abnormally")`.
  TD2 does not replace it with the original Error, swallow the Error, or stop subsequent queued work.
- The new deterministic success/RuntimeException tests in R6 are mandatory. A new dedicated Error
  callback test is optional per [api.md](api.md); retain the existing Error regression test and review
  the production ordering on this path. The feature's Error examples specify the contract and do
  not imply that a new Error callback test has run.

### R5 — Preserve queue execution and shutdown contracts

- Accepted tasks run in submission order, with at most one task body executing at any time. A task
  failure does not prevent the next task from running.
- Graceful `close()` lets queued tasks finish, leaves size 0, and rejects subsequent submissions with
  `RejectedExecutionException`.
- With the existing configured shutdown wait (200 ms in `JobQueueTest`), forced shutdown cancels
  unfinished running and waiting futures, does not execute dropped waiting tasks, leaves size 0
  when `close()` returns, and rejects subsequent submissions.
- Interrupted `close()` preserves the caller's interrupt flag and cancels unfinished work as before.
- Existing public signatures, configuration/defaults and logging behaviour stay unchanged. No new
  timing guarantee is imposed on callbacks during shutdown cancellation or on futures explicitly
  completed/cancelled by callers. Only completion published by `JobQueue.run` is strengthened.

### R6 — Supply deterministic regression proof

- Add Developer-owned unit tests in `JobQueueTest` for the last task succeeding and for the last task
  throwing a RuntimeException. Both must fail against the pre-fix production code and pass with the fix.
- Hold an earlier task behind a latch so the last task cannot finish before a non-async `whenComplete`
  callback is registered. Register first, then release. Use the same controlled setup for both outcomes.
- Read `queue.size()` inside that callback; retain the observed value in a safely published holder
  initialized to a nonzero sentinel such as -1. Assert that the observed value is 0.
- Separately await callback completion with a bounded wait, because completion of the original future
  does not itself prove that the callback finished. Assert latch waits succeed; do not silently proceed
  after a timeout. Catch/assert the expected exceptional result in the failure case.
- Use bounded synchronization for coordination; do not use sleeps, polling for eventual size 0,
  `whenCompleteAsync`, a following sentinel task, or repeated attempts as the new regression proof.
  A following task could hide this race by letting the preceding cleanup finish.

### R7 — Preserve the original regression and record verification

- Leave `sizeCountsWaitingAndRunningTasksUntilTheyFinish` unchanged, including its immediate final
  zero assertion. Do not replace it with a bounded wait for the count.
- Run that original method 200 consecutive times in a one-off local loop after the fix. Every run
  passes; record the command and results. Keep no loop, repeated-test annotation or retry in the code.
- Run `./mvnw test -Dtest=JobQueueTest`, then `./mvnw verify`. Before commit the project's
  `./mvnw clean verify` gate also applies: no skipped checks, at least 90% line and 90% branch coverage
  independently from unit tests, Spotless, SpotBugs and PMD passing.
- No new TD2 functional `*IT` or Cucumber step implementations. The scenarios in
  [TD2-job-queue.feature](../../src/features/TD2-job-queue.feature) document the queue caller contract
  and map to unit evidence. Tag them `@TD2 @unit_queue` and exclude `@TD2` from the functional runner,
  retaining the existing `@T8` exclusion. Tester owns that runner change; approval is recorded in
  [A: Q-analyst-1](comms/questions.md).

## Evidence map and commands

| Requirement | Evidence expected from Developer / Tester |
|---|---|
| R1 | Existing unchanged size test; controlled remaining-task count check if needed |
| R2, R3, R6 | New pre-registered callback unit tests, with red/green evidence; caller get assertions |
| R4 | Existing Error regression plus production-order review; dedicated callback test optional |
| R5 | Existing FIFO, no-overlap, failure-continuation, close/cancellation/interrupt tests |
| R7 | Original method diff unchanged; 200-run log; focused/full Maven logs; Tester runner exclusion |

Run from the repository root. Example one-off repetition (stop at the first failing run):

```sh
for run in {1..200}; do
  ./mvnw -q test -Dtest=JobQueueTest#sizeCountsWaitingAndRunningTasksUntilTheyFinish || exit 1
done
./mvnw test -Dtest=JobQueueTest
./mvnw verify
# Project gate before commit:
./mvnw clean verify
```

Record evidence in TD2 working files/comms. This requirements document supplies acceptance criteria;
it does not claim implementation, red/green execution, the 200-run check or Maven verification.

## Out of scope

- T11 queue-position/status messages, pipeline wiring, Telegram, Whisper or Ollama calls.
- Functional acceptance wiring or new `*IT` classes for TD2; feature selection is the approved exception.
- Changes to `close()`/`abandonUnfinished`, cancellation ordering or caller-mutated future semantics.
- Executor replacement, parallel jobs, fairness, priorities, quotas, capacity or a new public API.
- Changing job storage, retention, media, dependencies, test thresholds or unrelated TD1 debt.
