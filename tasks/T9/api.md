# T9 contracts

Package `com.video2podcast.transcript`.

## TranscriptionClient (public, the only entry point)

```java
public class TranscriptionClient {
  public TranscriptionClient(WhisperClient whisper);

  /**
   * Transcribes {@code chunks} in order and writes {@code <jobDir>/transcript.txt}.
   * On success deletes {@code <jobDir>/audio.wav} and {@code <jobDir>/chunks/}; keeps source.*.
   *
   * @param progress called as accept(n, total) before chunk n is first sent (n from 1), once per
   *     chunk, not again on the retry
   * @return the path of transcript.txt
   * @throws NullPointerException if an argument is null
   * @throws JobFailure NO_SPEECH, SERVICE_UNREACHABLE or FAILED (messages below)
   */
  public Path transcribe(Path jobDir, List<Path> chunks, ProgressListener progress);

  @FunctionalInterface
  public interface ProgressListener {
    void accept(int n, int total);
  }
}
```

Rules:
1. Chunks are sent sequentially in list order; the list is never re-sorted or re-read from disk. All of them are processed, no count limit.
2. Per chunk: `progress.accept(n, N)`, then `whisper.transcribe(chunk)`. On `WhisperException` retry once immediately (no sleep). If the retry throws `e2`: `e2.isConnection()` gives `SERVICE_UNREACHABLE`, otherwise `FAILED`. Later chunks are not sent. Nothing is written or deleted.
3. A successful answer whose text is empty after normalizing is a valid empty chunk: skipped, no retry, still counted in `n` and `N`.
4. After all chunks: `TranscriptMerger.merge(texts)`. Empty gives `NO_SPEECH`, nothing written.
5. Otherwise write `transcript.txt` with `AtomicFiles.write(path, UTF-8 bytes)`. `IOException` gives `JobFailure.transcriptionFailed(e)`; nothing is deleted.
6. After a successful write, delete `audio.wav`, each file in `chunks/`, then `chunks/`, each deletion in its own try. An error is logged at WARN (file name only), the rest still run, the result stays a success.
7. Argument contracts (empty list, bad `jobDir`, missing chunk, existing `transcript.txt`, throwing `progress`) are in `flows.md`, section "Argument contracts".

## WhisperClient (public, one HTTP call)

```java
public class WhisperClient {
  public WhisperClient(AppProperties.Whisper config);

  /** Returns the raw "text" field of the answer, not normalized. */
  public String transcribe(Path chunk) throws WhisperException;
}

public class WhisperException extends Exception {
  public WhisperException(String message, boolean connection, Throwable cause);
  /** True when there was no connection (refused, unresolved host, connect timeout). */
  public boolean isConnection();
}
```

HTTP, built with `java.net.http.HttpClient` (`connectTimeout` fixed 5 s, redirects NEVER):

```
POST {app.whisper.base-url}/inference     (a trailing "/" in base-url is dropped)
Content-Type: multipart/form-data; boundary=<random>
parts, in this order:
  file             filename=<chunk file name>, Content-Type: audio/wav, the chunk bytes
  response_format  json
  language         en
  temperature      0.0
```

Every attempt, including the retry, sends the same four parts and has its own request timeout `app.whisper.timeout` (`HttpRequest.timeout`). The body is a streaming `BodyPublisher` over the file, not one array. The response uses `BodyHandlers.ofString` (UTF-8).

Mapping to `WhisperException`:

| Situation | `connection` |
|---|---|
| `ConnectException`, `UnresolvedAddressException`, `HttpConnectTimeoutException` (test this one before `HttpTimeoutException`) | true |
| `HttpTimeoutException` (answer not in time) | false |
| status not 2xx (the body is logged, at most 200 chars, never put in the message) | false |
| 2xx but empty body, not JSON, no `text`, `text` null or not a string | false |
| other `IOException` (including a missing or unreadable chunk), `InterruptedException` (interrupt flag restored) | false |

Success: status 2xx and `$.text` is a string (may be empty). Parsing uses `storage/Json.MAPPER`. The `WhisperException` message is for logs only and never reaches the chat.

Response read: `{"text": " Hello ...\n ..."}` (fixture `whisper-inference-ok.json`). Other fields are ignored. Errors are plain text (`400 Invalid request`, fixture `whisper-inference-bad-request.txt`).

## TranscriptMerger (package-private, static)

```java
final class TranscriptMerger {
  /** Trims and collapses every run of whitespace (Unicode, including CR, LF, tab, U+00A0) to one space. */
  static String normalize(String text);       // null gives ""

  /** Normalizes each, drops empty ones, joins with one space, appends one "\n". Returns "" if nothing is left. */
  static String merge(List<String> texts);
}
```

Whitespace: regex `[\s ]+` with `UNICODE_CHARACTER_CLASS`. No header, timestamps, BOM. Words, punctuation and Unicode letters are untouched. Example: `[" Hello\n world ", "", "Next one\n"]` gives `"Hello world Next one\n"`.

## JobFailure (job/JobFailure.java)

| Kind | Message |
|---|---|
| `NO_SPEECH` (new) | `No speech was found in this video.` |
| `SERVICE_UNREACHABLE` (new) | `The transcription server is unreachable.` |
| `FAILED` from T9 | `Transcription failed. Please try again later.` |

Changes:
- `enum Kind` gains `NO_SPEECH`, `SERVICE_UNREACHABLE` (appended); `messageOf` gets both.
- `of(Kind)` / `of(Kind, Throwable)` give the new messages for the new kinds; `FAILED` keeps "Could not process this video. Please try again later.".
- New `public static JobFailure transcriptionFailed(Throwable cause)`: kind `FAILED`, the transcription message, cause kept for the log (null allowed). `TranscriptionClient` uses it for every `FAILED` it throws.

Messages never contain server text, paths, URLs or stack traces. `SERVICE_UNREACHABLE` is only a typed failure; T9 queues nothing and promises no later delivery (the DLQ is a separate task). T11 shows `getMessage()`.

## Configuration

```java
public record Whisper(String baseUrl, Duration timeout) {}
```

```yaml
app:
  whisper:
    base-url: ${WHISPER_BASE_URL:http://host.containers.internal:8081}   # existing
    timeout: ${WHISPER_TIMEOUT:PT10M}                                    # new
```

`application-dev.yml` is unchanged. `SPEC.md` lists `WHISPER_TIMEOUT` (default `PT10M`). The connection wait (5 s) is a constant in `WhisperClient`, not configurable. Existing code and tests that build `AppProperties.Whisper(String)` must be updated to the two-argument form.

## Beans (`config/TranscriptConfig`)

```java
@Configuration(proxyBeanMethods = false)
public class TranscriptConfig {
  @Bean WhisperClient whisperClient(AppProperties props);              // props.whisper()
  @Bean TranscriptionClient transcriptionClient(WhisperClient client);
}
```

## Files on disk

| Path | Written by | Notes |
|---|---|---|
| `<jobDir>/transcript.txt` | `TranscriptionClient` | UTF-8, text plus one `\n`, atomic write |
| `<jobDir>/audio.wav`, `<jobDir>/chunks/` | T8 `FfmpegRunner` | deleted by T9 after a successful write |
| `<jobDir>/source.*`, `job.json` | T8 / job store | untouched by T9 |
