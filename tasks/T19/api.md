# T19 contracts

No new configuration property, no new environment variable, no change to `job.json`.

## 1. whisper-server wire contract

`POST {base}/inference`, `multipart/form-data`. Fields, in this order:

| Field | First chunk(s), "detect" mode | Later chunks, "pinned" mode |
|---|---|---|
| `file` | the WAV chunk | same |
| `response_format` | `verbose_json` | `json` |
| `language` | `auto` | `en` or `ru` |
| `translate` | `false` | `false` |
| `detect_language` | `false` | `false` |
| `temperature` | `0.0` | `0.0` |

- `detect_language=true` is never sent: it makes the server stop after detection and return no text.
- Detect-mode answer (200): JSON with a top-level `text` (string) and a top-level `language` (string: a full name such as `russian`, as in the T3 fixture, or a code). `segments` and other fields are ignored.
- Pinned-mode answer: `{"text": "..."}`; any `language` field is ignored.
- Errors, retries and timeouts: unchanged from T9.
- The server flag `-l` is only a default for manual `curl` calls; the bot always sends `language`. `docs/host-setup.md` changes `-l en` to `-l auto`.

## 2. `job.Language`

```java
package com.video2podcast.job;

public enum Language {
  EN("en", "English"),
  RU("ru", "Russian");

  public String code();          // "en", "ru"
  public String displayName();   // "English", "Russian"; used in prompts
  /** Exact, case-sensitive match on code(); empty for anything else. */
  public static Optional<Language> fromCode(String code);
}
```

## 3. `transcript.WhisperLanguages` (package-private, final, static only)

```java
/**
 * Whisper's own language list (whisper.cpp names and codes, 99 entries, e.g. "russian" -> "ru",
 * "german" -> "de", "chinese" -> "zh").
 * Trims and lower-cases (Locale.ROOT). A known full name gives its code; a known code is returned
 * as is. Anything else: the value itself if it matches [a-z]{2,3}, otherwise "unknown".
 * Never returns null, never returns text that contains anything but a-z and "unknown".
 * Called only with a non-blank string (WhisperClient rejects the other cases before); a null or
 * blank argument still gives "unknown".
 */
static String toCode(String whisperLanguage);
```

The result is placed in a user message, which is why it is restricted to letters.

## 4. `transcript.WhisperClient`

```java
public record WhisperResult(String text, String language) {}
// text: the raw "text" field, not normalized (as before).
// language: ISO code from WhisperLanguages.toCode(...) in detect mode when normalized text is not empty;
//   null in pinned mode and in detect mode when normalized text is empty (language is not checked then).

/**
 * @param language "auto" (detect mode) or a code such as "en" or "ru" (pinned mode); anything else is
 *     an IllegalArgumentException; null is a NullPointerException
 * @throws WhisperException as before; additionally in detect mode, when "text" is not empty after TranscriptMerger.normalize (same Unicode whitespace rule as the merger, so U+00A0 counts as whitespace; not String.isBlank) and
 *     "language" is missing, null, not a string, or blank after trim ("Whisper answer has no
 *     language", connection=false). That is a broken answer: the existing single retry of the
 *     chunk, then FAILED "Transcription failed...". A non-blank string that WhisperLanguages does
 *     not know (e.g. "klingon") is NOT an error here: it is returned as "unknown" (or as itself if
 *     it is 2-3 letters) and TranscriptionClient refuses it as an unsupported language.
 */
public WhisperResult transcribe(Path chunk, String language) throws WhisperException;
```

The old `String transcribe(Path)` is removed; `probe(Duration)` is unchanged. `WhisperResult` is public, in package `com.video2podcast.transcript`.

## 5. `transcript.TranscriptionClient`

```java
public record Transcription(Path transcript, Language language) {}   // nested type of TranscriptionClient

/**
 * As before, and: detects the language, uses it for all following chunks and returns it.
 * Language rule: chunks are sent in "auto" mode until one returns text that is not empty after
 * TranscriptMerger.normalize; its language decides. en/ru: selected, every later chunk is pinned.
 * Any other code: JobFailure UNSUPPORTED_LANGUAGE at once, no later chunk is sent, no transcript.txt
 * is written, the temporary audio is not deleted here (PipelineService deletes the directory).
 * Retry of a failed chunk uses the same mode as the first try.
 *
 * @throws JobFailure NO_SPEECH, UNSUPPORTED_LANGUAGE, SERVICE_UNREACHABLE or FAILED
 */
public Transcription transcribe(Path jobDir, List<Path> chunks, ProgressListener progress);
```

