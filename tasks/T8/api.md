# T8 contracts

All types under `com.video2podcast`. Messages are English and never contain stderr, paths or the URL.

## `job.JobFailure`
```java
public class JobFailure extends RuntimeException {
  public enum Kind { PRIVATE, UNSUPPORTED, TOO_LONG, FAILED, TIMEOUT }
  public JobFailure(Kind kind, String safeMessage);   // message = safeMessage, no cause exposed
  public Kind kind();
}
```
Safe messages (fixed strings, constants in `JobFailure` as `static` factories are optional):

| Kind | Message |
|---|---|
| PRIVATE | `This video is private or needs a sign-in.` |
| UNSUPPORTED | `This link is not a supported video.` (live streams: `Live streams are not supported.`) |
| TOO_LONG | `The video is longer than 3 hours.` (uses the configured limit, formatted in whole hours/minutes) |
| FAILED | `Could not process this video. Please try again later.` |
| TIMEOUT | `Processing took too long and was stopped.` |

## `media.ProcessRunner`
```java
public interface ProcessRunner {
  /** Runs args[0] with args[1..] (no shell). Waits at most timeout. Never throws on a non-zero exit. */
  ProcessResult run(List<String> args, Duration timeout);   // throws JobFailure(TIMEOUT | FAILED)
}
public record ProcessResult(int exitCode, String stdout, String stderr) {}
```
`DefaultProcessRunner` (`@Component`-free, created in `MediaConfig`):
- `new ProcessBuilder(args)`, stdout and stderr read concurrently on two threads (no deadlock on large output); each capped at 8 MiB (extra output is read and discarded, so the pipe keeps draining).
- On timeout (one algorithm, also used for interrupt): snapshot `process.descendants()`, call `destroyForcibly()` on each descendant and then on the process (no graceful phase), wait up to 5 s for the process to exit, close both streams so the reader threads end, join the readers for at most 5 s (reader threads are daemon threads), then throw `JobFailure(TIMEOUT)`. The runner never waits for output without a bound.
- **Cleanup budget:** one 5-second budget per `run` call, computed once as a deadline when cleanup starts and shared by the process-exit wait and all reader joins (not 5 s each). Worst case wall time: `timeout + 5 s + small` on timeout.
- **Normal exit with a lingering pipe holder** (parent exits, a child keeps stdout/stderr open): after the parent exits, the readers are joined until the cleanup budget (5 s from parent exit) is spent. Then both streams are closed, the readers end, and `run` returns `ProcessResult(exitCode, stdoutSoFar, stderrSoFar)`. This is not a failure and not a `TIMEOUT`: the kind follows the exit code as usual (exit 0 is success). The orphaned child is not killed, because a dead parent has no descendants to enumerate; yt-dlp and ffmpeg do not daemonize. The overall `timeout` covers only the wait for the parent; the drain budget is separate. A test asserts `run` returns within about 5 s, with the exit code and the output seen so far.
- Start `IOException` and interrupt: `JobFailure(FAILED)` (interrupt flag restored, process killed).
- `timeout` must be positive, else `IllegalArgumentException`.

## `media.UrlValidator`
```java
public class UrlValidator {
  /** Returns the URL unchanged when valid. @throws JobFailure(UNSUPPORTED) otherwise. */
  public String validate(String url);
}
```
Rejected, all as `UNSUPPORTED`:
- null, blank, longer than 2048 chars, any whitespace or ISO control char (`Character.isWhitespace`, `isISOControl`).
- not parseable by `java.net.URI`; scheme not `http`/`https` (case-insensitive); host missing; `userInfo` present.
- host `localhost` or ending `.localhost` (case-insensitive, trailing dot ignored).
- host is a literal IP: IPv4 dotted quad, or IPv6 in `[...]`, that is loopback, any-local (`0.0.0.0`, `::`), link-local (`169.254/16`, `fe80::/10`), site-local/private (10/8, 172.16/12, 192.168/16), IPv6 unique-local `fc00::/7` (checked explicitly on the first byte: `(b0 & 0xFE) == 0xFC`, since `isSiteLocalAddress` does not cover it), 100.64/10, or an IPv4-mapped IPv6 of those. Checked with `InetAddress` on the parsed literal only; no DNS lookup is ever made.
- host made only of digits and dots that is not a valid dotted quad (e.g. `2130706433`, `127.1`): rejected as an ambiguous IP form.
No DNS names are resolved. Public IPs and normal DNS names pass.

