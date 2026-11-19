# T11 contracts

No HTTP endpoint, no new configuration property, no new environment variable. Existing settings used: `app.jobs.work-dir`, `app.jobs.retention` (`JOB_RETENTION`, default `PT1H`), and the settings of the media, Whisper and Ollama clients.

## `com.video2podcast.job`

```java
/** What the pipeline needs to know about one accepted link. */
public record JobRequest(String jobId, long ownerUserId, long chatId, String url, Instant createdAt) {}

/** Stage reports from the worker thread. Implementations must not throw. */
public interface JobProgress {
  void downloading();
  /** n counts from 1; called once per chunk, before the chunk is sent to Whisper. */
  void transcribing(int n, int total);
  void summarizing();
}

/** Sends the summary message of a finished job (and can take its buttons away again). */
public interface SummaryPublisher {
  /**
   * Sends the summary message with the two delivery buttons and returns its Telegram message id.
   * @throws TelegramGatewayException if Telegram refuses or cannot be reached
   */
  int publish(JobRecord job, String summary);

  /** Best effort: replaces the summary message by the same text without buttons. Never throws. */
  void withdraw(JobRecord job, int messageId, String summary);
}

public class PipelineService {
  public PipelineService(
      YtDlpClient ytDlp, FfmpegRunner ffmpeg, TranscriptionClient transcription,
      SummaryService summary, JobStore store, SummaryPublisher publisher,
      Clock clock, AppProperties.Jobs jobs);

  /**
   * Runs one job to the point where the summary message is sent and its retention has started.
   * Runs on the worker thread. On every failure the job directory is deleted (best effort; a
   * deletion error is logged) before the exception leaves.
   *
   * @throws JobFailure UNSUPPORTED, TOO_LONG, PRIVATE, FAILED, TIMEOUT, NO_SPEECH,
   *     SERVICE_UNREACHABLE, SUMMARY_UNREACHABLE (from the media, transcription and summary
   *     components, unchanged), or FAILED for anything else (see below)
   */
  public void run(JobRequest request, JobProgress progress);
}
```

### Order of `run` (the Developer builds exactly this)

1. `Files.createDirectories(store.directory(jobId))`. An `IOException` is `JobFailure.of(FAILED, e)`.
2. `progress.downloading()`.
3. `meta = ytDlp.fetchMetadata(url)`, then `source = ytDlp.downloadAudio(url, dir)`.
4. `wav = ffmpeg.toWav(source, dir)`, `chunks = ffmpeg.split(wav, dir)`.
5. `transcript = transcription.transcribe(dir, chunks, progress::transcribing)`.
6. `progress.summarizing()`, read `transcript` as UTF-8 (an `IOException` is `JobFailure.transcriptionFailed(e)`), `bullets = summary.summarize(text)`.
7. Build the record: `new JobRecord(jobId, ownerUserId, chatId, null, meta.title(), meta.duration().toSeconds(), createdAt, files)` with `files = {AUDIO: JobFile.available(source.getFileName().toString()), TRANSCRIPT: JobFile.available("transcript.txt")}`. `store.create(record)`.
8. `messageId = publisher.publish(record, bullets)`; `sentAt = clock.instant()`; `store.update(jobId, r -> r.published(messageId, sentAt.plus(jobs.retention())))`. An empty result from `update` is a failure.

Failure rules (`catch` around steps 1 to 8):

- `JobFailure`: delete the directory, rethrow the same object.
- `JobStoreException`, `TelegramGatewayException`, or any other `RuntimeException`: log with the stack, delete the directory, throw `JobFailure.of(FAILED, cause)`.
- A failure after `publish` returned (the `update` in step 8) leaves a summary message with buttons that cannot work. `job/` must not touch Telegram, so `SummaryPublisher.withdraw` (above) does it: `PipelineService` calls `withdraw(record, messageId, bullets)` in that case, then deletes the directory and throws `FAILED`.
- Files are deleted only through `store.delete(jobId)`; a missing directory is not an error.
- Success deletes nothing: `source.*`, `transcript.txt` and `job.json` stay (T12, T13 and T14 use them).

## `com.video2podcast.bot`

