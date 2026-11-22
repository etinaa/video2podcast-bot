# Analyst handoff — 2026-11-22

Written: tasks/T16/requirements.md, tasks/T16/research.md, src/features/T16-failure-modes.feature. Existing failure coverage is inventoried in the requirements table. GherkinParser 42.0.1 validates 32 concrete scenarios; all requirement references/local Markdown links, external-boundary steps and scoped whitespace checks pass. Executable test code and build/gate runs remain with their owning roles.

A: Q-analyst-1 incorporated: Whisper GET /, Ollama GET /api/version; any HTTP status counts as reachable, bodies discarded; parallel probes use HEALTH_TIMEOUT=PT3S and timeout + 1 second aggregate budget; startup does not wait; both failures produce the exact combined message.

Owner A: Q-analyst-2 incorporated: probes run on the shared worker after queueing/acknowledgement, right before processing. A stalled check delays following work by at most HEALTH_TIMEOUT + 1 second. Telegram commands and transcript callbacks stay responsive. The prior off-worker scenario is replaced by bounded timeout release, plus a waiting-job freshness scenario. No open design question remains.

Tester notes: distinguish GET probes from inference calls; connection-refusal tests cannot assert receipt of an HTTP request at a stopped server. Test parallel attempts for transport failures in unit tests; functional healthy-peer/held-response observations cover the external behavior. Wait for completed preflight before negative assertions. A 404/503 health response now succeeds by design, and must not be used to simulate a down service. Existing T9/T10/T11 mid-job failure fixtures must allow successful preflight before causing their intended later failure.
