# T12 contracts

No HTTP endpoint, no new configuration property, no change of the `job.json` format. Existing: `JobFile.state`, `JobFile.expiresAt`, `JobFile.sentAt` (set by `JobStore.transition` on `SENT`).

## `com.video2podcast.job`

```java
public class DeliveryService {
  public DeliveryService(JobStore store, Clock clock);

  public enum Outcome { GRANTED, EXPIRED, NOT_OWNER, ALREADY_SENT, IN_PROGRESS }

  /**
   * @param job  the stored job after the claim (the file is SENDING when GRANTED); null when the
   *             job does not exist
   * @param file the file to send; set only when GRANTED
   */
  public record Claim(Outcome outcome, JobRecord job, Path file) {}

  /**
   * Checks a tap and, when it is allowed, moves the file AVAILABLE -> SENDING. Exactly one of
   * several concurrent callers gets GRANTED. Order of checks: job missing or invalid id (EXPIRED);
   * userId != ownerUserId (NOT_OWNER); SENT (ALREADY_SENT); EXPIRED (EXPIRED); SENDING
   * (IN_PROGRESS); AVAILABLE with expiresAt != null and !clock.instant().isBefore(expiresAt)
   * (EXPIRED); AVAILABLE but the file is not on disk (EXPIRED, and the file is moved
   * AVAILABLE -> EXPIRED); then the transition. A lost race reloads the job and answers ALREADY_SENT (SENT),
   * IN_PROGRESS (SENDING) or EXPIRED (job or file gone, EXPIRED).
   *
   * @throws JobStoreException on an I/O error
   */
  public Claim claim(String jobId, long userId, FileKind kind);

  /** SENDING -> SENT. Returns the stored job, or empty if it is gone or the file was not SENDING. */
  public Optional<JobRecord> complete(String jobId, FileKind kind);

  /** SENDING -> AVAILABLE after a failed send. Returns the stored job, or empty as above. */
  public Optional<JobRecord> release(String jobId, FileKind kind);
}
```

`claim` returns `file = store.resolve(jobId, kind-to-JobFile)`: the file's `path` from `job.files().get(kind)`. `complete` and `release` catch nothing; they use `JobStore.transition` (`STATE_MISMATCH` and `NOT_FOUND` give an empty result) then `store.load`.

## `com.video2podcast.bot`

```java
/** public, like UrlMessageHandler: config/ constructs it and injects it into BotUpdateHandler */
public class DeliveryCallbackHandler {
  public DeliveryCallbackHandler(TelegramGateway gateway, DeliveryService delivery);

  /**
   * Package-private, called only by BotUpdateHandler. callback.getData() starts with Keyboards.TRANSCRIPT_PREFIX; the rest is the job id. Answers
   * the tap exactly once. Never throws for a failed Telegram call or a JobStoreException.
   */
  void handle(CallbackQuery callback);
}

/** package-private, static */
final class FileNames {
  /**
   * "<title>.txt": characters \ / : * ? " < > | and control characters become "_", the title is
   * stripped and cut to 100 UTF-16 units (not inside a surrogate pair), an empty result is
   * "transcript".
   */
  static String transcriptName(String title);
}
```

`Keyboards` (additions; `delivery(String jobId)` is kept and equals `delivery(jobId, AVAILABLE, AVAILABLE)`):

```java
static List<InlineButton> delivery(String jobId, FileState audio, FileState transcript);
```

One row, audio first. Labels by state (callback data is always `audio:<jobId>` / `transcript:<jobId>`):

| State | Audio button | Transcript button |
|---|---|---|
| `AVAILABLE` | Download audio | Download transcript |
| `SENDING` | Preparing audio… | Sending transcript… |
| `SENT` | Audio sent | Transcript sent |
| `EXPIRED` | Audio expired | Transcript expired |

`TelegramGateway` (addition):

```java
/**
 * Replaces only the buttons of a message this bot sent earlier; the text stays. An empty list
 * removes the keyboard.
 * @throws TelegramGatewayException if Telegram refuses or cannot be reached
 */
void editButtons(long chatId, int messageId, List<InlineButton> buttons);
```

`TelegramClientGateway.editButtons` sends `editMessageReplyMarkup` (`chat_id`, `message_id`, `reply_markup` as in `keyboard(buttons)`; with an empty list an empty `InlineKeyboardMarkup`) through the same `call(...)` wrapper. Other `TelegramGateway` implementations (the recording test gateway) add the method.

`BotUpdateHandler(TelegramGateway gateway, AccessService access, UrlMessageHandler urls, DeliveryCallbackHandler delivery)`: in `handleCallback`, after the access check, `data.startsWith(Keyboards.TRANSCRIPT_PREFIX)` → `delivery.handle(callback)`. The existing order stays: private-chat check, "No access." for a user who is not allowed, `access:` to `AccessAdmin`.

### Texts (exact)

| When | Text |
|---|---|
| answer, tap granted | `Sending transcript…` |
| `EXPIRED` | `Expired, please resend the link.` |
| `NOT_OWNER` | `This button is not for you.` |
| `ALREADY_SENT` | `Already sent.` |
| `IN_PROGRESS` | `Already being sent.` |
| `JobStoreException` | `Something went wrong, please try again.` |
| message after a failed send | `Could not send the transcript. Tap the button to try again.` |

### Telegram calls of a successful tap, in order

1. `answerCallback(callbackId, "Sending transcript…")`
2. `editButtons(job.chatId, job.summaryMessageId, delivery(jobId, audioState, SENDING))`
3. `sendDocument(job.chatId, <jobdir>/transcript.txt, FileNames.transcriptName(job.title), null)`
4. `editButtons(job.chatId, job.summaryMessageId, delivery(jobId, audioState, SENT))`

`editButtons` is skipped when `summaryMessageId` is null. Bot API view for the Tester: `POST /bot<token>/answerCallbackQuery`, `.../editMessageReplyMarkup` (the keyboard in `reply_markup`), `.../sendDocument` (multipart: `chat_id`, `document` with filename `<title>.txt`). A refused tap makes only the `answerCallbackQuery` call.

### `job.json` after success

`files.TRANSCRIPT.state = "SENT"`, `files.TRANSCRIPT.sentAt` set, `files.AUDIO` unchanged, `transcript.txt` still on disk.

### Failure rules (from Q-analyst-1)

- `answerCallback` and every `editButtons` call are best effort: a `TelegramGatewayException` is logged and ignored.
- A missing `transcript.txt` is not a send failure: `claim` answers `EXPIRED` before any transition (SPEC criterion 6), nothing is uploaded.
- Any exception from `sendDocument` or another `RuntimeException` after `claim`: `release`, then restore the button and send the "Could not send…" message (both best effort). No automatic retry.
- Ownership is `callback.getFrom().getId() == job.ownerUserId()`. The callback's chat and message id are not compared with `chatId` and `summaryMessageId`.
