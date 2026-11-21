# T14 requirements — Retention sweeper and restart recovery

Status: analyst handoff aligned with Architect answers A: Q-analyst-1/2 and [api.md](api.md), 2026-11-21. Sources: [brief.md](brief.md), T14 in [todo.md](../todo.md), [SPEC.md](../../SPEC.md), [plan.md](../plan.md), [T12 requirements](../T12/requirements.md), [T13 requirements](../T13/requirements.md). Requirement ids are local to T14.

## Objective and inherited behavior

Reclaim retained job files without interrupting an eligible download, and restore downloadable published jobs after restart. Retention starts when the summary is successfully published, defaults to `JOB_RETENTION=PT1H`, and uses the persisted per-file `expiresAt`. A tap and a restart do not renew it. Existing private-chat, current allowlist and job-owner checks, on-demand delivery, single-worker audio queue, maximum three-hour input and 1500-character summary limit remain in force. This task neither regenerates summaries nor automatically uploads retained files.

The brief confirms three precedence rules: runtime cleanup skips a whole directory containing any `SENDING` file; age-based directory removal applies only to terminal jobs (`DONE`/`FAILED`), never live processing; expiry uses T12 labels and preserves `SENT` labels. These take precedence over generic cleanup wording in older documents.

## Requirements and acceptance criteria

### R1 — Run cleanup periodically and at startup

- Register a Spring `@Scheduled` cleanup with fixed delay `app.jobs.sweep-interval` / `JOB_SWEEP_INTERVAL`, default `PT1M`, measured from the end of the previous pass. The first scheduled execution is one interval after startup. Run cleanup at startup without waiting for that first tick; functional tests may configure a shorter interval such as `PT1S`. An idle bot must clean eligible files without receiving a Telegram update.
- Startup recovery must resolve persisted interrupted sends before startup cleanup decides eligibility. Normal callbacks and periodic cleanup must not race an unfinished recovery and reclaim its files.
- Use application time for expiry, so unit and functional tests can use a controlled clock. Advancing the clock alone is not proof that the real scheduler ran: acceptance waits for the scheduled execution and observes disk/HTTP effects.
- Repeated cleanup/startup runs must be safe for already-absent files/directories and must not create a new retention window.

### R2 — Expire files according to their persisted deadlines

- For an eligible directory without `SENDING`, an `AVAILABLE` file expires when `now >= expiresAt`, including exact equality. Before that instant it remains available. A nonnull future per-file deadline is honored independently of job creation time and any different deadline on the other file.
- A null per-file `expiresAt` does not expire at runtime. Startup treats an AVAILABLE file with a null deadline as expired, including files reset from SENDING; preserve a sibling with an explicit future deadline. Never invent an age fallback.
- Remove the expired file from disk and record `EXPIRED` while the directory remains needed for another available file. Do not leave an existing file downloadable after successful cleanup. Preserve the other file's bytes and unelapsed expiry.
- Do not change `SENT` to `EXPIRED`, clear its `sentAt`, or extend any deadline. `SENT` and `EXPIRED` remain terminal file states. Previously expired entries with already-absent files do not stop cleanup.
- When the whole directory is removed, final EXPIRED records need not survive outside it; no tombstone/history store is introduced. Final keyboard state must be derived before losing the record.

### R3 — Remove completed and failed directories, protect live processing

- Remove the entire job directory, including `job.json`, temporary outputs and nested working files, when every downloadable file is `SENT` or `EXPIRED`, or when a terminal job's retention is over. An empty processing file map must not be mistaken for “all delivered”.
- Cleanup after both files are delivered must not wait for retention to elapse; the next cleanup pass must remove the directory. T14 does not require a new synchronous Telegram callback cleanup hook.
- Preserve an unexpired undelivered file after partial delivery. For example AUDIO `SENT` and TRANSCRIPT `AVAILABLE` with a future deadline retain the transcript and durable sent state until expiry or delivery.
- Failed pipeline work is discarded immediately rather than held for one hour; existing failure replies and immediate deletion behavior remain intact. There is no durable FAILED record: the existing pipeline discards failed work immediately. An orphan whose immediate deletion failed is left alone at runtime when no usable record exists, and removed at startup under R6.
- Runtime age cleanup must leave downloading/transcribing/summarizing/queued jobs alone, even when older than `JOB_RETENTION` or when `job.json` does not exist yet. Interrupted processing at restart belongs to recovery rather than runtime age inference.
- No new job-level status or schema is introduced. Per the Architect, a usable published record with stored deadlines represents DONE. A directory with no usable record, or AVAILABLE files with null deadlines awaiting publication, is left alone at runtime. FAILED has no directory after successful immediate discard. Retention uses per-file deadlines independently, with no directory mtime/createdAt fallback.

