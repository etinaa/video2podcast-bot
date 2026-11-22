# T12 requirements — Download transcript

Date: 2026-11-19. Sources: [SPEC.md](../../SPEC.md), T12 in [todo.md](../todo.md), [brief.md](brief.md), [plan.md](../plan.md), and the Architect's [api.md](api.md), [flows.md](flows.md) and [answers](comms/questions.md). Requirement ids are local to T12.

## Objective and inherited limits

An allowed user receives the already-generated English transcript only after tapping the transcript button for their own job. This slice uses T11's persisted job and existing Telegram gateway; it does not repeat media download, transcription or summarization. Private chats, allowlist checks, safe HTML and validated job ids remain mandatory. The existing 3-hour media limit, 1500-character summary limit and default one-hour retention remain unchanged; delivery uses the stored per-file expiresAt, including a configured nondefault retention.

## Requirements and acceptance criteria

### R1 — Gate transcript callbacks by access and job ownership

- Route `transcript:<jobId>` callbacks after the private-chat and current allowlist checks. Unknown/revoked users receive `No access.` through answerCallbackQuery, with no upload, keyboard edit or file mutation. Group/channel updates cause no Telegram calls or file mutation.
- A different allowed user, including the bot administrator if not this job's owner, receives `This button is not for you.` No upload, keyboard edit or job mutation occurs. Ownership uses callback.from.id and stored ownerUserId, never chat id or username.
- All delivery destinations and keyboard targets come from stored chatId/summaryMessageId. Per the Architect's contract, a matching callback chat/message id is not an additional authorization condition; an altered private-message envelope cannot redirect the document.

### R2 — Upload the retained transcript with a safe filename

- A valid tap on an AVAILABLE, unexpired transcript sends exactly one multipart sendDocument request to stored chatId, containing the existing transcript.txt bytes unchanged, no caption, and filename `<title>.txt` derived from the stored original title.
- Normal title `Budget meeting` produces `Budget meeting.txt`. Replace each `\ / : * ? " < > |` and control character with `_`, strip surrounding whitespace, truncate the stem to at most 100 UTF-16 units without splitting a surrogate pair, use `transcript` if the stem is empty, then append `.txt`. This changes the upload name only, not the stored title or transcript path/content.
- No sendAudio, Whisper or Ollama request occurs for a transcript tap. Delivery does not require audio delivery first.

### R3 — Persist the one-send lifecycle

- Claim AVAILABLE → SENDING before upload. While Telegram holds its upload response, job.json exposes SENDING and null sentAt. On confirmed success persist SENT and a nonnull sentAt from the application clock, no earlier than the upload success.
- A second tap after success receives `Already sent.` and produces no second upload or state change. This also holds after reload of a persisted SENT job and after its expiresAt has passed: SENT takes precedence over elapsed retention while job.json remains present.
- Concurrent taps on an available job yield one upload only. A loser observing SENDING receives `Already being sent.`; a loser observing completed SENT receives `Already sent.`. A held-upload fixture can deterministically prove the SENDING case.
- Leave the AUDIO entry, title, ownership, summaryMessageId, creation time and expiresAt values unchanged. Keep source audio, transcript.txt and job.json on disk; cleanup belongs to T14.
- The guarantee covers duplicate/concurrent taps during a successful send lifecycle. Telegram does not expose an idempotency key here: an ambiguous network failure followed by a manual retry may deliver twice. Do not claim end-to-end exactly-once delivery across transport failures or T14 restart recovery.

### R4 — Show delivery progress without changing the summary

- For a granted tap, attempt answerCallbackQuery with `Sending transcript…`, editMessageReplyMarkup on the stored summary to `Sending transcript…`, upload, then editMessageReplyMarkup to `Transcript sent` after SENT persistence.
- Keep the HTML summary text untouched. Only the reply markup changes; no editMessageText is used for delivery. The row remains audio first, transcript second, with callback data `audio:<jobId>` and `transcript:<jobId>` unchanged and within 64 UTF-8 bytes.
- Render the audio label from its stored state: AVAILABLE `Download audio`, SENDING `Preparing audio…`, SENT `Audio sent`, EXPIRED `Audio expired`. Transcript labels are AVAILABLE `Download transcript`, SENDING `Sending transcript…`, SENT `Transcript sent`, EXPIRED `Transcript expired`. A SENT label remains protected by state checks even though it still has callback data.
- Skip keyboard edits when summaryMessageId is null; this must not prevent a valid upload. No audio handler is added in T12.

### R5 — Refuse expired, missing and invalid jobs safely

- Answer `Expired, please resend the link.` without upload for a missing job directory, missing/corrupt/unusable job.json, missing TRANSCRIPT entry, EXPIRED transcript, or an AVAILABLE transcript with expiresAt equal to or earlier than now. A tap one instant before expiry is eligible. No sweeper is needed to enforce expiry at tap time.
- A missing transcript.txt also gives the same expired notice without an upload; do not present a manual-send retry for a file that no longer exists. This follows SPEC success criterion 6 and the corrected Architect contract.
- Empty/invalid ids (including path traversal) map to expired without reading or modifying files outside the job directory. A missing job is checked before ownership; for an existing job ownership precedes file-state details. SENT and SENDING take precedence over the elapsed timestamp as specified in api.md.
- A refusal does not edit the summary or delete working files. Refusals leave persisted state unchanged except that an AVAILABLE entry whose transcript file is missing is marked EXPIRED, as clarified by the Architect. Do not introduce sweep/deletion on a refused tap.

