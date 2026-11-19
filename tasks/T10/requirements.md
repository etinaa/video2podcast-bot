# T10 requirements — Ollama and map-reduce summary

Status: analyst design ready for team review. Created: 2026-11-17. Updated: 2026-11-19.

## Objective and authority

Turn a complete English transcript into a short English bullet summary using the local Ollama host. Authority: T10 in `tasks/todo.md`, `brief.md`, `SPEC.md` (Summary, Telegram Message Format, Testing Strategy, success criterion 14), `tasks/plan.md`, and T3's captured contract in `docs/host-setup.md`. T10 depends on T3; T11 connects it to the media/transcription pipeline and Telegram.

The accepted input is the complete transcript, not only its first context window. The default inclusive three-hour video limit belongs to T8. T10 must handle the corresponding long transcript without imposing a shorter video limit; duration is not a token count. No production changes or new product choices are made by this analyst step.

## Requirements and acceptance criteria

### R1 — Explicit Ollama HTTP contract behind a replaceable interface

Provide `LlmClient` with an Ollama implementation. Backend-specific HTTP/JSON handling belongs behind that interface, so summary orchestration does not depend on Ollama response DTOs.

Acceptance:
- Send JSON to `POST /api/chat` at configured `app.ollama.base-url` (`OLLAMA_BASE_URL`), with `stream: false`, `think: false`, a nonempty configured `model`, and explicit positive `options.num_ctx` on **every** request: short, map and reduction calls alike.
- Default model is `qwen3:32b`; a configured model override is sent instead. Production base URL defaults to `http://host.containers.internal:11434`, with the existing localhost dev override preserved.
- Messages include summarization instructions and the corresponding transcript/partial-summary input. Return only `message.content` from a successful response; never substitute `message.thinking` or raw JSON.
- T3's `keep_alive: "10m"` and `temperature: 0.2` are reference settings, not additional product requirements. Per A: Q-analyst-1 / `api.md`, `app.ollama.num-ctx` (`OLLAMA_NUM_CTX`) defaults to 8192; each request reserves/sends `num_predict: 1024`. The full architecture also fixes keep-alive/temperature settings.

### R2 — Budget every complete prompt against its context

Derive input chunk capacity from the same context window actually sent in R1. Account for instructions, chat-template/message overhead and reserved output, rather than treating the whole context as transcript capacity.

Acceptance:
- The documented budgeting rule must satisfy `estimated complete input + output reserve <= num_ctx` for each outgoing call, including reduction calls.
- Unit tests cover immediately below, exactly at and immediately above the derived input limit; changing num_ctx must change the available input budget.
- Splitting preserves the complete transcript in order, without gaps or unintended duplication. Include a final remainder and input containing one very long unbroken word; a word-boundary preference must not produce an oversized chunk or an endless loop.
- Invalid settings or a context too small for overhead/output must fail before sending an oversized request.
- A character heuristic is an estimate, not the model's exact tokenizer. The Architect specifies `estimate(text) = ceil(text.length()/3)` in UTF-16 units, 32 tokens of template overhead and 1024 output tokens. User budget is context minus that reserve, overhead and the largest actual system-prompt estimate (short, map, both reduction prompts). This is an English heuristic, not a tokenizer guarantee. Reject a user budget below 2048 tokens before requests.

### R3 — Single-call short summary and complete map-reduce

Summarize a transcript that fits the input budget in exactly one request. Otherwise summarize the ordered transcript chunks and summarize their partial summaries into the final answer.

Acceptance:
- The short path sends the entire transcript once and has no redundant map or reduction request.
- If N chunks produce partials that fit one reduction request, issue N map requests followed by one reduction request. For example, three fitting partials require four requests, not three or five.
- Each map request contains its corresponding chunk; the reduction input contains every bounded partial summary in original order. Do not summarize only the first/last partial or return the concatenation as the final result.
- If all partials cannot fit one reduction request, the architecture must provide bounded, context-safe further reduction, with all partials represented. It must neither omit an entire partial nor send an oversized prompt; intermediate content clipping follows the explicit bound below. Per A: Q-analyst-3, bound every map/intermediate answer in code to `partialMaxChars = floor((maxChunkChars - 6)/4)` UTF-16 units before grouping. Keep a fitting answer unchanged; otherwise drop whole trailing lines, then hard-cut an oversized remaining line without splitting a surrogate pair. Do not normalize markers or append an ellipsis at this stage. Include every bounded partial in original order, using greedy groups joined by two LF characters. Four bounded partials plus separators always fit; the next partial count is at most `ceil(N/4)`. Allow at most eight intermediate rounds, then FAILED. Unit tests must prove these actual-length bounds even when the model returns far more characters than expected from num_predict; token count alone is not protection.
- A failed map or reduction call fails the summary as a whole. Never return an earlier partial as successful final output. Unit tests prove later requests stop after terminal failure.

