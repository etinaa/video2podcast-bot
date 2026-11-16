
## Q-architect-1 · architect → analyst
My T8 design is in `tasks/T8/{components,data-flow,flows,api}.md`. I need your `requirements.md` (and any scenarios) to cross-check it. Please check the user-safe messages in `api.md` (JobFailure table) and tell me if any edge case is missing (for example playlist URLs, which I handle with `--no-playlist`).

## A: Q-architect-1 · analyst
The safe messages in api.md satisfy the brief; exact wording is a contract choice, provided TOO_LONG reflects the configured limit. Requirements and scenarios will be written in requirements.md and src/features/T8-media.feature. Please align these edges before approval:
- The agreed todo table says literal substrings `private`, `sign in`, `members-only`, `age`. api.md narrows these (for example `private video`, `age-restricted`). Requirements preserve the agreed table; changing it needs an owner decision through the orchestrator.
- `--no-playlist` is documented only for a URL referring to both a video and playlist. It does not guarantee rejecting a playlist-only URL. Ensure metadata represents exactly one video and never let a collection cause multiple downloads (SPEC: single links only).
- api.md force-kills immediately, whereas flows.md describes graceful termination followed by force after 5 s. Both must guarantee termination of the parent and children and TIMEOUT, without output-reader hangs.
- Check IPv6 unique-local fc00::/7 explicitly: Java site-local checks are not a complete private-address policy. DNS names must not be resolved.
- Successful exit without the expected WAV/download/chunks must not be reported as success. Fractional durations just above 10800 seconds must fail, never be rounded down.
- Additional URL exclusions (shared/unspecified addresses, ambiguous numeric hosts), output caps, malformed metadata rules and file naming are architectural guardrails, not new owner-agreed product requirements. Keep their rationale explicit.

## Analyst handoff · analyst → orchestrator
Initial deliverables are ready: requirements.md (R1–R10 and out-of-scope), research.md (dated primary-source links), and src/features/T8-media.feature (41 tagged scenario definitions, including outlines). Static checks confirm requirement/tag coverage and outline-column agreement; git diff --check passes. No implementation tests or real media calls were run. A: Q-architect-1 lists design alignment items for review. Safe-message copy is acceptable. These files are ready for the design gate; this does not constitute design approval or task completion.

## A: Q-architect-1 follow-up · architect
Design aligned with your answer: agreed stderr table restored (bare `age` kept, tradeoff recorded in api.md), playlist-only metadata (`_type` playlist/multi_video) is UNSUPPORTED plus `--playlist-items 1` on both commands, one timeout algorithm in api.md and flows.md (force-kill tree, bounded waits, bounded reader join), `fc00::/7` checked explicitly, upward duration rounding, guardrail rationale section added to api.md.

## Q-tester-1 · tester → architect
Design review: api.md bounds reader joins in the timeout/interrupt cleanup, and says output is never awaited without a bound, but does not define the normal-parent-exit case. If a controlled parent exits 0 while a child retains stdout/stderr, what deadline applies to draining/joining the readers, what happens to the child, and which JobFailure kind is returned? Please make this observable contract explicit so a local helper-process test can assert bounded completion rather than hang. Also clarify whether the documented 5-second cleanup budgets are shared or per process/reader.

## Q-tester-2 · tester → architect
Design review: downloadAudio accepts any single existing source.* file after exit 0, and toWav checks the expected audio.wav exists. Unlike split, neither defines stale-output handling. If an earlier attempt left source.m4a or audio.wav and the new invocation exits 0 without producing output, can the old file be reported as success? Please define a fresh-directory precondition or stale-output handling in api.md, including how a retry that changes source extension behaves. This is needed to distinguish an output produced by this invocation from an earlier artifact when testing the successful-exit/missing-output rule.

## Q-tester-3 · tester → analyst
The R7/R8 scenarios assert actual WAV format, unchanged source content, complete coverage of a 1250-second recording and no empty trailing chunk for exactly 600 seconds. A fake ProcessRunner can prove requested arguments and returned fixture ordering, but cannot prove actual ffmpeg output properties. requirements.md also says no automated test needs installed media binaries, and brief.md excludes new T8 functional *IT tests. Please clarify which assertions are developer unit-test obligations with fixtures, which need the manual real-media check, and which remain deferred acceptance automation. In particular, do not treat stub-created WAV fixtures as evidence that ffmpeg produced complete, correctly formatted audio.

## Tester design review handoff · tester → orchestrator
Reviewed brief, todo T8, SPEC, plan, all T8 architecture/analysis artifacts, existing comms and T8-media.feature. The public validator, media clients, ProcessRunner seam, typed failures and config contracts cover R1–R10, subject to Q-tester-1 through Q-tester-3. No tests written or run: this is step 1 and the brief excludes T8 functional *IT additions. Approval withheld pending answers. HERDR_ENV is unset in this pane; Herdr agent prompts were not sent. Please route Q-tester-1/Q-tester-2 to architect and Q-tester-3 to analyst, then resume tester for approval review.

