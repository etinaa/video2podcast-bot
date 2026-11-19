# T10 contracts

Package `com.video2podcast.summary`.

## Configuration

```java
public record Ollama(String baseUrl, String model, int numCtx, Duration timeout) {}
```

| Property | Env | Default | Meaning |
|---|---|---|---|
| `app.ollama.base-url` | `OLLAMA_BASE_URL` | unchanged | a trailing `/` is dropped |
| `app.ollama.model` | `OLLAMA_MODEL` | `qwen3:32b` | unchanged; must not be blank |
| `app.ollama.num-ctx` | `OLLAMA_NUM_CTX` | `8192` | context window sent as `options.num_ctx` on every request |
| `app.ollama.timeout` | `OLLAMA_TIMEOUT` | `PT10M` | wait for one answer (per request). The connection wait is a fixed 5 s. |

Constants (not configurable): `OUTPUT_RESERVE = 1024` tokens, `TEMPLATE_OVERHEAD = 32` tokens, `CHARS_PER_TOKEN = 3`, `MIN_RATIO = 0.5`, `MAX_ROUNDS = 8`, `SUMMARY_CAP = 1500`.

## LlmClient

```java
public interface LlmClient {
  /**
   * One chat completion. Returns the non-blank answer text and the number of prompt tokens the
   * backend says it evaluated.
   * @param maxOutputTokens upper bound for the answer (sent as options.num_predict)
   * @throws LlmException connection, timeout or any invalid answer
   */
  LlmReply complete(String system, String user, int maxOutputTokens) throws LlmException;
}

public record LlmReply(String content, long promptTokens) {}   // content non-blank, promptTokens >= 1

public class LlmException extends Exception {
  public enum Reason { CONNECTION, TIMEOUT, OTHER }
  public LlmException(String message, Reason reason, Throwable cause);
  public Reason reason();
}
```

The message is for logs only and never reaches the chat.

## OllamaClient

```java
public class OllamaClient implements LlmClient {
  public OllamaClient(AppProperties.Ollama config);        // HttpClient: connectTimeout 5 s, redirects NEVER
  OllamaClient(AppProperties.Ollama config, HttpClient http);   // tests
}
```

Request: `POST {base-url}/api/chat`, `Content-Type: application/json`, request timeout `app.ollama.timeout`:

```json
{
  "model": "<app.ollama.model>",
  "stream": false,
  "think": false,
  "keep_alive": "10m",
  "options": { "num_ctx": <app.ollama.num-ctx>, "num_predict": <maxOutputTokens>, "temperature": 0.2 },
  "messages": [
    { "role": "system", "content": "<system>" },
    { "role": "user", "content": "<user>" }
  ]
}
```

Every request, short, map and reduce alike, has all of these fields. Built with `Json.MAPPER`.

Mapping (checked in this order):

| Situation | `LlmException.Reason` |
|---|---|
| `ConnectException`, `UnresolvedAddressException`, `HttpConnectTimeoutException` (test before `HttpTimeoutException`) | `CONNECTION` |
| `HttpTimeoutException` | `TIMEOUT` |
| other `IOException`, `InterruptedException` (flag restored) | `OTHER` |
| status not 2xx (body logged, at most 200 chars) | `OTHER` |
| 2xx and body empty, not JSON, or not a JSON object | `OTHER` |
| `message.content` missing, null, not a string, or blank | `OTHER` (also the thinking-only fixture) |
| token count invalid (below) | `OTHER` |

Token count: `promptTokens = prompt_eval_count`, which must be an integral JSON number >= 1, else `OTHER`. `prompt_eval_cached_count` is ignored: measured on the local Ollama (0.40.16, qwen3:32b), the same request sent twice returned `prompt_eval_count` 54 both times and `prompt_eval_cached_count` 0, then 53, so the cached count is a subset of `prompt_eval_count` and adding it would double-count. `message.thinking` and every other field are ignored. `done_reason: "length"` with non-blank content is accepted (logged at WARN). The returned `content` is `strip()`ped.

## SummaryService