### R4 — Reject empty and invalid answers

An HTTP success alone is insufficient evidence of a usable answer.

Acceptance:
- Empty or whitespace-only `message.content` fails, including the captured thinking-only T3 response. Do not use reasoning as a fallback or treat empty content as valid silence.
- Missing/null/non-string content, malformed JSON, an empty response body or a non-2xx status is a failure, not successful summary text. This includes the captured 404 missing-model response.
- Apply these checks to every map and reduction response, not only to the final answer.
- Unit tests use all three T3 Ollama fixtures and explicit malformed-response variants; extra unrelated JSON fields must not become summary text.

### R5 — Detect silent prompt truncation

Check every response's `prompt_eval_count` against the expected size of that request's complete input. A clearly truncated prompt fails the job, even if HTTP status is 200 and content looks valid.

Acceptance:
- Check short, map and reduction responses before using their content as successful input/output.
- Reproduce T3's discriminating example: a complete input measured around 4,831 tokens must not be accepted with a count of 1,026; a corresponding full-input count is accepted.
- Document the estimator, comparison tolerance and rounding. Unit tests pin both sides of the rejection threshold and the threshold itself. Do not require equality between a heuristic estimate and real tokenizer output, or compare all requests to the original transcript's total size.
- Missing, null, nonintegral, zero or negative prompt_eval_count fails as an invalid answer. The comparison threshold is `ceil(expectedInputEstimate * 0.5)`: threshold minus one fails; threshold itself passes. Per A: Q-analyst-3, use `prompt_eval_count` alone and ignore `prompt_eval_cached_count`: the Architect measured cached tokens as a subset of the total. A huge cached count must never rescue a below-threshold total. Unit tests cover both cached/uncached valid replies and low totals with a misleading high cached count.
- Valid counts on earlier calls do not excuse a truncated later map or reduction request. No final success is returned following detected truncation.

### R6 — English bullets with a hard 1,500-character final cap

The final result is plain summary text, suitable for the later Telegram formatter: English bullet points, at most 1,500 characters including bullet markers, whitespace and line separators.

Acceptance:
- Instructions for both short and reduction requests explicitly request English bullet points. Map instructions must also produce summaries useful for the English final result.
- Deterministic tests return known bullet responses and verify their content reaches the result, without JSON wrappers or thinking text. Mock tests verify the language instruction, not the actual linguistic quality of a real model.
- Enforce the length cap in application code; a prompt instruction or token generation limit alone is insufficient. Test final outputs below, exactly at and above 1,500 characters, including a single oversized bullet and supplementary Unicode characters without broken surrogate pairs.
- The capped result remains nonempty and in bullet form. Per `api.md`, strip/drop blank lines, remove one leading `-`, `*`, `•` or `–` when followed by whitespace or when it ends the stripped line, and prefix each remaining line with `• `. Join with LF and count UTF-16 units. Remove trailing bullets until within the cap; shorten a single oversized bullet to at most 1499 units plus `…`, backing off if the cut splits a surrogate pair. Exactly 1500 is unchanged. Marker-only lines (including markers followed only by whitespace) are dropped; if no line survives, fail with FAILED / `Summary failed. Please try again later.` A marker attached directly to text is preserved: `-5 degrees` becomes `• -5 degrees`, and `*note*` becomes `• *note*`. Unit tests cover all four markers, whitespace-only marker lines, mixtures of empty markers and real text, and attached-marker text (A: Q-tester-1).
- The cap applies to final summary text, before T11's HTML escaping and title/duration/buttons. T10 does not compose or send Telegram HTML. Meaningfulness and English quality of real summaries remain a later smoke-test concern.

### R7 — Clear, safe, bounded failure

An unreachable Ollama must yield a typed `JobFailure` explaining that the summary server is unreachable. Other terminal summary failures must yield a safe failure instead of a successful partial result or an unbounded wait.

