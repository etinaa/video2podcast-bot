
## Q-analyst-1 · analyst → architect
Please settle the audio preparation contract: (a) exact upload limit in bytes (50 MB versus 50 MiB; target is explicitly 48 MiB), inclusive source threshold versus strict encoded-output threshold; (b) integer bitrate calculation and the one lower-bitrate retry; (c) handling if a <=limit non-MP3/M4A source converts at 128 kbps into an oversized output. Combined source.mp4 must become audio-only AAC/M4A, per brief/SPEC. Analyst will cover this at Telegram/files boundaries. These are unspecified contract details, not permission to exceed Telegram's limit.

## Q-analyst-2 · analyst → architect
SPEC requires sendAudio title and performer set from the video, but JobRecord stores title/duration only and TelegramGateway.sendAudio has no caption argument. Please settle performer provenance/fallback and the Telegram representation of unchanged/converted/compressed details including resulting bitrate, without inventing uploader metadata. Also clarify whether an audio claim made before expiry may finish after expiry while waiting in the shared queue (T12 claims govern delivery).

## Analyst draft handoff · analyst → architect / orchestrator / tester
Written requirements.md, research.md and src/features/T13-audio-button.feature. Cached Cucumber Gherkin 42.0.1 parser accepts the feature: 50 expanded scenarios, all tagged with requirement ids. No Maven gates run. Exact size-boundary examples, conversion-overflow, performer/notice assertions and queued-expiry behavior remain dependent on Q-analyst-1/2; the artifacts explicitly say draft. The combined source.mp4 real-ffmpeg conversion scenario is included. Tester agent was not yet available (Herdr agent_not_found), so the orchestrator was notified of this disk handoff.

## A: Q-analyst-1 · architect
(a) Limit is 50 MiB = 52 428 800 bytes (50 000 000 would sit below the 48 MiB target). Source is sent unchanged when size <= limit (inclusive) and extension is mp3/m4a. An encoded output is accepted only when strictly under the limit. (b) kbps = clamp(floor(48 MiB × 8 / durationSeconds / 1000), 8, 128); 3 h → 37 kbps. One retry at max(8, floor(kbps × 0.8)) (29 kbps for 3 h), then FAILED. (c) A <=limit non-mp3/m4a source converted at 128 kbps whose output is not under the limit goes on to the compression path (mono, computed bitrate, one retry); the mode is then COMPRESSED. source.mp4 is converted with -vn to audio-only AAC in M4A. Details: tasks/T13/api.md.

## A: Q-analyst-2 · architect
Performer is the constant `Video2Podcast` (no uploader is stored; none is added). Title is the job title. sendAudio gets `fileName` and `caption` arguments; the compression note is the caption: `Original audio, not compressed.` / `Converted to M4A, 128 kbps.` / `Compressed to mono AAC, <kbps> kbps, to fit Telegram's 50 MB limit.` A claim granted before expiry finishes after expiry while queued (T12 claim rule); a tap after expiry is refused. Texts in api.md.

## Architect note · architect → analyst
Answers are in this file and in tasks/T13/api.md (texts exact). Two points for your draft: (1) a duration <= 0 is treated as 1 second, so the bitrate clamps to 128 and nothing divides by zero (not a failure). (2) The transcript state in the audio keyboard edits comes from the latest stored record (claim for the first edit, the record returned by complete for the last). Please align requirements.md and the feature, set your status to DONE, and I will check every scenario against the flows.

## Analyst alignment complete · analyst → architect / orchestrator / tester
requirements.md, research.md and src/features/T13-audio-button.feature now match A: Q-analyst-1/2 and api.md: 52,428,800-byte inclusive source boundary / strict encoded boundary; clamped floor bitrate and 80% retry; conversion overflow into mono compression; exact captions, performer and filenames/title limits; nonpositive duration treated as 1 second; claimed audio completes after queued expiry; claim/completion transcript snapshots; temporary output cleanup. Feature syntax validated with cached Gherkin 42.0.1: 67 expanded scenarios, all requirement-tagged. git diff --check passes. No Maven gates run; no production/test implementation or commits made. No outstanding analyst question.

Architect consistency note: flows.md's final expiry note saying a new tap at 13:05 gets EXPIRED should be read as a new AVAILABLE claim. A repeat tap on the same claimed SENDING/SENT audio still receives Already being sent./Already sent. per T12, api.md and requirements R8; the feature preserves that precedence.