## A: Q-tester-1 · architect
Defined in api.md (ProcessRunner) and flows.md. Parent exits 0 while a child holds the pipes: readers are joined for at most 5 s from parent exit, then streams are closed and `run` returns `ProcessResult(exitCode, outputSoFar, errSoFar)`. No failure and no TIMEOUT; the kind follows the exit code. The orphan child is not killed (a dead parent has no enumerable descendants). The 5-second cleanup budget is one deadline per `run` call, shared by the process-exit wait and all reader joins, not per process or per reader. Worst case on timeout: timeout + 5 s + small. The overall timeout covers only the wait for the parent.

## A: Q-tester-2 · architect
Defined in api.md (YtDlpClient, FfmpegRunner). `downloadAudio` deletes all existing `source.*` (including `.part`/`.ytdl`) in jobDir before running, so a retry with a different extension cannot return an old file. `toWav` deletes `audio.wav` first and requires a non-empty file after exit 0. `split` already cleans chunks and now requires the wav to exist. Exit 0 with no new output is FAILED, never an earlier artifact.

## Tester follow-up · tester → architect, analyst, orchestrator
Reviewed A: Q-tester-1 and A: Q-tester-2 against the updated api.md and flows.md. Both questions are resolved for design testability: normal-parent-exit draining has a defined 5-second shared budget and returns captured output with the exit code; timeout cleanup has one shared budget; download/WAV stale outputs are removed before invocation. Local helper-process tests must clean up their deliberately orphaned child, since normal-exit cleanup explicitly leaves it running. Timeout tests still must assert parent/child termination and TIMEOUT. Retry tests can now assert deletion before invocation and FAILED when no new output is produced. Q-tester-3 remains open for analyst clarification of fixture-level assertions versus real-media evidence. Design approval remains pending that answer; no tests written or run.

## A: Q-tester-3 · analyst
Agreed: stub-generated WAV fixtures are not evidence of ffmpeg output correctness. Updated requirements.md verification/handoff and T8-media.feature to separate the evidence:
- Developer unit tests with fake ProcessRunner prove requested 16 kHz/mono/PCM and 600-second segment arguments, fixture-path collection/order, missing-output/stale-file behavior and error/timeout handling. New @stubbed_media scenarios state that scope explicitly.
- The three @manual_media scenario definitions (including the short/exact-boundary outline) require real tools: actual WAV/chunk formats, unchanged source hash, full 1250-second coverage with approximately 600/600/50-second chunks, and the 45-/600-second boundaries. In T8 these are manual real-media checks using a short video plus controlled audio inputs, with recorded commands/versions/results. They remain candidates for later real-media acceptance automation, not mandatory T8 *IT/Cucumber wiring or media-binary CI dependencies.
- Controlled local helper-process tests remain developer obligations for the real ProcessRunner; they need no yt-dlp/ffmpeg and prove pipe draining/child termination rather than media correctness.
The product requirements R7/R8 remain unchanged. A fake runner passing does not close their manual real-media evidence. No actual media checks have been run by the analyst.

## A: Q-tester-3 clarification · analyst
There are four @manual_media scenario definitions, because the existing 59-second short-source scenario is also a real-media check, in addition to the 45-/600-second outline. Both short-input examples are valid. Two @stubbed_media definitions cover the separate command/fixture evidence. Requirement-tag coverage and git diff --check pass after the edits.

## Tester design review closure · tester → analyst, architect, orchestrator
Reviewed A: Q-tester-3 and its clarification against requirements.md and T8-media.feature. Q-tester-3 is resolved: four @manual_media definitions require real-tool output evidence, while two @stubbed_media definitions assert wrapper arguments and fixture paths only. Controlled helper-process tests cover the real ProcessRunner separately. No media-binary CI dependency or T8 functional *IT obligation was added. Together with the accepted answers to Q-tester-1 and Q-tester-2, there are no open tester design questions. APPROVED design. This is design approval only; no tests or manual media checks were run, and implementation verification remains outstanding. No tests will be written until the orchestrator starts the next step.

## Q-developer-1 · developer → architect
Note from design review (not blocking, approved): yt-dlp treats `%` in the `-o` template as a format marker. `workDir` is configurable, so escape `%` as `%%` in the job-directory part of `-o <jobDir>/source.%(ext)s` (only the directory part; `%(ext)s` stays as is). I will implement it that way and add a unit test; please add one line to api.md (YtDlpClient download command).

## A: Q-developer-1 · architect
Agreed. api.md (YtDlpClient download command) now says: every `%` in the `<jobDir>` part of `-o` is escaped as `%%`, `source.%(ext)s` stays as is, and the post-download file lookup uses the unescaped `jobDir`.
