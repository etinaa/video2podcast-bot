# TD2 acceptance evidence (developer, review round 1)

All commands run from the repo root with `JAVA_HOME=~/.sdkman/candidates/java/current`. Logs are in `target/` (not committed).

## 1. Red: old order in `JobQueue.run` (complete the future, then `open.remove`)
- Command: `./mvnw test -Dtest=JobQueueTest -Dno-it`
- Outcome: BUILD FAILURE, `Tests run: 12, Failures: 2`. Log: `target/td2-red.log`.
- Failing: `sizeIsZeroInsideTheCallbackOfTheLastSucceededTask` (`JobQueueTest.java:105`) and `sizeIsZeroInsideTheCallbackOfTheLastFailedTask` (`:130`), both `Expecting AtomicInteger(1) to have value: 0`. The callback runs on the worker inside `complete`, before `open.remove`, so `size()` still counts the finished task.
- (Earlier, before the fix, the same two tests failed 3 runs out of 3.)

## 2. Green: fixed code restored
- Command: `./mvnw test -Dtest=JobQueueTest -Dno-it`
- Outcome: BUILD SUCCESS, `Tests run: 12, Failures: 0`. Log: `target/td2-green.log`.

## 3. 200-run check of `sizeCountsWaitingAndRunningTasksUntilTheyFinish`
- Temporary `@RepeatedTest(200)` on that test, then the same command: `./mvnw test -Dtest=JobQueueTest -Dno-it`
- Outcome: BUILD SUCCESS, `Tests run: 211, Failures: 0` (200 repeats + 11 other tests). Log: `target/td2-repeat200.log`.
- `@RepeatedTest` removed afterwards (grep count 0).

## 4. Content check
After restoring, `git diff -- src/main src/test` is identical to `tasks/TD2/review/dev-r1.patch` (`diff` printed nothing).

## 5. Suggestions taken after the evidence above (test-only change; `JobQueue.java` unchanged from the reviewed version)
- New `sizeIsZeroInsideTheCallbackOfTheLastTaskThatThrowsAnError` (AssertionError path: callback sees size 0, `get` gives `ExecutionException` with `IllegalStateException "Queued task ended abnormally"`).
- `sizeIsZeroInsideTheCallbackOfTheLastFailedTask` now keeps the thrown exception in a variable and asserts it is the cause of `get` and `isSameAs` the callback's error.
- Red with the old order: `./mvnw test -Dtest=JobQueueTest -Dno-it`, `Tests run: 13, Failures: 3` (all three callback tests). Log: `target/td2-red2.log`. Fixed code restored afterwards.
- `./mvnw spotless:apply`, then `./mvnw verify -Dno-it`: BUILD SUCCESS, `Tests run: 323, Failures: 0`. Log: `target/td2-verify.log`.
- Because of these test changes, the zone diff of `JobQueueTest.java` no longer equals `dev-r1.patch`; the `JobQueue.java` part still does.