```java
public class SummaryService {
  public SummaryService(LlmClient llm, AppProperties.Ollama config);
  /**
   * @return English bullet text, "• " per line, at most 1,500 UTF-16 chars, never blank
   * @throws NullPointerException if transcript is null
   * @throws JobFailure SUMMARY_UNREACHABLE, TIMEOUT or FAILED (messages below)
   */
  public String summarize(String transcript);
}
```

The constructor throws `IllegalArgumentException` (application start fails, nothing is sent) when `numCtx <= 0` or `textBudget < 2 * OUTPUT_RESERVE` (see Budget; with the default 8192 the budget is about 7,000, so `OLLAMA_NUM_CTX` below roughly 3,300 is rejected; this also keeps `partialMaxChars` at least 1,500). A blank transcript gives `JobFailure.summaryFailed(null)` without any request.

### TokenEstimator

```
estimate(text)        = ceil(text.length() / 3.0)                 // UTF-16 units; deliberately high for English
requestEstimate(s, u) = estimate(s) + estimate(u) + TEMPLATE_OVERHEAD
```

### Budget

```
promptTokens   = max(estimate(SHORT), estimate(MAP), estimate(REDUCE_FINAL), estimate(REDUCE_ROUND))   // the four actual system prompts
textBudget     = numCtx - OUTPUT_RESERVE - promptTokens - TEMPLATE_OVERHEAD   // tokens of user text per request
maxChunkChars  = textBudget * 3
partialMaxChars = (maxChunkChars - 6) / 4      // integer division; bound for every intermediate answer
```

Invariant for every outgoing request: `requestEstimate(system, user) + maxOutputTokens <= numCtx` with `maxOutputTokens = OUTPUT_RESERVE`. A user text fits when `estimate(user) <= textBudget`, equivalently `user.length() <= maxChunkChars`. Boundary tests: `maxChunkChars - 1`, `maxChunkChars`, `maxChunkChars + 1`; a different `numCtx` changes `maxChunkChars` by `3 * delta`.

### TextChunker

`List<String> split(String text, int maxChars)`:
- Text of at most `maxChars` is returned as one chunk (unchanged, not stripped).
- Otherwise: from `start`, the cut is at `start + maxChars`. If a whitespace character exists in `[start + maxChars/2, start + maxChars]`, cut after the last one; otherwise cut at `start + maxChars`, moved back by one if it would split a surrogate pair. Each chunk is `strip()`ped; a chunk that becomes empty is dropped. The next `start` is the cut. Every chunk has `length() <= maxChars`, and the concatenation of the chunks equals the text with only whitespace removed. One unbroken word longer than `maxChars` is cut into pieces of `maxChars`. Progress per iteration is at least 1 char, so there is no endless loop.

### Algorithm

```
text = transcript.strip()
if requestLen fits (text.length() <= maxChunkChars):        // short path
    reply = call(SHORT, text, check)          → format(reply) → return
partials = for each chunk in split(text, maxChunkChars): clip(call(MAP, chunk), partialMaxChars)    // sequential, in order
round = 0
while true:
    joined = join(partials, "\n\n")
    if joined.length() <= maxChunkChars:
        return format(call(REDUCE_FINAL, joined))
    if ++round > MAX_ROUNDS: throw summaryFailed
    groups = greedy in-order grouping of partials, each group joined by "\n\n", length <= maxChunkChars
             (every partial is at most partialMaxChars, so 4 partials plus 3 separators always fit: a group has >= 4 partials except possibly the last)
    partials = for each group: clip(call(REDUCE_ROUND, group), partialMaxChars)
```

Request counts: short = 1; N chunks whose partials fit = N + 1; otherwise N + groups + ... + 1. Exactly one `format` call, on the last answer. Any exception stops everything: no later request is sent and no partial is returned.

### Truncation check (every call, before the content is used)

```
expected = requestEstimate(system, user)
accept  iff  reply.promptTokens() >= ceil(expected * MIN_RATIO)
```

