# T16 brief

Owner's original words: "Начать T16".

Existing state found at intake: the JaCoCo gate (90% line and branch, unit tests only) is already in `pom.xml`; failures during a job already map to `SERVICE_UNREACHABLE` ("The transcription server is unreachable.") and `SUMMARY_UNREACHABLE` ("The summary server is unreachable."); what is new is a check before work starts and the missing failure-path tests.

Questions and answers:
- Task type: product (owner's choice): Analyst, Architect, Developer, Tester, both reviewers.
- When to check Whisper and Ollama: at startup (log only, the bot starts anyway) and before every job (before any download); no periodic check. Owner's choice.

Decisions made by the orchestrator and shown to the owner (no objection): the existing message texts are reused; the per-job check does not block other users or the queue; a service going down mid-job keeps the T9/T10 behaviour; deferred jobs and the 2-hour retry stay in T18.

Agreed task: see T16 in `tasks/todo.md`.

Owner decision on Q-analyst-2 (2026-11-22): the per-job probes run on the processing worker right before execution (after queueing); a down service delays the next queued job by at most 4 seconds. This supersedes "does not block the queue" above. See tasks/T16/comms/questions.md.