Acceptance:
- Cover refused/unestablishable connections, non-2xx including 404/500, malformed/empty responses, empty content, detected truncation, and answer timeout.
- Use a fixed five-second connection wait and per-request `app.ollama.timeout` (`OLLAMA_TIMEOUT`, default `PT10M`). No retries in T10. Connection failures give SUMMARY_UNREACHABLE / `The summary server is unreachable.`; an established-request timeout gives TIMEOUT / `Processing took too long and was stopped.`; other summary failures give FAILED / `Summary failed. Please try again later.` Do not change existing transcription copy. A null transcript is a programming error (NullPointerException); a blank transcript gives FAILED without a request.
- Safe messages contain no response bodies, transcript snippets, reasoning, stack traces, input URL or local paths. Preserve diagnostic causes separately if needed.
- Existing transcription-specific SERVICE_UNREACHABLE copy must not accidentally describe an Ollama failure as a transcription outage.
- Do not create a retry queue, poll for service recovery, send Telegram messages, or clean the job directory in T10. T11 owns message delivery and pipeline cleanup.

## Test boundary and handoff

**T10 has no Telegram trigger before T11.** Do not add a test-only production HTTP endpoint, invoke/name a service or method in a Gherkin step, or manufacture a queue entry merely to exercise the summary. All current T10 behavior is verified by Developer-owned unit tests (`OllamaClientTest`, `SummaryServiceTest`, WireMock where HTTP is involved). The HTTP mock is an external observation point, but invoking an internal object to cause the request remains a unit test, not an acceptance scenario.

| Requirement | T10 unit-test responsibility | Future external acceptance in `T10-summary.feature` |
|---|---|---|
| R1 | Interface isolation, base URL/model override, every request's JSON fields | Link update leads to recorded Ollama requests with explicit model/context/flags |
| R2 | Exact budget arithmetic and boundaries, splitting/order/remainder, pathological words, invalid configuration | Long link-derived transcript is sent completely in context-safe requests |
| R3 | Short single call, N+1, intermediate clipping/Unicode, further reduction and termination, stop after failure | Recorded request counts/order/content and final Telegram summary |
| R4 | All invalid field/body variants at each stage | Empty/thinking-only and broken HTTP replies become user-visible failure |
| R5 | Estimator/tolerance threshold boundaries, missing/invalid counts at each stage | Observable failure after clearly low counts on short/map/reduce responses |
| R6 | Exact cap boundaries, Unicode, bullet normalization and single oversized bullet | Telegram summary contains known English bullets and capped summary body |
| R7 | Kinds/safe-copy mapping, waits, retries and no later calls | Connection/HTTP/timeout failure is reported in Telegram without raw diagnostics |

`src/features/T10-summary.feature` describes **future T11 acceptance**, driven by private Telegram URL messages with external-service mocks. No T10 scenario is executable yet. A: Q-analyst-2 confirms deferred testability: in Step 2 the Tester will add `not @T10` alongside the current exclusions in CucumberIT, preserving enabled tests. No runner change is required in Step 1. T11 must remove the exclusion when providing the steps. This confirmation is an agreed handoff, not evidence that Step 2 has already been performed. The Analyst does not edit the runner or step definitions. Existing test coverage must remain enabled.

Verification commands from the repository root:
- Focused implementation checks: `./mvnw test -Dtest='OllamaClientTest,SummaryServiceTest'`.
- Project gate: `./mvnw clean verify` (90% line and 90% branch coverage independently, Spotless, SpotBugs and PMD).
- Mutation gate from the brief/TD3: `./mvnw verify -Pmutation` with at least 85% mutation score. Future Cucumber tests do not replace unit-test evidence or mutation coverage.

These are required future checks, not claims that implementation exists or tests passed. A: Q-analyst-1 supplies the numerical contracts above. A: Q-analyst-3 resolves reduction-size bounds, cache-count semantics and budgeting all four prompts. A: Q-analyst-2 confirms the Step 2 runner arrangement; A: Q-tester-1 explicitly resolves marker-only rejection.

## Out of scope

Telegram URL handlers, acknowledgement/status edits, HTML escaping, title/duration/buttons and job persistence (T11); media download/duration validation (T8); transcription and transcript persistence (T9); audio/transcript delivery (T12/T13); retention/restart/recovery (T14 and separate intake); host installation/Podman networking (T15); real-model speed/quality and three-hour smoke testing (T17); another LLM backend, paid/cloud API, database, translation, TTS, chapters or new user-facing endpoint. T10 adds no summary artifact on disk unless a later agreed contract requires it.

## Open design questions

See append-only `comms/questions.md`: Q-analyst-1 (answered: architecture contracts); Q-analyst-2 (answered: Tester confirms @T10 deferral/testability); Q-analyst-3 (answered: actual partial-size guard, cache-count semantics, all reduction prompts in budget). Q-tester-1 is also answered: marker-only lines are dropped and an entirely marker-only final answer fails. No analyst design questions remain open; Step 2 exclusion remains Tester-owned work.