`promptTokens < ceil(expected * 0.5)` gives `JobFailure.summaryFailed(null)`. The estimate overstates English by about 1.3-1.5x, so a complete prompt is at about 0.65-0.8 of `expected`; T3's truncated case (1,026 of 4,831 real tokens) is far below 0.5. Boundary tests: `threshold - 1` rejected, `threshold` accepted. Missing or invalid counts already failed in `OllamaClient` (`OTHER` → `FAILED`), so the check cannot be skipped.

### Bounding intermediate answers

`num_predict` limits tokens, not characters, so `SummaryService` enforces the character bound itself. Every map and round answer goes through `SummaryFormatter.clip(content, partialMaxChars)` before it is stored: if `content.length() <= partialMaxChars` it is unchanged; otherwise whole trailing lines are dropped until it fits; if one line remains and is still too long it is cut to `partialMaxChars` (not splitting a surrogate pair). No marker normalization, no `…`. A clip is logged at WARN (sizes only). Consequence: the joined input of any reduction group is at most `maxChunkChars`, and each round at least quarters the list (`n -> ceil(n/4)`), so the round limit is a safeguard and never the only protection against an oversized request. The loop only runs when there are at least 5 partials (2 to 4 partials always fit).

### SummaryFormatter

`String format(String content)`, applied to the final answer only:
1. Split into lines, `strip()` each, drop blank lines. Remove a leading marker `-`, `*`, `•` or `–` (one marker only) when it is followed by whitespace or ends the stripped line, so a line that is only a marker (`"-"`, `"- "` stripped to `"-"`) becomes empty. Re-strip. Drop a line that is blank after that. A marker glued to text (`-5 degrees`, `*note*`) is not a marker and stays as text. Every remaining line becomes `"• " + line`. No line, so no bullet: `JobFailure.summaryFailed(null)`.
2. Join with `"\n"`.
3. Cap `SUMMARY_CAP = 1500` measured in `String.length()` (UTF-16 units; this also respects Telegram's own counting). While the text is longer than 1500, drop the last bullet. If one bullet remains and is still longer than 1500, cut it to 1499 units (one less if that would end in a high surrogate) and append `…` (so the result is at most 1500 and ends in `…`). Exactly 1500 is kept unchanged. The result is never blank and never has a broken surrogate pair.

### Prompts (exact text is the Developer's; contract)

- `SHORT` and `REDUCE_FINAL`: ask for the final summary as English bullet points, each line starting with `- `, at most 8 bullets, no preamble, each bullet short (the cap is 1,500 characters).
- `MAP`: ask for the key points of this part of a longer transcript as English bullet points (`- `), 3 to 8 bullets, no preamble.
- `REDUCE_ROUND`: ask to merge the given bullet lists into one shorter English bullet list (`- `), keeping the important points.
- All prompts: answer in English, even if the input is not.

### Failure mapping

| Cause | JobFailure kind | Message |
|---|---|---|
| `LlmException` `CONNECTION` | `SUMMARY_UNREACHABLE` | "The summary server is unreachable." |
| `LlmException` `TIMEOUT` | `TIMEOUT` | existing: "Processing took too long and was stopped." |
| `LlmException` `OTHER`, truncation, no bullets, round limit, blank transcript | `FAILED` via `JobFailure.summaryFailed(cause)` | "Summary failed. Please try again later." |

The cause is passed to `JobFailure.of(kind, cause)` / `summaryFailed(cause)` for the log only. No retry in T10: a call is made once. (T9's retry does not apply: one call costs minutes, and the later DLQ task owns re-runs.) No cleanup, no Telegram messages in T10.

## JobFailure changes

```java
Kind: ... SERVICE_UNREACHABLE, SUMMARY_UNREACHABLE      // appended; the switch in messageOf gets the new case
public static JobFailure summaryFailed(Throwable cause); // FAILED, "Summary failed. Please try again later."
```

`of(SUMMARY_UNREACHABLE)` message: "The summary server is unreachable." Existing kinds and messages do not change.

## Not in the contracts

No new HTTP endpoint, no file on disk, no change to `job.json`. T11 reads `transcript.txt`, calls `summarize`, and escapes the result for HTML.
