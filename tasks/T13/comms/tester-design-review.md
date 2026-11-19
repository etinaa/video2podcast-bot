# T13 tester design review — Step 1

Reviewed 2026-11-19: tester/common role rules, SPEC.md, tasks/plan.md, T13 task/brief, requirements.md, research.md, components.md, data-flow.md, flows.md, api.md, T13-audio-button.feature and existing communications. Checked the existing functional harness and transcript boundary fixture for reusable test support.

Verdict: APPROVED design. No open contract or scenario questions.

- R1/R5/R8: Telegram callback updates plus stored job fixtures expose private-chat/access/ownership checks, stored destinations, refusal precedence, per-file expiry, one-send behavior and persisted AVAILABLE/SENDING/SENT/EXPIRED states. A mutable application clock covers exact expiry and claimed delivery after queued expiry.
- R2–R4/R6: multipart audio bytes, filename, title, performer and caption expose unchanged/conversion/compression behavior. Real short MP3/M4A/WebM/combined MP4 and a decodable oversized WAV can exercise success paths. Valid padded media covers the exact source threshold. Process fault fixtures cover encoded-size equality/overflow, one lower-bitrate retry, timeout and missing/empty/partial output. Inspect captured upload bytes for codec/channels/video/duration; capture the temporary file at the held HTTP upload boundary for the conversion-on-disk assertion, before finally cleanup removes it. Do not depend on out.m4a surviving delivery or expect a real encoder to hit exactly its requested bitrate.
- R5/R7: an earlier link job held at Whisper exposes FIFO audio waiting and prompt callback answers. A held audio upload exposes duplicate rejection. A transcript callback during queued audio exposes independent delivery and the completion snapshot's transcript label.
- R7/R8: retained-file snapshots and job.json expose source/transcript preservation, unchanged expiry, cleanup, release after failure and explicit manual retry. HTTP rejection/connection-loss fixtures cover no automatic upload retry; callback-answer and keyboard failures remain observable at Telegram.
- Exact Path object identity, arithmetic/floor/clamp cases (including 3-hour 37/29 kbps), surrogate-safe limits, queue rejection and low-level storage failures are explicitly assigned to Developer unit tests. api.md's specific complete-after-send failure rule retains SENDING and takes precedence over the general preparation/send failure recovery rule, matching requirements R8.

No test implementation or build execution in Step 1. T14 sweeping/recovery and T17 real 3-hour smoke remain out of scope. The orchestrator owns serialized verification gates and commits.