## `media.VideoMetadata`
```java
public record VideoMetadata(String title, Duration duration) {}
```

## `media.YtDlpClient`
```java
public class YtDlpClient {
  public YtDlpClient(ProcessRunner runner, UrlValidator validator, AppProperties.Jobs jobs);

  /** @throws JobFailure UNSUPPORTED (bad URL, live/no duration), TOO_LONG, PRIVATE, FAILED, TIMEOUT */
  public VideoMetadata fetchMetadata(String url);

  /** Downloads into jobDir as source.<ext>. Returns that file. @throws JobFailure as above */
  public Path downloadAudio(String url, Path jobDir);
}
```
Commands (list items, in this order; URL is always last, directly after `--`):
- metadata: `yt-dlp --no-playlist --playlist-items 1 --no-warnings --skip-download --dump-single-json -- <url>`, timeout `jobs.metadataTimeout`.
- download: `yt-dlp --no-playlist --playlist-items 1 --no-warnings -f bestaudio[ext=m4a]/bestaudio -o <jobDir>/source.%(ext)s -- <url>`, timeout `jobs.downloadTimeout`.
  In `-o`, every `%` in the `<jobDir>` part is escaped as `%%` (yt-dlp treats `%` as a template marker); `source.%(ext)s` is left as is. The file lookup after the download uses the real, unescaped `jobDir`.

Rules:
- Both methods call `validator.validate(url)` first.
- Exit code non-zero: log `stderr` (truncated to 2000 chars) at WARN, throw `JobFailure(StderrClassifier.classify(stderr))`.
- Metadata JSON with `_type` equal to `playlist` or `multi_video` (a playlist-only URL, which `--no-playlist` does not turn into one video) is `JobFailure(UNSUPPORTED)`. `--playlist-items 1` on both commands is a second guard so a collection can never download more than one item; a download that leaves more than one `source.*` file is `JobFailure(FAILED)`.
- Metadata exit 0 but stdout is not a JSON object, or `title` missing/blank: `JobFailure(FAILED)`. Missing, null, non-numeric or `<= 0` `duration`, or `is_live == true`: `JobFailure(UNSUPPORTED)`. `duration` (seconds, may be fractional) is rounded up to whole seconds. The rounding is upward, so 10800.1 s becomes 10801 s and fails; it is never rounded down across the limit. `duration > jobs.maxVideoDuration`: `JobFailure(TOO_LONG)`; exactly equal passes.
- Before running, `downloadAudio` deletes every existing `source.*` file (also `.part`/`.ytdl`) in `jobDir`, so a retry that changes the extension (m4a then webm) never returns an old file and a stale file cannot count as success. `jobDir` must exist (it is created by `JobStore`); else `JobFailure(FAILED)`.
- Download exit 0 but no file matching `source.*` (ignoring `*.part`, `*.ytdl`) in `jobDir`: `JobFailure(FAILED)`. If more than one matches: `JobFailure(FAILED)`.
- `fetchMetadata` is called by the caller before `downloadAudio`; `downloadAudio` does not re-check duration.

`StderrClassifier` (package-private), one table, matched on the lower-cased stderr, first row that matches wins:

| Substrings (any) | Kind |
|---|---|
| `private`, `sign in`, `members-only`, `age` | PRIVATE |
| `unsupported url` | UNSUPPORTED |
| anything else, including empty stderr | FAILED |

This is the table the owner agreed (todo T8, brief Q5). The bare `age` substring is broad (it matches
words such as "language" or "message") and can misclassify an unrelated failure as PRIVATE. This is a
known, accepted tradeoff; narrowing it needs an owner decision. A stderr matching both rows is PRIVATE.