### R6 — Allow manual retry after upload failure

- A rejected sendDocument or a transport failure follows the Architect's existing gateway failure contract: release SENDING → AVAILABLE, keep null sentAt, restore `Download transcript` best effort, and send `Could not send the transcript. Tap the button to try again.` to stored chatId, best effort.
- Do not automatically retry uploads. After an explicit rejection, a later owner tap can successfully upload once and reach SENT. Keep retained job/source/transcript files and the AUDIO entry unchanged on failure. Do not expose exceptions, server responses or filesystem paths in Telegram errors.
- Missing-file detection uses R5 rather than this retry path. If disappearance occurs after a successful claim and makes the upload fail, use this release-and-retry path; deterministic between-step race mechanics belong to unit tests.

### R7 — Tolerate notification and persistence failures

- Failed answerCallbackQuery and keyboard edits are logged and ignored; an eligible upload still runs. A failed final keyboard edit does not roll SENT back or permit a duplicate upload. Attempt one callback answer per handled private transcript tap, with no application retry.
- A job-store I/O failure before a claim produces `Something went wrong, please try again.` best effort, no upload and no disclosure of diagnostics.
- If upload succeeds but SENT persistence fails, log the error, leave SENDING, do not release it or send a misleading upload-failure message. Further taps receive `Already being sent.`; crash/startup recovery is deferred to T14.
- If the filesystem refuses release or Telegram refuses the failure notification, handle/log the error without allowing it to escape update processing. Exact low-level persistence and logging branches are unit-test obligations; tests must not call internal services from Gherkin.

### R8 — Use persisted state without regenerating content

- A preexisting unexpired AVAILABLE job on disk can be delivered after application startup with no link resubmission, Whisper/Ollama request or media work. A preexisting SENT job cannot be sent again.
- T14 alignment (2026-11-21): runtime SENDING files remain in progress and return `Already being sent.`. At explicit application startup, T14 resets persisted SENDING to AVAILABLE before cleanup; an unexpired retained transcript then uploads on a fresh owner tap and reaches SENT. Ordinary callback fixtures are installed after startup to represent live runtime state. Corrupt-directory removal and expiry sweeping follow the T14 requirements.
- Delivery uses stored per-file expiry, never createdAt plus one hour or a fresh retention window on tap. Delivery does not extend retention.

### R9 — Verify through the external boundary and retain project gates

- [T12-transcript-button.feature](../../src/features/T12-transcript-button.feature) uses Telegram updates, Telegram/Whisper/Ollama HTTP mocks and job-directory files. Fixtures may write valid preexisting job.json and transcript files directly; steps may not invoke/name production services or methods.
- Tester supplies TranscriptButtonIT and reusable Cucumber steps. Developer supplies TranscriptButtonTest plus state/filename/failure unit coverage. Internal lock/race mechanics, precise surrogate-boundary truncation, storage exceptions and logging branches stay unit-tested when there is no reliable portable external fixture.
- Orchestrator coordinates `./mvnw clean verify` and `./mvnw verify -Pmutation`; unit line/branch coverage is independently at least 90%, mutation score at least 85%. Integration tests do not count toward these gates. Analyst does not run Maven clean/verify or modify the runner/test implementation.

## Acceptance mapping

| Requirement | External evidence / unit boundary |
|---|---|
| R1 | owner/other-user/admin/revoked/group callbacks and redirected envelope scenarios |
| R2 | multipart filename, unchanged UTF-8 bytes, no caption or other service calls; filename outline; surrogate clipping unit tests |
| R3 | held upload, repeated/concurrent taps, persisted state and timestamps, retained files |
| R4 | reply-markup progress/final requests and labels; no text edit; null summary id |
| R5 | expiry boundary matrix, missing/corrupt files, invalid id matrix, unchanged state |
| R6 | rejected upload then explicit retry, held transport failure with no automatic retry |
| R7 | callback/keyboard rejection scenarios; persistence/release/logging unit tests |
| R8 | preloaded AVAILABLE/SENT/SENDING records, elapsed retention precedence |
| R9 | Tester execution reports and Developer/orchestrator quality gates |

## Out of scope

Audio upload/preparation (T13); automatic expiry sweeps, deletion after all files delivered, expired-keyboard sweeping and SENDING startup recovery (T14); broad restart/interruption hardening (T16); live Telegram/model smoke tests (T17). No database, dependency, configuration, schema, access-policy or pipeline changes. Analyst writes these artifacts and comms only, no implementation, test code, commits or task-status edits.

## Handoff

Architecture clarification is recorded in [questions.md](comms/questions.md). The Architect corrected missing-file handling to match SPEC; exact implementation mechanics are in api.md/flows.md. No owner product question is introduced by this analysis. This is a reviewable analyst handoff, not a claim that future code or acceptance tests already pass.
