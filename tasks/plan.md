# Implementation Plan: video2podcast-bot — V1

Source spec: `SPEC.md` (V1, approved). Tasks are tracked in `tasks/todo.md`.

## Overview

A Telegram bot (Java 27, Spring Boot 4.1.2, Maven) that takes a video URL, downloads the audio with `yt-dlp`, transcribes it with whisper.cpp, and summarizes it with Ollama (`qwen3:32b`). It replies with an HTML summary and two buttons. **Download audio** sends the audio (compressed only if over 50 MB). **Download transcript** sends a `.txt`. Access is an owner-managed allowlist with an Approve/Deny request flow. Job state lives in `job.json` files with a 1-hour retention. The bot runs in a Podman container via `podman compose`. Ollama and whisper.cpp run on the macOS host.

## Architecture Decisions

- **Vertical slices.** After the skeleton and the risk spikes, each task delivers something testable end to end (access gate, summary flow, transcript button, audio button, cleanup), rather than building layers.
- **Access control comes before any processing.** The authorization gate (task 5) is in place before the pipeline exists, so no code path can process a message from an unknown user.
- **Files, not a database.** `job.json` per job directory and one `allowed-users.json`, both written atomically (temp file and rename) behind locks. This matches the spec's "ask first" rule for adding a database.
- **One worker thread.** A single-thread executor runs summary jobs and audio preparation, so the local models never compete for the GPU. Telegram updates are handled on separate threads and only enqueue work.
- **Clients behind interfaces.** `LlmClient` (Ollama), `TranscriptionClient` (whisper-server), `VideoDownloader`/`FfmpegRunner` (external processes) are interfaces, so tests use stubs or WireMock and the backend can be swapped later.
- **Process safety.** All external commands go through one `ProcessRunner` that takes an argument list, enforces timeouts, and captures output. URLs are validated before they reach it.
- **Risky unknowns first.** Task 2 (Telegram library on Boot 4) and task 3 (whisper-server and Ollama contracts) come before any feature work, because a failure there changes the design.

## Commit Convention

- **One commit per task**, made after the task's verification passes. A checkpoint that needs fixes gets its own fix commit.
- Commit message: `T<N>: <short task title>`, with a body that lists what changed and how it was verified, and the attribution line.
- Each commit leaves the build green (`./mvnw verify`) and `tasks/todo.md` updated with the task ticked, in the same commit.
- Plan and spec changes get their own commits, separate from code.

## Dependency Graph

```
T1 skeleton ─┬─ T2 telegram spike ─┬─ T4 access store ─ T5 auth gate ─ T6 admin flow ─┐
             │                     │                                                  │
             └─ T3 contracts ──────┼─ T8 media ───┐                                   │
                                   ├─ T9 whisper ─┼─ T11 pipeline + summary msg ◄─ T7 job store
                                   └─ T10 llm ────┘            │                      │
                                                               ├─ T12 transcript btn ─┤
                                                               ├─ T13 audio btn       │
                                                               └─ T14 retention ◄─────┘
                                                                         │
                                                      T15 podman/compose ─ T16 hardening ─ T17 smoke tests
```

**Parallel opportunities** (after T3): the access track (T4–T6), the media and model clients (T8, T9, T10), and the job store (T7) have no shared files and can be built in any order or in parallel. T11 is the join point. T12 and T13 are independent of each other once T11 is done.

## Task List

See `tasks/todo.md` for the checklist with acceptance criteria and verification for each task. Summary:

### Phase 1: Foundation and risk spikes
- T1 Project skeleton on Java 27 / Spring Boot 4.1.2
- T2 Telegram long-polling spike (`/whoami`)
- T3 Host-service contracts (whisper-server and Ollama) and fixtures
- Checkpoint 1

### Phase 2: Access control
- T4a WireMock functional-test harness (Telegram, Whisper, Ollama); T5 onward each add their own `*IT` tests
- T4 Allowlist store and access service
- T5 Authorization gate for messages and callbacks
- T6 Owner commands and the Approve/Deny flow
- Checkpoint 2

### Phase 3: Summary pipeline
- T7 Job model, `job.json` store, and single-worker queue
- T8 Media: URL validation, metadata, download, WAV chunks
- T9 Transcription client and merged transcript
- T10 Ollama client and map-reduce summary
- T11 Link to summary message with buttons (end-to-end)
- Checkpoint 3

### Phase 4: On-demand delivery and cleanup
- T12 Download transcript button
- T13 Audio preparation and Download audio button
- T14 Retention sweeper and restart recovery
- Checkpoint 4

### Phase 5: Packaging and hardening
- T15 Dockerfile, Podman compose file, and host setup docs
- T16 Failure-mode and restart integration tests
- T17 Real-world smoke tests (short and 3-hour video)
- Checkpoint: Complete

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Telegram library or starter doesn't work on Spring Boot 4.1.2 / Java 27 | High | T2 spike first. Fallback is the plain `telegrambots-longpolling` client as a Spring bean, then a thin custom client over the Bot API's `getUpdates` HTTP endpoint if both fail. |
| Spotless, JaCoCo, or Mockito don't support Java 27 | Med | T1 runs `./mvnw verify` with all three on an empty project. Pin the newest versions, or configure the tool to skip or relax only that tool and ask before dropping it. |
| `whisper-server` endpoint or response differs from what the client assumes | Med | T3 captures the real request and response and saves them as WireMock fixtures before T9. |
| Ollama default context is too small and silently truncates long prompts | High | T10 sets `num_ctx` on every request. A unit test asserts the chunk size fits the context. T17 runs a real 3-hour transcript. |
| `qwen3:32b` is slow, and a 3-hour transcript needs many map-reduce calls | Med | Measured in T17. Chunk size and model are configurable, and `qwen3:14b` is a documented fallback. The progress message shows summarization progress. |
| Memory pressure from Whisper and a 32B model loaded together (64 GB) | Med | Stages run sequentially and Ollama's `keep_alive` is configured. Measured in T17. |
| `yt-dlp` breaks when sites change | Med | The container image installs the latest `yt-dlp` at build time, and docs describe `podman compose build --no-cache` to update. Download errors map to a clear user message. |
| A 3-hour video at 37 kbps AAC is too low quality, or the result is still over 50 MB | Low | The size is verified after encoding, with one retry at a lower bitrate. The bot tells the user the result was compressed. Local Bot API server is in the V2 list. |
| The Podman VM can't reach host services | Med | `host.containers.internal` is used and checked in T15 (services bound to `127.0.0.1` may need `0.0.0.0`). Podman machine has 6 GiB of memory, enough for the bot and `ffmpeg`. A health check reports unreachable services to the user (T16). |
| Concurrent button taps cause double sends | Med | Per-job lock and `SENDING` state (T12, T13), covered by a concurrency test. |

## Open Questions

- None blocking. The two spec items marked "verify in the plan phase" (Java 27 / Boot 4.1.2 compatibility and the whisper.cpp server API) are tasks T1, T2, and T3.
