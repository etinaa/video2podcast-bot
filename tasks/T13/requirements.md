# T13 requirements — Audio preparation and Download audio

Status: analyst handoff aligned with Architect answers Q-analyst-1/2 and [api.md](api.md), 2026-11-19. Sources: SPEC.md (Audio Delivery, User Interaction, limits), tasks/todo.md T13, tasks/T13/brief.md, T12 delivery contracts and D11. This document records requirements and acceptance obligations, not implementation approval or passing runtime tests.

## Objective and scope

An allowed job owner requests retained audio through `audio:<jobId>` and receives one playable Telegram audio upload, prepared only on demand through the existing single-worker queue. Transcript delivery stays independent. Success is observable through Telegram HTTP requests, retained files and job.json.

Assumptions inherited from approved scope: cloud Bot API; maximum accepted video duration 10,800 seconds; one-hour stored retention from summary publication; existing per-file delivery states and owner checks; real ffmpeg for successful acceptance paths. The Architect has settled the upload byte constant, metadata fallback, retry calculation and queued-expiry behavior below.

## Requirements and checkable acceptance criteria

### R1 — Authorize the audio callback and use stored destinations

- Apply the existing authorization gate: private chats only; a revoked/unknown user's callback receives `No access.` and performs no audio preparation or upload. Group, supergroup and channel callbacks are ignored.
- Only stored ownerUserId may request a job's audio. Another allowed user, including the bot administrator when not that job's owner, receives `This button is not for you.` with no file/state/keyboard changes.
- Upload and keyboard targets use stored chatId and summaryMessageId; callback envelope ids cannot redirect them. Missing summaryMessageId does not prevent delivery and causes no keyboard edit.
- Callback ids are validated by the existing job-id contract. Invalid/path-traversal/missing ids give the expiry notice without accessing files outside that job directory.

### R2 — Preserve a sendable source under the size limit

- For a source with case-insensitive filename extension MP3 or M4A (no content sniffing) of size at or below 52,428,800 bytes (50 MiB), upload identical source bytes without encoding, resampling or metadata rewriting. The Developer's unit test must assert the returned Path is the same source Path; identical bytes alone cannot establish that API contract.
- Include MP3 and M4A, a size below the limit, and the exact threshold. The chosen source threshold is inclusive: 52,428,800 bytes stays unchanged; 52,428,801 bytes enters compression. Uppercase extensions follow the same rule.
- Keep source files unchanged on all preparation branches. No audio preparation, upload or compression notice occurs merely because a summary is published (existing T11 coverage).

### R3 — Convert other small formats, including combined video

- A source at or below the limit in another format becomes AAC audio in an M4A container at a requested 128 kbps. This is format conversion, not size-driven compression; the user is told that distinction.
- A retained combined `source.mp4` from TD5 is in this branch even when its audio codec is AAC. Upload an audio-only M4A with no video stream, not the MP4 renamed or uploaded unchanged.
- Converted output must be nonempty and strictly smaller than 52,428,800 bytes before any sendAudio request. If the 128 kbps output is equal to or above that limit, continue with the mono compression path in R4 using the original source. Report COMPRESSED and the final bitrate, not CONVERTED. Conversion preserves the input channel count; combined MP4 output has no video. Do not upload a missing/empty output.

### R4 — Compress oversized sources using their duration and verify the result

- Every source over the limit, regardless of extension, is encoded as mono AAC/M4A. Target bytes are explicitly `48 * 1024 * 1024 = 50,331,648`; target audio bits per second are `targetBytes * 8 / durationSeconds`. In kbps divide by 1,000 as well. For 10,800 seconds the unrounded result is 37.282702... kbps (about 37 kbps). Choose `kbps = clamp(floor(50,331,648 * 8 / max(durationSeconds, 1) / 1000), 8, 128)`. Thus 3 hours gives 37 kbps, 1 hour gives 111 kbps, and 60 seconds clamps to 128 kbps.
- Verify actual output bytes after encoding; a successful ffmpeg exit alone does not prove a valid upload. Container overhead and encoder behavior mean the target is not a size guarantee.
- Accept encoded output only when nonempty and strictly below 52,428,800 bytes. If the first compressed output is equal to or above that limit, choose `lower = max(8, floor(kbps * 0.8))` and retry once when lower < kbps (37 → 29; 128 → 102). Verify again. At the 8 kbps floor there is no lower retry; fail. No third compression encode and no oversized upload. An initial 128 kbps format conversion may precede these two compression attempts.
- A second oversized output, an encoding error/timeout, or absent/empty output gives a safe failure and no audio upload, with manual retry under R8. A persisted duration <= 0 is treated as 1 second, yielding 128 kbps after clamping; it does not fail merely because of the duration. Exact arithmetic and floor cases are unit-test obligations.
- The 3-hour calculation is unit-tested without a real 3-hour encode. A shorter valid oversized media fixture exercises real compression in acceptance tests.