### R4 — Preserve every directory with an active send

- During runtime cleanup, any file in `SENDING` protects the entire job directory: no expired sibling is deleted or relabeled, no job metadata is removed and no expiry keyboard edit is attempted for that job. This protection takes precedence over age and terminal/all-other-files-terminal rules.
- A claim granted strictly before expiry can finish after expiry, including audio that waits in the shared queue and a transcript upload waiting on Telegram's response. It sends once and records `SENT` under the existing T12/T13 rules.
- After all `SENDING` entries resolve, a subsequent cleanup pass applies normal expiry/deletion. A failed send released to `AVAILABLE` after its original deadline becomes eligible for expiry; it receives no renewed grace period.
- Claim versus deletion must be coordinated so exactly one wins: an accepted claim retains its files; cleanup that wins first causes the later tap to receive `Expired, please resend the link.` with no upload. Deterministic lock interleavings are Developer unit-test obligations.

### R5 — Recover interrupted sends without automatic delivery

- At startup, reset every persisted `SENDING` file to `AVAILABLE` before ordinary expiry cleanup. Preserve ownership, stored destinations, title, paths, original deadlines and unrelated `SENT` entries/sentAt values. Do not recreate a file that is missing on disk.
- An unexpired recovered file can be sent through a fresh owner callback after restart; an expired recovered file is expired/deleted by startup cleanup and cannot be sent. No automatic send, audio preparation, media download, Whisper call or Ollama call is caused merely by recovery.
- Unexpired published `AVAILABLE` files stay downloadable after restart without resubmitting the URL; `SENT` files cannot be resent while their job remains. Authorization and stored destinations continue to apply after restart.
- Recovery does not promise exactly-once delivery across crashes: if Telegram received a file before SENT was persisted, a new explicit tap after recovery may send it again. Telegram has no application idempotency key for these uploads; do not silently claim otherwise.
- Interrupted pre-publication work is not replayed. At startup, absent/unusable records are removed; null-deadline AVAILABLE entries are expired. A normal interrupted publication with both deadlines null therefore loses its directory.
- When recovery resets an unexpired SENDING file and its directory remains, refresh the summary keyboard best effort to its recovered AVAILABLE label, preserving sent sibling labels. If startup expiry already refreshed that job, do not add a duplicate recovery edit.

### R6 — Remove unusable job directories at startup safely

- At startup, remove job directories whose `job.json` is absent, invalid JSON or unusable under the job-store validation contract; this includes an orphan directory left before publication. Such cleanup does not require age to elapse.
- A callback to a removed job receives `Expired, please resend the link.` with no upload. Do not obtain Telegram targets or perform expiry edits from untrusted/unusable records.
- Enumerate only job directories whose names match the existing `[A-Za-z0-9_-]{1,40}` id contract; other names under `WORK_DIR` remain untouched. Cleanup is confined to job directories under `WORK_DIR`. Keep the separate access store and unrelated external files untouched; persisted filenames and ids must retain existing validation. Following a symlink must never delete the link target outside `WORK_DIR` (low-level filesystem cases belong in unit tests).
- An absent or empty work directory is a normal startup condition. Missing-file races are harmless; real read/access failures are diagnostic failures, not evidence that bytes are corrupt.

### R7 — Update the retained summary keyboard best effort

- When expiry changes download availability, attempt `editMessageReplyMarkup` against stored `chatId` and `summaryMessageId`, with audio first and transcript second, original `audio:<jobId>` and `transcript:<jobId>` callback data intact (within 64 UTF-8 bytes).
- Use labels AUDIO: AVAILABLE `Download audio`, SENDING `Preparing audio…`, SENT `Audio sent`, EXPIRED `Audio expired`; TRANSCRIPT: AVAILABLE `Download transcript`, SENDING `Sending transcript…`, SENT `Transcript sent`, EXPIRED `Transcript expired`. Do not mark a previously sent file expired when its sibling expires or the directory is deleted.
- A pure all-SENT cleanup must not replace sent labels with expired labels. A pass expiring one or both entries attempts one combined keyboard edit for that job. Both expired files produce `Audio expired` / `Transcript expired`; mixed SENT/expired files produce the corresponding sent/expired pair even when removing the directory in that pass.
- Preserve the summary body; send no new expiry announcement, upload or `deleteMessage`. Already-delivered Telegram files remain in the chat. Missing `summaryMessageId` skips the edit and does not block filesystem cleanup.
- The keyboard is informational. A stale callback after directory removal still reports expiry; no retained tombstone is required to answer `Already sent.` once job.json is gone.

