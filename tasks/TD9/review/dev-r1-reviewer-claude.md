VERDICT: APPROVED

## Findings
- [Suggestion] src/test/java/com/video2podcast/bot/TelegramClientGatewayTest.java:~215 - the clamp `Math.min(duration, Integer.MAX_VALUE)` has no test (values 114, 0, -5 only). PIT may keep a mutant there; a case above Integer.MAX_VALUE (e.g. 3_000_000_000L expecting 2147483647) would pin it. Not blocking.
- [Suggestion] src/test/java/com/video2podcast/bot/AudioButtonTest.java:178 - the handler test checks only the positive value (10_800); the zero/negative case is covered at gateway and scenario level, so this is fine, just noting where the "not positive" rule lives (gateway, not handler).

## Requirements
- R1 (`sendAudio` gets a `duration` seconds parameter, multipart carries `duration`): met - interface, client gateway and `SendAudio.duration` updated; all implementers/callers (RecordingGateway, UrlMessageHandlerTest stub, handler) updated, no others found.
- R2 (handler passes `job.durationSeconds()`; field omitted when zero or negative): met - handler passes the stored value; gateway sends null (field absent) when not positive.
- R3 (nothing else changes): met - no change to tap flow, texts, states, captions, file names.
- R4 (unit tests check the `duration` field): met - gateway test checks the multipart field for 114 and its absence for 0 and -5; handler test checks the value passed.