### R5 — Queue audio preparation and prevent duplicate delivery

- The first eligible tap claims AUDIO `AVAILABLE → SENDING` before another tap can win. Acknowledge with `Preparing audio…` and display that audio button while waiting/running. Keep null sentAt until successful sendAudio.
- Audio preparation and delivery run through the same FIFO single-worker queue as link processing. With another job observably blocked at Whisper, the audio tap is acknowledged but no audio output/upload occurs until that earlier job completes. The Telegram update thread remains available to answer a second tap.
- A queued/running/uploading audio tap receives `Already being sent.`; after a successful upload, further taps receive `Already sent.`. Concurrent first taps yield only one audio upload.
- Successful sendAudio is followed by AUDIO `SENT` and sentAt from the application clock. Failure never marks SENT. Do not automatically retry uploads, including ambiguous connection loss after upload bytes were received; this inherits T12's manual retry semantics rather than promising delivery across crashes.
- Existing persisted SENT/SENDING records behave the same after startup. T14 owns SENDING recovery. An AUDIO claim granted before expiry finishes even after expiry passes while queued; do not recheck the clock to cancel that claimed delivery. A new AVAILABLE request at/after expiry is refused. T14 must protect directories with SENDING files from sweeping.

### R6 — Send playable audio with honest metadata and preparation information

- Use multipart `sendAudio`, not sendDocument/sendVoice/sendVideo, to stored chatId. Upload a safe `<sanitised title>.<extension>` filename using T12 filename rules (forbidden/control characters replaced, stripped stem capped at 100 UTF-16 units without splitting a surrogate pair), with fallback stem `audio`; keep the unchanged source extension or use `m4a` for converted/compressed output. Selection is extension-based, not content sniffing. Set title to the stored job title truncated to 256 UTF-16 units without splitting a surrogate pair; performer is always `Video2Podcast`. Do not add uploader metadata to job.json.
- The user sees whether bytes were unchanged, converted at 128 kbps without size-driven compression, or compressed to mono AAC at the final chosen bitrate. A successful retry reports the retry bitrate, not the first attempt's bitrate.
- Put preparation information in the sendAudio HTML caption, exactly: unchanged → `Original audio, not compressed.`; converted → `Converted to M4A, 128 kbps.`; compressed → `Compressed to mono AAC, <kbps> kbps, to fit Telegram's 50 MB limit.`. These captions contain no interpolated title or characters needing HTML escaping. The gateway gains explicit filename/caption parameters per api.md.
- Treat title/metadata safely for the chosen Telegram representation. Literal `R&D <notes>` stays metadata, and HTML text/captions must not interpret it as markup. Never disclose filesystem paths, process stderr, tokens or exception text.
- Audio delivery uses retained files; it does not redownload media or call Whisper/Ollama.

### R7 — Keep buttons and files independent

- Replace only the summary keyboard, never its title/bullets. In both progress and completion edits, audio remains first, transcript second, and callback data stays `audio:<jobId>` / `transcript:<jobId>`.
- Success label is `Audio sent`. Use the claim record for the first keyboard edit and the record returned by successful completion for the final edit; preserve that stored transcript state and its matching label (`Download transcript`, `Sending transcript…`, `Transcript sent`, `Transcript expired`) when audio changes. A transcript delivered while audio waits must retain its `Transcript sent` label in the final audio edit. Arbitrary Telegram edit interleavings beyond these snapshot contracts are not a stronger synchronization guarantee.
- Audio delivery does not send, release or alter the transcript entry/bytes. Transcript can be requested first, during queued audio, or after audio success. Races in snapshot acquisition are unit-tested; held external HTTP responses provide integration evidence.
- Keep source, transcript and job.json after audio success/failure. Immediate deletion when both files are delivered, expiry sweeping and expired-keyboard sweeping belong to T14. Do not extend either expiresAt on a tap or retry. A generated `out.m4a` is temporary: delete it best effort after success/failure, including a rejected upload; never delete the retained source as part of this cleanup. Failed encoding must not leave a partial output behind.

### R8 — Handle expiry, preparation/upload errors and auxiliary failures