`ProgressListener` is unchanged (one call per chunk, n from 1, also for chunks sent in detect mode again).

## 6. `job.JobFailure`

```java
enum Kind { ..., UNSUPPORTED_LANGUAGE }          // appended; existing ones unchanged

/** Kind UNSUPPORTED_LANGUAGE; message exactly:
 *  "Only English and Russian videos are supported. Detected language: <code>."
 *  code is a WhisperLanguages.toCode result (a-z only, or "unknown"). */
public static JobFailure unsupportedLanguage(String code);
```

- `messageOf(UNSUPPORTED_LANGUAGE)` (the switch must stay exhaustive) returns `"Only English and Russian videos are supported."`, used only if someone calls `of(Kind)`.
- `isServiceOutage()` is false for it. The message is sent through `Html.escape` by `UrlMessageHandler` as every failure message.
- Example: `Only English and Russian videos are supported. Detected language: de.`

## 7. `summary`

```java
// Prompts (package-private, final): the four constants become methods
static String shortPrompt(Language language);
static String mapPrompt(Language language);
static String reduceFinalPrompt(Language language);
static String reduceRoundPrompt(Language language);
```

Text: the current four English prompts with "English" replaced by `language.displayName()`, and the clauses "even if the transcript is not in English" / "even if the text is not in English" / "even if the input is not in English" removed. The prompts stay written in English. Each one must say that the answer is in `displayName()`, keeps `"- "` bullets and gives only the bullets. Example (`RU`, short):

> You summarize a video transcript. Write the final summary as Russian bullet points. Start every line with "- ". Use at most 8 bullets and keep each bullet short. Answer with the bullet points only, without a preamble.

```java
// TokenEstimator (package-private)
static int charsPerToken(Language language);                       // EN 3, RU 2
static int estimate(String text, Language language);               // ceil(length / charsPerToken)
static long requestEstimate(String system, String user, Language language);

// SummaryService
public String summarize(String transcript, Language language);     // NPE on null arguments
int maxChunkChars(Language language);
int partialMaxChars(Language language);
```

- The constructor computes the text budget per language: `numCtx - OUTPUT_RESERVE - max prompt tokens (4 prompts of that language) - TEMPLATE_OVERHEAD`, `maxChunkChars = budget * charsPerToken(language)`. It still throws `IllegalArgumentException` for a `num-ctx` that is too small or too large, now checked for every language. With the default `OLLAMA_NUM_CTX=8192` the Russian chunk is about 2/3 of the English one, so long Russian videos need more map steps; this is expected.
- Everything else (map/reduce rounds, `MIN_RATIO` truncation check, `SUMMARY_CAP` 1500, `SummaryFormatter`) is unchanged and uses the language of the call.
- `LlmClient.complete(system, user, maxOutputTokens)` is unchanged.
- The characters-per-token value for Russian is a safe estimate, not a measurement. The manual check on a Russian video must compare `prompt_eval_count` (log or `OllamaLlmClient`) with the estimate, and the Developer lowers the value if the real count is higher than the estimate.

## 8. `job.PipelineService`

`execute` becomes: `Transcription t = transcription.transcribe(dir, chunks, progress::transcribing);` then `summary.summarize(readTranscript(t.transcript()), t.language())`. No other change; a `JobFailure` from `transcribe` already deletes the job directory in `run`.

## 9. Docs

- `docs/host-setup.md`: whisper-server command with `-l auto` (`-mc 0` stays); the "whisper-server contract" table and the example `curl` for both modes; the old "Language: Whisper is forced to English" paragraph replaced by the result of the manual check on a Russian video; the Ollama contract example prompt mentions the language.
- `SPEC.md`: line 37 (language not forced; auto-detection; English and Russian), acceptance criteria 3 and 5 (transcript in the language of the video), out of scope line 222 (other languages are refused; translation stays out), and a new line for the refusal message.
- `docs/decisions.md`: D18 (written).
