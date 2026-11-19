# T11 tester design review

Date: 2026-11-19. Step 1 only. Verdict: APPROVED.

Reviewed the tester/common role rules, CLAUDE.md, SPEC.md, tasks/plan.md,
T11 in tasks/todo.md, all T11 design and analyst artifacts, the append-only
question handoff, and T8/T9/T10/TD2/T11 features against the existing functional
harness and storage/media boundaries. Q-analyst-1 and Q-architect-check are
answered and incorporated. No open contract or scenario question remains.

## Testability mapping

| Scenarios / requirements | External setup and evidence | Contract |
|---|---|---|
| Access, nonprivate chats, non-link text, user/chat association (R1) | Telegram updates and request journal; no downstream requests or new directories; inspect stored JSON | F2/F7, handler dispatch and JobRequest |
| Prompt acknowledgement, busy commands, initial positions and inherited TD2 (R2) | Hold Whisper response, send subsequent updates, observe queued replies within five seconds; wait for Done. or failure sentence before testing a finished queue | F1/F5/F6, exact status table |
| Ordered same-message progress and FIFO (R3/R4) | Distinct Telegram message ids, cross-server event recording, held responses, per-job media URLs; inspect Whisper chunk order and summary order | JobProgress, F1/F6 |
| HTML escaping, title whitespace/length, full summary budget and buttons (R5/R6) | Media-page metadata and Ollama HTTP responses; parse outbound HTML/entities and keyboard JSON; inspect unchanged stored title and absence of uploads/delivery files | Formatter, publisher, Keyboards |
| Stored files and retention, delayed publication, configured retention (R7) | Filesystem/JSON inspection, source hashes, primary mutable Clock, separate context for configuration overrides, held Ollama response | Pipeline steps 7–8 and test seams |
| Processing failures, cleanup isolation, continuation (R8) | Failure-only process fixtures, Whisper/Ollama HTTP failures/timeouts, safe Telegram errors and filesystem snapshots | F3 and unchanged per-stage failures |
| Initial/progress/summary/terminal Telegram rejection (R8) | Selective HTTP stubs by text/message id; count attempts and wait for completion/next-job success | F4/F5 |
| Initial persistence failure and post-send persistence failure, including rejected withdrawal (R8) | Hold Ollama or Telegram response; place a nonempty directory at job.json; inspect cleanup and withdrawal request with unchanged HTML and empty keyboard | F4, SummaryPublisher.withdraw; update read failure or empty result is terminal |
| Inherited T8/T9/T10 acceptance (R9) | Real yt-dlp/ffmpeg via local media proxy on success; process fakes only for failures/explicit argument checks; HTTP transcript/map/reduce fixtures and request assertions | Existing component contracts; T11 pipeline and test seams |
| Runner and verification gates (R9/R10) | Later enable every nonmanual feature with filter not @manual_media; add SummaryFlowIT; run authorized Maven verification | Brief and tester Step 2/3 rules |

## Implementation notes for Step 2

- Reuse the existing Telegram entry point and step expressions; share scenario
  state rather than registering duplicate private/group-message steps. Steps
  must not call pipeline, queue, store or other processing services directly.
- Use distinct status and summary ids, including summary id 701 where specified;
  the current shared id-77 response cannot prove message identity.
- Held responses provide synchronization for temporary WAV/chunk assertions and
  disk obstacles. Release all holds and finish outstanding jobs before resetting
  shared WireMock state or changing the clock between scenarios.
- Configuration values consumed at construction need isolated contexts, not
  environment changes after startup. The documented primary Clock seam supports
  exact retention assertions without wall-clock sleeps.
- Cross-server ordering assertions need a common test event recorder; independent
  request-list order or equal-resolution timestamps alone do not establish order.
- Generate success fixtures with real ffmpeg, route real yt-dlp through the local
  proxy without external DNS, and keep combined-MP4 @TD5 coverage enabled.
- Ollama success responses need prompt counts for the complete request; do not
  reuse the captured count of 71 for arbitrary transcript fixtures.
- Wait for final Done. for successful TD2 completion, not summary publication.
  Queue-release assertions and negative assertions must observe terminal work,
  rather than pass while an asynchronous job has not started yet.
- Cleanup permission failures, shutdown, atomic storage and precise surrogate
  clipping boundaries remain Developer unit tests as already agreed.

This is a static design/testability review, not executable acceptance evidence.
No tests, feature files, production files, runner configuration or git index were
changed. Test implementation waits for the orchestrator's Step 2 instruction.