### R8 — Keep cleanup effective when external or filesystem work fails

- A rejected/failed Telegram markup edit must not prevent successful expiry/deletion or make an expired file available. Do not make filesystem cleanup conditional on Telegram availability. Log diagnostics without exposing filesystem paths or exception text to users.
- A failure for one directory must not prevent independent eligible jobs being cleaned. Where deletion/persistence failed, preserve enough valid state for a later pass to retry safely rather than report a successful send or renew retention.
- Specific filesystem error injection, persistence ordering, scheduler registration, startup ordering and synchronization branches belong in Developer unit tests. Functional scenarios cover observable Telegram failures, disk outcomes and recovery through full application startup; they may not call cleanup/recovery services directly.

### R9 — Deliver external acceptance coverage and preserve project gates

- [T14-retention.feature](../../src/features/T14-retention.feature) uses Telegram updates/HTTP mocks, files on disk, application clock fixtures and application startup/scheduled time. No step calls or names a production service/component/method.
- Tester supplies `ExpiryIT` and Cucumber steps; Developer supplies `RetentionSweeperTest` and recovery/state/filesystem unit coverage with a fake clock and temp directory. Existing T12/T13 scenarios that assert files remain immediately after delivery must isolate scheduled ticks so they do not contradict next-pass cleanup.
- Verification commands for the responsible roles: `./mvnw test -Dtest=RetentionSweeperTest`, `./mvnw verify -Dit.test=ExpiryIT`, coordinated `./mvnw clean verify`, and `./mvnw verify -Pmutation`. Keep the unit-only 90% line and 90% branch gates and 85% mutation threshold; integration coverage does not count.
- Analyst checks feature syntax, tags, traceability and links; does not run shared Maven clean/builds, implement test steps, edit production code or make commits. Executable test success is not claimed by this handoff.

## Acceptance mapping

| Requirement | Evidence |
|---|---|
| R1 | Idle periodic cleanup, startup cleanup, repeated startup; scheduler/order unit tests |
| R2 | Exact boundary, independently expiring siblings, old creation time/future deadline |
| R3 | Partial delivery, both delivered, SENT/EXPIRED combinations, live processing; lifecycle branches unit-tested |
| R4 | Held transcript upload and queued audio across expiry, released failure; claim/delete race unit tests |
| R5 | Startup AVAILABLE/SENDING/SENT combinations, expired SENDING, fresh callback, no automatic processing |
| R6 | Missing/corrupt/unusable job.json, empty work root, external sentinel; symlink/read-failure unit tests |
| R7 | Expired/sent keyboard pairs, unchanged callbacks/body, null message id |
| R8 | Telegram edit rejection and another eligible job, subsequent callback; I/O failures unit-tested |
| R9 | Analyst static validation, Tester and Developer execution evidence |

## Out of scope

T18 DLQ/automatic service retries; replaying interrupted pipelines; durable episode history/tombstones; new retention settings or databases; changing callback ownership/access policy, compression, summary content, maximum media duration or upload guarantees; live Telegram/model smoke tests; general T16 failure hardening. The new sweep interval controls scheduling only, not the retention window. No changes to SPEC.md, task status, architecture-owned files, production code, test code or commits by this role.

## Resolved architecture questions

[A: Q-analyst-1/2](comms/questions.md), [components.md](components.md), [data-flow.md](data-flow.md), [flows.md](flows.md) and [api.md](api.md) define the lifecycle without a schema change: runtime leaves missing/unusable records and null deadlines alone; startup removes unusable records, resets SENDING, then expires due or null-deadline AVAILABLE files. Deadlines are independent with no age fallback. The design adds a configurable scheduling interval and a best-effort recovery keyboard refresh. No owner product question remains open.
