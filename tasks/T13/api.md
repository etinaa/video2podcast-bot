# T13 contracts

No HTTP endpoint of ours, no new configuration property, no change of `job.json`.

## Rule constants (decisions, answers to Q-analyst-1)

- `LIMIT_BYTES = 50 * 1024 * 1024` (52 428 800). Chosen as 50 MiB because the SPEC target of 48 MiB is already above 50 000 000 bytes. `TARGET_BYTES = 48 * 1024 * 1024`.
- Source unchanged when `size <= LIMIT_BYTES` (inclusive) and the extension (case-insensitive) is `m4a` with audio codec `aac`, or `mp3` with codec `mp3`. The codec comes from ffprobe (T17, D15); an unknown codec is not unchanged.
- An encoded output is accepted only when `size < LIMIT_BYTES` (strict) and the file is not empty.
- Duration is `JobRecord.durationSeconds` (rounded up already by `YtDlpClient`).

## `com.video2podcast.media`

```java
public record PreparedAudio(Path file, Mode mode, int kbps) {
  public enum Mode { UNCHANGED, CONVERTED, COMPRESSED }
  // UNCHANGED: kbps == 0 (unknown, not reported).
}

public class AudioPreparer {
  public AudioPreparer(FfmpegRunner ffmpeg);

  /**
   * @param source          the downloaded file (any container; may be a video)
   * @param durationSeconds > 0; a value <= 0 is treated as 1
   * @throws JobFailure FAILED or TIMEOUT (from ffmpeg), or FAILED when no output under the limit
   *                    could be made or the source cannot be read
   */
  public PreparedAudio prepare(Path source, long durationSeconds);
}
```

Decision table (in this order):

| # | Condition | Action | Result |
|---|---|---|---|
| 1 | size ≤ LIMIT and (ext m4a + codec aac, or ext mp3 + codec mp3) | none | `UNCHANGED`, `file == source` (same path) |
| 2 | size ≤ LIMIT, other ext (e.g. `source.mp4`, `.webm`, `.opus`) | `encodeM4a(source, <dir>/out.m4a, 128, mono=false)` (`-ac 2` since T17) | if output `< LIMIT`: `CONVERTED`, 128. Otherwise go to row 3 |
| 3 | size > LIMIT, or row 2 gave an oversized output | compress | see below |

Compression: `kbps = clamp(floor(TARGET_BYTES * 8 / max(durationSeconds,1) / 1000), 8, 128)`; `encodeM4a(source, out, kbps, mono=true)`. If the result is `< LIMIT` → `COMPRESSED`, `kbps`. Otherwise one retry at `lower = max(8, floor(kbps * 0.8))` (output overwritten); if still not `< LIMIT` (or `lower == kbps`) → `JobFailure.of(FAILED)`. A failed attempt leaves no `out.m4a` behind. Examples: 3 h (10 800 s) → 37 kbps, retry 29 kbps; 1 h → 111 kbps.

```java
// FfmpegRunner (addition)
/**
 * Writes an M4A (AAC) file without video: ffmpeg -nostdin -hide_banner -loglevel error -y -i <source>
 * -vn [-ac 1] -c:a aac -b:a <kbps>k -movflags +faststart <target>. The target is deleted first.
 * @return target
 * @throws JobFailure FAILED (non-zero exit, no or empty output) or TIMEOUT
 */
public Path encodeM4a(Path source, Path target, int kbps, boolean mono);
```

## `com.video2podcast.bot`

```java
// TelegramGateway (changed signature; the old 4-argument form is removed)
/**
 * Sends an audio file that plays in Telegram's player.
 * @param fileName shown to the user, e.g. "Budget meeting.m4a"
 * @param caption  HTML, may be null
 * @throws TelegramGatewayException if Telegram refuses or cannot be reached
 */
void sendAudio(long chatId, Path file, String fileName, String title, String performer, String caption);

// FileNames (package-private)
static String audioName(String title, String extension); // "<sanitised title>.<extension>", empty title → "audio"
// transcriptName(title) is unchanged in behaviour.

// DeliveryCallbackHandler
public DeliveryCallbackHandler(TelegramGateway gateway, DeliveryService delivery,
                               AudioPreparer audio, JobQueue queue);
void handle(CallbackQuery callback); // now: Keyboards.AUDIO_PREFIX or TRANSCRIPT_PREFIX
```

