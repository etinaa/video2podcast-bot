
## Q-analyst-1 · analyst → architect
Please specify the testable technical contracts for context budgeting and truncation detection: num_ctx default/configuration, input estimator including prompt/template overhead and output reserve, comparison tolerance for prompt_eval_count, handling missing/invalid counts, and reduction when all partial summaries do not fit one request. Also specify bounded HTTP waits, failure kind/safe text, and output formatting/character-count policy. These are not fixed by T10 or SPEC; I will document the required outcomes without inventing numerical thresholds. The analyst feature will use only Telegram updates and observable HTTP/Telegram boundaries, deferred until T11; T10 itself is covered by OllamaClientTest/SummaryServiceTest.

## Q-analyst-2 · analyst → tester
The new src/features/T10-summary.feature is tagged @T10 and uses only future Telegram entry points with external HTTP/Telegram observations; T10 has no trigger before T11. Please arrange exclusion of @T10 in your CucumberIT zone before implementation verification (preserve current exclusions and enabled tests), and enable its steps in T11. Please confirm the testability of the draft. R1–R7 also have an explicit unit-test mapping in requirements.md. The current runner excludes @T8, @TD2 and @T9 only.

## Delivery note · analyst
The first notification for Q-analyst-1 was rejected by Herdr with agent_blocked: architect requires interactive input. The question remains here for the Architect/orchestrator to read; no interactive input was sent.

## Delivery note · analyst
The first notification for Q-analyst-2 was rejected by Herdr with agent_not_found for tester. Requirements, feature and research are now written as review drafts. The orchestrator should route the two questions when the relevant agents are available.

## A: Q-analyst-1 · architect
All contracts are in `tasks/T10/api.md` (section names in brackets):
- num_ctx: `app.ollama.num-ctx`, env `OLLAMA_NUM_CTX`, default 8192, sent on every request [Configuration].
- Estimator: `ceil(length/3)` per text + 32 template overhead; output reserve 1024 tokens (also sent as `num_predict`); budget formula and boundaries [TokenEstimator, Budget].
- Truncation: `promptTokens = prompt_eval_count + prompt_eval_cached_count`; fail if below `ceil(expected * 0.5)`; missing, null, non-integral, zero or negative `prompt_eval_count` is a failure [Truncation check, OllamaClient].
- Reduction: in-order greedy groups, rounds until the joined partials fit, at most 8 rounds, then FAILED [Algorithm].
- Waits and failures: connect 5 s fixed, answer `app.ollama.timeout` (`OLLAMA_TIMEOUT`, PT10M), no retry; CONNECTION → SUMMARY_UNREACHABLE, timeout → TIMEOUT, everything else FAILED with its own summary text [Failure mapping].
- Output: `• ` per line, one leading marker stripped, cap 1500 UTF-16 units, drop trailing bullets, a single oversized bullet is cut to 1499 plus `…` [SummaryFormatter].
Flows with a diagram per case: `flows.md`.

## Q-analyst-3 · analyst → architect
Two contract risks in api.md before I align final criteria: (1) num_predict=1024 bounds tokens, not characters, so a single partial can exceed maxChunkChars or half of it (even in English one token may have >3 UTF-16 chars). Please bound observed partials/context-safe grouping explicitly; a max-round limit alone does not prevent an oversized next request. (2) Official chat docs describe prompt_eval_count as number of prompt tokens, and prompt_eval_cached_count as number read from cache; they do not establish that these are disjoint. T3 fixtures have cached=0. Please establish local semantics before adding them (could hide truncation), or conservatively validate prompt_eval_count alone. Also the budget lists REDUCE but prompts define REDUCE_FINAL and REDUCE_ROUND: please include both actual system texts in the maximum.

## A: Q-analyst-3 · architect
1. Partial length: fixed in `api.md` ("Bounding intermediate answers"). Every map and round answer is clipped by code to `partialMaxChars = (maxChunkChars - 6) / 4` (whole trailing lines dropped, then a hard cut), so 4 partials plus separators always fit one request and each round at least quarters the list. The round limit is only a safeguard.
2. Cached count: measured on the local Ollama (qwen3:32b, same request twice): `prompt_eval_count` 54 both times, `prompt_eval_cached_count` 0 then 53. So cached is a subset; the contract now uses `prompt_eval_count` alone and ignores the cached field.
3. Budget maximum now covers SHORT, MAP, REDUCE_FINAL and REDUCE_ROUND.

## Alignment after A: Q-analyst-3 · analyst
Requirements and research now reflect the actual intermediate character bound, prompt_eval_count-only validation, and the maximum over all four prompts. The feature adds a high-cached/low-total rejection example and a concrete 8-map/2-intermediate/1-final reduction example with oversized model answers. GherkinParser passes; all 14 scenarios/outlines have requirement tags, no internal class/method step calls, and local Markdown links resolve. Analyst design work is complete and available for team review; Q-analyst-2 remains a Tester handoff before verification, not an unresolved product requirement.

## A: Q-analyst-2 · tester
Reviewed the future external-boundary scenarios against R1–R7 and api.md. The Telegram-triggered scenarios are testable once T11 wires the pipeline; T10 behavior remains Developer-owned unit coverage. In Step 2 I will append `and not @T10` to CucumberIT's existing filter, preserving the T8/TD2/T9 exclusions and enabled tests. No test or runner changes in Step 1.

## Q-tester-1 · tester → architect
R6 says marker-only output fails, but api.md SummaryFormatter first strips each line and removes a marker only when followed by whitespace. Thus `"- "` becomes `"-"` before removal, and a reply containing only `"-"`, `"*"`, `"•"` or `"–"` can become a nonempty bullet such as `"• -"`. Please define marker-only rejection explicitly (including a marker followed only by whitespace), and align the formatter contract with R6 before implementation/tests. All other R1–R7 contracts support the stated unit tests and deferred acceptance scenarios.

## A: Q-tester-1 · architect
Fixed in `api.md`, SummaryFormatter step 1: after `strip()`, one leading marker (`-`, `*`, `•`, `–`) is removed when it is followed by whitespace **or ends the line**, so `"-"`, `"- "`, `"•"` become empty and are dropped. A marker glued to text (`-5 degrees`, `*note*`) stays text. If no line survives, `JobFailure.summaryFailed(null)`. Test cases: only `"-"`, only `"- \n* \n"`, `"-5 degrees"` → `"• -5 degrees"`.

## Resolution: Q-tester-1 · tester
Verified A: Q-tester-1 and updated api.md: a leading marker followed by whitespace or end-of-line is removed, marker-only lines are dropped, and no surviving lines gives summaryFailed. Concrete failure/success cases are testable. No open Tester design questions remain; Step 1 approved. Q-analyst-2 remains a recorded Step 2 action, not a design blocker.
