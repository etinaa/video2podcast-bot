# T19 tester design review — Step 1

Reviewed 2026-11-25: tester/common role rules, CLAUDE.md, SPEC.md, tasks/plan.md, T19 task/brief, requirements, research, all four architecture artifacts, analyst handoff and questions/answers, T19 feature, and relevant existing functional harness, media fixtures, summary reduction and deferred scheduler support.

Decision: APPROVED. No open tester questions. Owner A: Q-analyst-2 governs first-nonempty-chunk detection; A: Q-analyst-3 distinguishes malformed metadata from valid unsupported languages. The API is sufficient to test every scenario without a new production test hook.

| Coverage | Observable contract and test approach |
| --- | --- |
| R1 detection | Record actual Whisper multipart bytes and all fields; return codes/full names/mixed case and deliberately malformed metadata. Assert auto/verbose requests and selected-language summary instructions independently of title. |
| R2 pinning and isolation | Observe ordered chunk requests, held request concurrency, retries, silent intros, all-empty results, 18 chunks at 10800 seconds, and consecutive jobs. Pinned response metadata must be ignored; new attempts start auto. |
| R3 transcript | Assert exact retained UTF-8 bytes, Unicode whitespace normalization, one LF, ordered final chunk, unchanged source bytes and temporary WAV cleanup. Capture transcript upload bytes after the button tap. |
| R4 summary | Record every /api/chat request and supplied answer. Build language-specific input spanning eight map chunks, force clipped map partials large enough to require two intermediate reductions, then a final reduction (11 calls). Verify complete ordered map input, prompts at every stage, configured options/context budgets and final supplied content. Existing T10 failures remain active. |
| R5 refusal | Wait for terminal status and worker completion before asserting no later chunk, chat inference, summary keyboard, uploads, deferred entry or remaining attempt directory. Run scheduler recovery twice to establish entry removal and absence of repeated notification; then demonstrate queue usability with a supported job. Health GETs remain distinct from inference POSTs. |
| R6 failures | Preserve exactly one same-mode retry, no resend of successful chunks, malformed metadata failure vs unsupported result, and post-detection connection outage parking. T9/T10/T11/T18 retain their existing failure coverage. |
| R7 interface | Inspect English status progression, buttons/captions, duration, HTML escaping/entity-decoded limits and callback bytes. Existing access, ownership, retention and delivery lifecycle scenarios remain applicable. |
| R8 filenames | Inspect actual Telegram multipart filenames for ordinary, sanitized and 101-character Cyrillic titles on both uploads, plus exact retained content. The 100-unit stem bound is an application contract; no invented Telegram filename limit. |
| R9 manual/docs | Real Russian output quality, real Telegram acceptance of both filenames and token-estimate comparison require later live evidence and documentation inspection. Mock success cannot establish these results. |

Step 2 implementation notes: provide the required LanguageIT target and Cucumber glue for T19; reuse the current scenario world/media proxy and scheduler controls. Update successful English shorthand fixtures in T9/T10/T11/T16/T18 and assembled IT fixtures to return first-response detection metadata, while leaving intentionally malformed answers untouched. Keep all unrelated assertions. Use terminal events and a drained worker queue for negative checks, rather than arbitrary sleeps.

The prompt-method naming discrepancy in components.md is already tracked by Q-orchestrator-1; api.md section 7 supplies the implementable method names. No additional tester question is needed. No tests or production code were written, and no build was run during this design-only step. Test implementation awaits the orchestrator's instruction.

## Reapproval after Q-orchestrator-1

Re-read corrected components.md, api.md section 7 and A: Q-orchestrator-1. All four prompt names now agree: shortPrompt, mapPrompt, reduceFinalPrompt and reduceRoundPrompt. This naming correction changes no scenario behavior or test boundary. No open tester questions; design approval renewed after the reset. No tests written or run.