```java
public class UrlMessageHandler {
  public UrlMessageHandler(
      TelegramGateway gateway, UrlValidator validator, JobQueue queue,
      PipelineService pipeline, Clock clock);

  /** text is the whole message text; it is stripped here. Never throws for a failed Telegram call. */
  void handle(Sender sender, String text);
}
```

`BotUpdateHandler(TelegramGateway gateway, AccessService access, UrlMessageHandler urls)`. In `handleMessage`, after the `/whoami`, unknown-user, `/start` and admin branches: `else if (!message.getText().strip().startsWith("/")) urls.handle(sender, message.getText())`.

### `handle` step by step

1. `url = text.strip()`. `validator.validate(url)`; on `JobFailure` send `sendMessage(chatId, Html.escape(failure.getMessage()), List.of())` and return (F2).
2. `jobId = JobStore.newJobId()`, `request = new JobRequest(jobId, sender.userId(), sender.chatId(), url, clock.instant())`, `statusId = new CompletableFuture<Integer>()`, `AtomicReference<JobFailure> failure`.
3. `future = queue.submit(task)` where `task` is:
   - `Integer id = statusId.get(30, SECONDS)`; on any exception (send failed, timeout, interrupt) return without running the job. (A timeout or interrupt is logged at WARN.)
   - `try { pipeline.run(request, new StatusReporter(gateway, chatId, id)); } catch (JobFailure f) { failure.set(f); } catch (RuntimeException e) { log; failure.set(JobFailure.of(FAILED, e)); }`. The task never throws for an expected failure, so the queue does not log an ERROR for it.
   - `RejectedExecutionException` from `submit`: reply with `JobFailure.of(FAILED)` (as in step 1) and return.
4. `position = queue.size()`; `id = gateway.sendMessage(chatId, "Queued: position " + position, List.of())`; `statusId.complete(id)`. On `TelegramGatewayException`: log, `statusId.completeExceptionally(e)`, return.
5. `future.whenCompleteAsync(callback, Thread::startVirtualThread)` where the callback runs **after the job has left the queue** and, best effort (every exception is logged and swallowed):
   - `failure.get() == null` and the future completed normally and the status id exists: `editMessage(chatId, id, "Done.", List.of())`.
   - `failure.get() != null`: `sendMessage(chatId, Html.escape(failure.getMessage()), List.of())`, then `editMessage(chatId, id, "Failed.", List.of())`.
   - the future was cancelled or failed (queue closed): same as a failure with `JobFailure.of(FAILED)`.
   - the task ended because the status message could not be sent (no id): nothing is sent.

Position: `queue.size()` right after `submit` includes the new job, so the first job in an empty queue shows position 1.

### Status texts (exact, ASCII digits, the ellipsis is U+2026)

| Stage | Text | Sent as |
|---|---|---|
| accepted | `Queued: position N` | first message (the acknowledgement), `sendMessage` |
| `downloading()` | `Downloading…` | `editMessage` |
| `transcribing(n, total)` | `Transcribing n/total…` | `editMessage` |
| `summarizing()` | `Summarizing…` | `editMessage` |
| success | `Done.` | `editMessage` |
| failure | `Failed.` | `editMessage`, after the failure message |

`StatusReporter implements JobProgress` is package-private. It remembers the last text and does not edit when the new text is equal (Telegram rejects an unchanged edit). A `TelegramGatewayException` from an edit is logged at WARN and swallowed. Status messages have no buttons and contain no user text, so no escaping is needed.

### Failure message texts

The message of the `JobFailure` (fixed sentences in `JobFailure.messageOf` and its factories, for example "This link is not a supported video.", "The video is longer than 2 hours.", "Summary failed. Please try again later.") sent as a plain `sendMessage` with `Html.escape` applied. Nothing else is added.

## `SummaryMessageFormatter` (package-private, static)

```java
static final int TITLE_MAX = 200;

/** "<b>title</b>\nDuration: 1h 42m\n\n• a\n• b" with title and bullets HTML-escaped. */
static String format(String title, long durationSeconds, String bullets);

/** Whitespace runs become one space, strip, cut to TITLE_MAX UTF-16 units (a cut that would split
 *  a surrogate pair goes one unit lower); a cut title ends with "…" and is TITLE_MAX units long. */
static String displayTitle(String title);

/** hours > 0: "<h>h <m>m"; else minutes > 0: "<m>m"; else "<s>s". Minutes are floored. */
static String formatDuration(long seconds);
```