`BotUpdateHandler.handleCallback`: after the existing access checks, `audio:` or `transcript:` → `delivery.handle(callback)`.

### Texts (exact)

| When | Text |
|---|---|
| answer, tap granted | `Preparing audio…` |
| refusals | the same five texts as T12: `Expired, please resend the link.`, `This button is not for you.`, `Already sent.`, `Already being sent.`, `Something went wrong, please try again.` |
| message after a failed prepare/send | `Could not send the audio. Tap the button to try again.` |
| caption `UNCHANGED` | `Original audio, not compressed.` |
| caption `CONVERTED` | `Converted to M4A, 128 kbps.` |
| caption `COMPRESSED` | `Compressed to mono AAC, <kbps> kbps, to fit Telegram's 50 MB limit.` |

`title` = `job.title()` (the Telegram title, cut to 256 UTF-16 units not inside a surrogate pair); `performer` = the constant `Video2Podcast`. The job stores no uploader and this task does not add one (answer to Q-analyst-2; the SPEC phrase "from the video" is met by the title). Caption has no characters that need HTML escaping.

### Telegram calls of a successful tap, in order

1. `answerCallback(callbackId, "Preparing audio…")` (in the update thread)
2. `editButtons(chatId, summaryMessageId, delivery(jobId, SENDING, transcriptState))` (in the update thread; then the task is submitted to `JobQueue`)
3. In the queue worker: `AudioPreparer.prepare(claim.file(), job.durationSeconds())`
4. `sendAudio(chatId, prepared.file(), FileNames.audioName(title, ext), title, "Video2Podcast", caption)`; `ext` is the extension of `prepared.file()` (`m4a` for CONVERTED/COMPRESSED, the source's own for UNCHANGED)
5. `delivery.complete(jobId, AUDIO)`; the returned record gives the states for the last edit
6. `editButtons(chatId, summaryMessageId, delivery(jobId, SENT, <transcript state of the record returned by complete>))`
7. `out.m4a` is deleted if `prepared.file()` is not the source (finally; best effort)

The transcript state in steps 2 and 6 comes from the latest stored record (step 2: the claim's record; step 6: the record `complete` returns), so a transcript that was sent meanwhile keeps its "Transcript sent" label. `editButtons` is skipped when `summaryMessageId` is null.

### Failure rules

- `answerCallback` and every `editButtons` are best effort (log, ignore).
- Any `RuntimeException` in steps 3-5 (`JobFailure`, `TelegramGatewayException`, I/O, a missing source file) or `RejectedExecutionException` from `submit`: `release(AUDIO)`, `editButtons(delivery(jobId, AVAILABLE, transcript))`, `sendMessage(chatId, "Could not send the audio. Tap the button to try again.", List.of())`, all best effort. Raw ffmpeg stderr goes to the log only. No automatic retry. The task never rethrows (the queue must stay clean), and `out.m4a` is deleted.
- If `complete` throws after a successful send: log, no further Telegram call (same as T12).
- The claim governs expiry (T12 rule): a tap granted before `expiresAt` finishes even if `expiresAt` passes while the task waits in the queue. A tap after `expiresAt` is `EXPIRED` and nothing is queued. T14's sweeper must not remove a job directory that has a `SENDING` file.
- The audio and the transcript are independent: each has its own state; the `AUDIO` claim does not touch `TRANSCRIPT`.
- Ownership is the T12 rule (`callback.getFrom().getId() == job.ownerUserId()`).
- A transcript tap while an audio task waits in the queue is answered at once; it is not queued (only audio preparation uses the queue).

## Files and fixtures for the Tester

Real `ffmpeg` produces the outputs in the success scenarios. A source over 50 MiB needs a generated file; the Tester may generate it (for example an uncompressed WAV renamed `source.wav`, which is not mp3/m4a). Failures that cannot be produced for real (oversized output at 128 kbps, timeout) use a fake `ProcessRunner`.
