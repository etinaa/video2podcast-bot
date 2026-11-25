# T19 questions and answers

## Q-analyst-1 · analyst → architect
Research found that the installed whisper-server symlink points to whisper.cpp 1.9.6; its help defaults to English even without `-l en`. T3's verbose fixture uses full names (`language: english`), not codes. Proposed observable contract: first chunk uses `language=auto`, `response_format=verbose_json`, `translate=false`, `detect_language=false`; read the top-level `language` and `text`, normalize Whisper names to codes (also accept codes), and pin subsequent requests to the selected `en`/`ru`. Later response format may stay plain `json`. `detect_language=true` exits after detection and cannot supply first-chunk transcript text. Please confirm the wire contract/normalization, including whether later chunks use `json` or `verbose_json`, so existing T9 request assertions can be updated consistently. Requirements/scenarios will describe language behavior and leave later response format to your API contract.

## A: Q-analyst-1 · architect
Confirmed, with these details (full text in `tasks/T19/api.md`):
- First request of a job: `language=auto`, `response_format=verbose_json`, `translate=false`, `detect_language=false`, `temperature=0.0`. The bot reads the top-level `language` and `text`.
- Normalization: lower-case and trim; Whisper full names ("english", "russian", "german") map to ISO codes through a table of Whisper's languages; a bare code is accepted too. `en` and `ru` are supported; anything else is refused with its code (an unknown value is shown as `unknown`).
- Every later chunk is pinned to the selected code (`language=en|ru`, `response_format=json`, `translate=false`, `detect_language=false`); the language of those answers is not read.
- One addition to your requirements: the language is taken from the first chunk that has non-empty text. If chunk 1 is silent (music, intro), it stays on `auto` and the next chunk is sent with `auto` again; the refusal and the pin happen on the first chunk with text. If no chunk has text: `NO_SPEECH` as now. Please put this in requirements and a scenario.
- A refusal is a new `JobFailure.Kind.UNSUPPORTED_LANGUAGE`, not a service outage: nothing goes to the deferred queue, no later chunk is sent and Ollama is not called.
- Russian text needs its own characters-per-token value in the summary budget (2 instead of 3); this is internal and needs no scenario.

## Q-analyst-2 · analyst → owner
The brief says unsupported languages stop after detection on the first chunk. Architect proposes ignoring a silent first chunk's detected language and trying `auto` again on the next chunk, until a chunk has nonempty text; only then pin `en`/`ru` or reject another language. This avoids a silent/music intro selecting an arbitrary language, but changes the literal first-chunk rule. Confirm this first-nonempty-chunk exception? If every chunk is empty, keep the existing `No speech was found in this video.` failure.

## Q-analyst-3 · analyst → architect
A: Q-analyst-1 says unknown values are refused as `unknown`. Please distinguish missing/null/non-string/blank `language` in a nonempty first response (broken response → existing immediate retry, then transcription failure?) from a nonblank unrecognized value such as `klingon` (terminal unsupported with `unknown`?). I propose that distinction to preserve T9's malformed-response contract. Also Q-analyst-2 asks owner to confirm the silent-first-chunk exception because it changes the brief's literal first-chunk rule. Drafts will mark that exception pending.

## A: Q-analyst-3 · architect
Yes, the distinction is in `api.md` section 4 and `flows.md`. With non-blank `text` in detect mode: `language` missing, null, non-string or blank gives `WhisperException` (not a connection error), so the single retry of the chunk and then `FAILED` "Transcription failed. Please try again later.", as for any malformed T9 answer. A non-blank string, known or not (`klingon`, `german`), is a valid answer: `en`/`ru` are selected, anything else is refused with `Detected language: <code>.` (`unknown` when it is not a Whisper name and not 2-3 letters). With blank `text` the language is not checked and not used. Q-analyst-2: agreed that the owner decides; my design assumes the exception and changes only `TranscriptionClient` if the owner says no (detect on chunk 1 whatever its text).

## A: Q-analyst-2 · owner
Yes. The language is taken from the first chunk with non-empty text: a silent first chunk (music, intro) is sent with `auto` again on the next chunk, and the pin to `en`/`ru` or the refusal happens on the first chunk with text. If no chunk has text, the existing "No speech was found in this video." failure stays.

## Q-orchestrator-1 · orchestrator → architect
`components.md` names the prompt methods `Prompts.short(Language)`, `map`, `reduceFinal`, `reduceRound`, while `api.md` section 7 has `shortPrompt`, `mapPrompt`, `reduceFinalPrompt`, `reduceRoundPrompt`. `short` is a Java keyword. Please align `components.md` with `api.md`. Also for the record: `SPEC.md` is edited by the orchestrator (as in T18), after the code is committed; R9 and `api.md` section 9 need no change for that.

## A: Q-orchestrator-1 · architect
Fixed: `components.md` now uses `shortPrompt`, `mapPrompt`, `reduceFinalPrompt`, `reduceRoundPrompt`, as in `api.md` section 7 (`short` is a keyword). Noted that the orchestrator edits `SPEC.md` after the code is committed; the SPEC lines in `api.md` section 9 stay as the list of what must change.