- Enforce existing T12 claim precedence: missing/unreadable job → expired; for an existing job, ownership before state; SENT → `Already sent.`, SENDING → `Already being sent.`, EXPIRED/missing AUDIO → `Expired, please resend the link.`; AVAILABLE with now >= stored AUDIO expiresAt → expired. A timestamp even one nanosecond ahead is eligible. Use per-file expiry, not createdAt or a new hour.
- An AVAILABLE entry whose source file is absent yields expiry and marks AUDIO EXPIRED under the existing T12 contract. Otherwise refusal leaves files/state unchanged. Do not introduce sweeping or deletion on a refused tap.
- Preparation failure or rejected/transport-failed sendAudio releases AUDIO to AVAILABLE, restores `Download audio` best effort, retains source/transcript and notifies stored chatId safely that audio could not be prepared/sent and can be requested again. Send exactly `Could not send the audio. Tap the button to try again.` as a best-effort message to stored chatId; no automatic upload retry. Queue submission rejection follows the same release/restore/notice behavior. An explicit later tap may succeed.
- Failed callback answers and keyboard edits are best effort, with one attempt per operation; neither prevents an eligible audio upload nor reverses successful SENT persistence. The success information travels in the audio caption; a failed final keyboard edit must not release the audio claim.
- If the file was uploaded but SENT persistence fails, retain SENDING, log and do not upload again/release/send a misleading upload-failure message. Store failure before claim gives `Something went wrong, please try again.` and no queue work/upload. Release/logging/storage fault details stay in unit tests unless the Tester has a portable external fixture.

### R9 — Verification and role boundaries

- Scenarios in `src/features/T13-audio-button.feature` use Telegram updates, external HTTP mocks and disk fixtures/assertions. No step may invoke or name a production class/method.
- Success encoding paths use real ffmpeg; source fixture generation may use ffmpeg and controlled padding of a valid container to cross the size threshold. Use short fixtures, verify they decode; do not spend three hours on a test. Fake process results are allowed only for deterministic impossible-to-guarantee real failures (oversize encoded outputs, timeout, missing output).
- Developer supplies AudioPreparerTest and AudioButtonTest, including same-Path identity, exact byte boundaries, 10,800-second bitrate, retry bound, process timeout and failure mapping, concurrency, safe queue rejection and persistence faults. Tester supplies AudioButtonIT and Cucumber steps. No acceptance scenario uses an internal service as its action.
- Orchestrator alone serializes and runs `./mvnw clean verify` then `./mvnw verify -Pmutation`, checks exit codes before commits; retain 90% unit line/branch coverage and >=85% mutation score. No new SpotBugs/PMD suppression without owner approval. Analyst does not run these gates or edit tests/implementation/task status.

## Acceptance mapping

| Requirement | Evidence |
|---|---|
| R1 | owner/admin/revocation/group/redirect/id scenarios |
| R2 | real MP3/M4A unchanged multipart bytes; exact byte/Path unit cases |
| R3 | real WebM and combined MP4 → audio-only AAC/M4A |
| R4 | real oversized source; controlled retry/oversize/failure cases; exact 3-hour unit calculation |
| R5 | held Whisper response proves shared queue ordering; held upload/concurrent taps; job.json state |
| R6 | multipart metadata, Telegram preparation notice, no unrelated HTTP requests |
| R7 | both orderings of transcript delivery; retained files and keyboard state matrix |
| R8 | expiry matrix/missing content; failure then manual retry; notification failure cases; unit I/O faults |
| R9 | Tester execution report and orchestrator gates |

## Out of scope

T14 expiry sweeper, removal after both deliveries, restart recovery of SENDING; T17 real 3-hour smoke test; new uploader extraction/schema changes unless explicitly agreed; local Bot API server, database, multipart splitting, downloadable hosting, new configuration, access-policy changes and broad refactoring. Existing >3-hour rejection stays in T8/T11.

## Resolved contract questions and handoff

Q-analyst-1 and Q-analyst-2 are answered in append-only [comms/questions.md](comms/questions.md) and [api.md](api.md). The requirements and feature now reflect those answers, including treating duration <= 0 as 1 second and using claim/completion record snapshots for transcript buttons. No outstanding analyst contract question. Exact arithmetic, 8 kbps floor, surrogate-boundary truncation, queue submission rejection and low-level storage failures remain Developer unit-test obligations. The Gherkin contains external coverage for byte boundaries, conversion overflow, captions/performer, temporary-output cleanup and queued expiry. This handoff does not claim future implementation or acceptance tests pass.
