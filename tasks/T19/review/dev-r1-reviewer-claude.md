VERDICT: APPROVED

## Findings
- [Suggestion] src/main/java/com/video2podcast/transcript/TranscriptionClient.java:31-36 - the new "<p>Chunks are sent in detect mode..." paragraph sits inside the `@param progress` tag, so Javadoc renders it as part of that parameter. Move it above the tags, into the method description.
- [Suggestion] src/main/java/com/video2podcast/transcript/WhisperClient.java:626 - the Javadoc says `IllegalArgumentException` is thrown "if language is blank or not a letters-only value". The code throws for anything that is not "auto", "en" or "ru" (for example "de"), which is also what api.md says. Say that in the Javadoc.
- [Suggestion] src/test/java/com/video2podcast/transcript/TranscriptionClientTest.java:2003 (`aLanguageOtherThanEnglishAndRussianIsRefusedAtOnce`) - the refusal test checks that no transcript is written, but not that the temporary audio (audio.wav and chunks) is still there. api.md says PipelineService deletes it, not TranscriptionClient. One `exists()` assertion would pin that contract.
- [Suggestion] src/main/java/com/video2podcast/summary/TokenEstimator.java:15 - RU = 2 characters per token is an unmeasured estimate, as api.md §7 says. The manual Russian check must compare `prompt_eval_count` with the estimate. The placeholder in docs/host-setup.md asks for this, so no change is needed now. Do not close the manual check without that comparison.

No Critical or Important findings.

What I checked:
- TranscriptionClient: the language is selected on the first chunk that has text. Empty chunks stay in auto mode and their metadata is ignored. en/ru pins every later chunk. Anything else throws UNSUPPORTED_LANGUAGE before another chunk is sent and before any transcript write. A later answer cannot change the language. A retry reuses the mode of the first try. When every chunk is empty the result is NO_SPEECH before `language` is read, so a null `language` cannot reach the Transcription.
- WhisperClient: the form fields and their order match api.md, `detect_language` is always false, and the empty-text decision uses `TranscriptMerger.normalize`, so U+00A0 counts as empty. Missing, null, non-string or blank language with text gives a non-connection `WhisperException`, so it gets the single retry and then FAILED. An unknown non-blank value (including an NBSP-only one) goes through as "unknown" and is refused.
- WhisperLanguages: the result is only a-z or "unknown", so it is safe in the message. The 99-name table is plausible, and the real server's full names ("russian", "german") are covered by tests.
- JobFailure: the new kind is appended, the `messageOf` switch stays exhaustive, `isServiceOutage()` is false, and the message text is exact. It is therefore not parked, and the existing terminal-failure path in PipelineService/T18 applies.
- SummaryService, Prompts and TokenEstimator: each of the four prompts takes the language, and map, round and final reduction all use the language of the call. The per-language budget keeps the "too small" and "too large" guards and applies them to both languages. The MIN_RATIO truncation check uses the language's estimate. The prompts drop the "even if not in English" clauses and are still written in English.
- PipelineService: it passes `Transcription.language()` to `summarize`, and nothing else changes.
- Unit tests check behaviour. These cases are covered: detect and pinned modes, field values, normalization, unsupported versus malformed metadata, silent start, refusal after a silent start, detect-mode retry, pinned retry, and a later answer that cannot change the language. Prompts are checked per language and stage, and the RU budget and a long RU run are checked to fit `num_ctx`.
- docs/host-setup.md: `-l auto`, the contract table, the curl examples for both modes and the language section are accurate. The old T17 paragraph is marked historical. The manual-check block is an explicit "not done yet" placeholder, so it does not claim a check that was not run.

## Requirements
- R1: met - first chunk sent with auto, verbose_json, translate=false, detect_language=false, temperature=0.0. Names and codes are normalized.
- R2: met - one language per job, silent start handled, retries keep their mode, nothing stored between jobs.
- R3: met - the transcript path and merge are unchanged, written once after all chunks succeed. The Cyrillic UTF-8 text is covered by unit tests.
- R4: met - the language reaches every prompt and stage, the budget uses RU 2 chars/token, and the safeguards are unchanged.
- R5: met in production code - UNSUPPORTED_LANGUAGE with the exact message, no later chunk, no Ollama call, not an outage. Directory deletion and recovery reporting go through the existing PipelineService/T18 paths, and the Tester covers them in IT.
- R6: met - the single retry is kept, broken language metadata is a retried WhisperException, unknown values are refused, and connection failures stay outages.
- R7: met in this zone - no interface text or callback changed. Interface behaviour is covered by IT.
- R8: met in this zone - filename code untouched. Cyrillic upload names are covered by IT.
- R9: partly met, as the orchestrator stated - host-setup.md is updated and the manual-check block is a marked placeholder. SPEC.md is updated by the orchestrator after the code commit.