```java
static JobFailure.Kind classify(String stderr);
```

## `media.FfmpegRunner`
```java
public class FfmpegRunner {
  public FfmpegRunner(ProcessRunner runner, AppProperties.Jobs jobs);

  /** Creates <jobDir>/audio.wav (16 kHz, mono, pcm_s16le). Returns it. @throws JobFailure FAILED | TIMEOUT */
  public Path toWav(Path source, Path jobDir);

  /** Splits wav into <jobDir>/chunks/chunk-000.wav ... of about 10 minutes. Returns them in order. */
  public List<Path> split(Path wav, Path jobDir);
}
```
Commands, timeout `jobs.ffmpegTimeout`:
- `ffmpeg -nostdin -hide_banner -loglevel error -y -i <source> -vn -ar 16000 -ac 1 -c:a pcm_s16le <jobDir>/audio.wav`
- `ffmpeg -nostdin -hide_banner -loglevel error -y -i <wav> -f segment -segment_time 600 -c copy <jobDir>/chunks/chunk-%03d.wav` (`chunks/` is created before; existing `chunk-*.wav` are deleted first).
`toWav` deletes an existing `audio.wav` before running, and after exit 0 requires the file to exist and be non-empty, else `JobFailure(FAILED)`. `split` requires `wav` to exist, else `FAILED`.
Non-zero exit: log stderr, `JobFailure(FAILED)`. After a successful split, zero chunks: `JobFailure(FAILED)`.

`ChunkSplitter` (package-private): `static final int SEGMENT_SECONDS = 600; static List<String> splitArgs(Path wav, Path chunksDir); static List<Path> collect(Path chunksDir)` (files matching `chunk-\d{3}\.wav`, sorted by name; a final chunk shorter than 10 min is expected).

## Configuration
`AppProperties.Jobs` becomes:
```java
public record Jobs(Path workDir, Duration maxVideoDuration, Duration retention,
    Duration metadataTimeout, Duration downloadTimeout, Duration ffmpegTimeout) {}
```
`application.yml` under `app.jobs`:
```yaml
metadata-timeout: ${METADATA_TIMEOUT:PT1M}
download-timeout: ${DOWNLOAD_TIMEOUT:PT30M}
ffmpeg-timeout: ${FFMPEG_TIMEOUT:PT30M}
```
Existing code that builds `Jobs` (`TelegramConfigTest`) must be updated by the Developer. `SPEC.md` env var list gets the three names (Developer/orchestrator).

## `config.MediaConfig`
```java
@Configuration(proxyBeanMethods = false)
public class MediaConfig {
  @Bean ProcessRunner processRunner();                       // DefaultProcessRunner
  @Bean UrlValidator urlValidator();
  @Bean YtDlpClient ytDlpClient(ProcessRunner, UrlValidator, AppProperties);
  @Bean FfmpegRunner ffmpegRunner(ProcessRunner, AppProperties);
}
```
Beans are `@ConditionalOnMissingBean` on `ProcessRunner` only, so the functional harness can supply its fake.

## Guardrails added by the architect (rationale)
Not owner-agreed product requirements; they close gaps in the agreed rules.
- Unspecified (`0.0.0.0`, `::`), shared `100.64/10`, `.localhost` names and ambiguous numeric hosts (`2130706433`, `127.1`) reach loopback or private ranges in some stacks, so a literal-IP check alone would miss them.
- Output capped at 8 MiB per stream: a misbehaving tool must not exhaust memory.
- Malformed metadata (not JSON, blank title, non-positive duration) is `FAILED` or `UNSUPPORTED`, never success, so a bad response cannot start a download.
- Fixed `source.<ext>`, `audio.wav` and `chunks/chunk-NNN.wav` names: no title-derived paths, and chunk order is by name.
- Success needs the expected file: exit 0 without a source file, WAV or chunks is `FAILED`.
- `chunks/` is cleaned of stale `chunk-*.wav` before a split, so old output is never returned.
