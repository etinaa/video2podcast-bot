# TD13 brief: Spring AI cleanup

**Type:** Technical task. Roles: Architect, Developer, reviewer-claude, reviewer-codex. No Analyst, no Gherkin scenarios, no Tester, no `src/it` tests. The design review is the Developer's and the orchestrator's. The Developer collects the evidence; the orchestrator writes it to `tasks/TD13/evidence.md`.

## Owner's words

> write task with improvements

(Said after the orchestrator's review and simplification pass over TD12, commit `c5e0e3a`, which proposed two improvements.)

## Background: the two improvements from the TD12 review

1. **Plain `spring-ai-ollama` instead of the starter.** The starter brings 8 autoconfigure modules, and `application.yml` excludes all 11 of their auto-configurations, while `SummaryConfig` builds `OllamaApi` and `OllamaChatModel` itself. Depending on `spring-ai-ollama` alone removes the exclusion block and the risk that a future Spring AI version adds an auto-configuration not on the list. `SpringAiWiringTest` stays as the guard. This departs from the TD12 Acceptance, which named the starter; the owner approved the departure.
2. **No dead interrupt branch in `OllamaLlmClient.failure()`.** The call runs on a virtual thread from `CALLS`. An `InterruptedException` in the `ExecutionException` cause chain was raised in that thread, yet `failure()` calls `Thread.currentThread().interrupt()` on the caller (the single job worker), which can break the next blocking call, for example `HealthChecks.await()`. The only interrupt of the call thread is `call.cancel(true)`, after which the result is never read, so the branch can't be reached in practice. The test `anInterruptionIsAnOtherFailureAndKeepsTheFlag` locks the wrong behaviour in and is changed.

Considered and left out: shared helpers for the connect timeout, the trailing-slash trim and `abbreviate()` (they would touch `WhisperClient`); one `HttpClient` shared by the probe and the chat model (it would change the T16 health check).

## Questions and answers

- **Q (orchestrator):** Product or technical task? Recommended: technical. **A (owner):** Technical.
- **Q (orchestrator):** Add TD13 to `tasks/todo.md` as drafted? **A (owner):** Yes, as shown.

## Agreed task

See TD13 in `tasks/todo.md` (Backlog).