Examples: `0 → "0s"`, `45 → "45s"`, `300 → "5m"`, `3600 → "1h 0m"`, `6120 → "1h 42m"`, `10800 → "3h 0m"`.

Length rule (answers Q-analyst-1 b): Telegram counts the text after it parsed the tags, in UTF-16 units. The parsed text is `displayTitle` (at most 200) + 1 + `Duration: …` (at most about 25) + 2 + bullets (at most 1,500, T10 cap), so at most about 1,730 and never close to 4,096. Escaping adds only tags and entities, which Telegram does not count. The 1,500 summary cap is not reduced. `job.json` keeps the title unchanged (T12 builds the file name from it).

## `SummaryMessagePublisher implements SummaryPublisher`

- `publish`: `gateway.sendMessage(job.chatId(), SummaryMessageFormatter.format(job.title(), job.durationSeconds(), summary), Keyboards.delivery(job.jobId()))`.
- `withdraw`: `gateway.editMessage(job.chatId(), messageId, <same html>, List.of())`; any exception is logged and swallowed.

## `Keyboards`

```java
static final String AUDIO_PREFIX = "audio:";
static final String TRANSCRIPT_PREFIX = "transcript:";
/** One row, in this order. Callback data is at most 6 + 36 and 11 + 36 bytes. */
static List<InlineButton> delivery(String jobId) {
  return List.of(
      new InlineButton("Download audio", AUDIO_PREFIX + jobId),
      new InlineButton("Download transcript", TRANSCRIPT_PREFIX + jobId));
}
```

## Telegram calls a successful job makes (what the Tester asserts)

1. `sendMessage` to the user's chat: `Queued: position N`, no `reply_markup`.
2. `editMessageText` on that message id, in this order: `Downloading…`, `Transcribing 1/N…` to `Transcribing N/N…`, `Summarizing…`.
3. `sendMessage`: the summary HTML (`parse_mode` HTML) with an inline keyboard of the two buttons (`audio:<jobId>`, `transcript:<jobId>`).
4. `editMessageText` on the status message: `Done.` (after the job left the queue).
5. No `sendDocument`, no `sendAudio`.

Failure: calls 1, the stage edits reached so far, then `sendMessage` with the failure sentence, then `editMessageText` `Failed.`. No summary message, no buttons, and the job directory does not exist afterwards. A rejected link (F2): one `sendMessage` with the sentence and nothing else.

## Beans (`config/PipelineConfig`, same `@ConditionalOnProperty(app.telegram.enabled)` as `TelegramConfig`)

```java
@Bean SummaryPublisher summaryPublisher(TelegramGateway gateway)
@Bean PipelineService pipelineService(YtDlpClient, FfmpegRunner, TranscriptionClient, SummaryService,
    JobStore, SummaryPublisher, Clock, AppProperties props)         // props.jobs()
@Bean UrlMessageHandler urlMessageHandler(TelegramGateway, UrlValidator, JobQueue, PipelineService, Clock)
```

`TelegramConfig.botUpdateHandler(TelegramGateway, AccessService, UrlMessageHandler)`.

## Test seams for the Tester

- `ProcessRunner` is already `@ConditionalOnMissingBean`: the functional harness supplies its own bean (decorator over `DefaultProcessRunner` adding `--proxy` to `yt-dlp`, or a fake for failures).
- Everything else is reached through Telegram updates, the WireMock servers, the media proxy and the job directory under `app.jobs.work-dir`. The harness's `Updates.privateMessage` already builds the link message.
- Clock: a test configuration may add a mutable `Clock` bean marked `@Primary`; every consumer (`JobStore`, `PipelineService`, `UrlMessageHandler`, `AccessService`) takes the `Clock` bean by type, so no production change is needed.
- Retention: `PipelineService` reads `app.jobs.retention` once, when it is built. A scenario that needs another value (`JOB_RETENTION` `PT30M`) needs its own application context (the Tester decides how). No production seam is added for it.
- Message ids: the shared `MESSAGE_OK` stub answers every send with id 77. Scenarios that must tell the status message from the summary message need a stub that returns a different id per call (WireMock response templating or a scenario state).
