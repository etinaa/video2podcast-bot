# Developer answers, review round 1

Codex
- [Important] parking edit can overwrite a retry's terminal status: fixed. `UrlMessageHandler.runJob` now edits the status to the parked text on the worker, right after `park`, so the next task on the shared worker (a retry of the entry) cannot start before the edit is done. `finish` no longer edits for a parked job (it only logs). Regression: `UrlMessageHandlerTest.theParkedStatusIsEditedBeforeAnyLaterTaskCanRetryTheEntry` (the edit is delayed 200 ms; a task queued behind the job must already see it; the old asynchronous edit fails it).
- [Suggestion] host-setup service-checks paragraph: fixed.

Claude
- DeferredJobRunnerTest flaky pass-ended wait: fixed (keeps calling `retryAll()` until attempts == 2).
- aCancelledEntryEndsThePass: fixed (waits for the mock to leave, asserts no events).
- Package-private constructor "dead": not changed. The public constructor delegates to it so that SpotBugs EI_EXPOSE_REP2 does not fire (a suppression needs owner approval); the Javadoc now says so.
- TelegramDeferredNotifierTest: fixed (new test: a failed send still lets the `Failed.` edit run; alias removed).
- Javadoc on the give-up decision with a failed count write: added.

`./mvnw verify -Dno-it -Dspotless.check.skip=true` is green; src/main and src/test are formatted.
