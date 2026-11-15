# Spec: video2podcast-bot — V1

## Objective

A Telegram bot that summarizes a video and, on request, delivers its audio and transcript. The user sends a video URL. The bot downloads the audio, transcribes it, and replies with an HTML summary message that has two buttons: **"Download audio"** and **"Download transcript"**. Only when the user taps a button does the bot prepare and send that file into the chat (the audio is compressed only if it is too big for Telegram). There is no RSS feed, no hosting, and no episode library.

**Users:** an owner (set by Telegram user id in the environment) and a small group of trusted users the owner has approved. They want to skim videos from Telegram and choose which ones to listen to.

**User flow**
1. User sends a video URL.
2. Bot acknowledges and shows progress (downloading, transcribing n/N, summarizing).
3. Bot sends the summary message (title, duration, bullets) with the **Download audio** and **Download transcript** buttons. No files are sent yet.
4. User taps **Download audio**. Bot prepares the file (compression only if over the size limit) and sends it as an audio message.
5. User taps **Download transcript**. Bot sends the transcript as a `.txt` document.
6. The buttons work independently, in any order, and each only once. The job's working files are deleted when both files have been delivered, or 1 hour after the summary was sent, whichever comes first.

**User stories**
- As the owner, I get a summary first, so I can decide whether a video is worth listening to before any audio is produced or sent.
- As the owner, I tap one button to receive the audio in the chat, and another to receive the transcript.
- As the owner, I get a clear chat message when a link can't be processed (unsupported, too long, private, download failed).

### Assumptions (confirm or correct)
1. A small trusted group, not a public service. Access is controlled by an allowlist of Telegram user ids managed by the owner from inside the bot (see Access Control). No accounts or billing.
2. Video sources are whatever `yt-dlp` supports, with YouTube as the primary target.
3. Telegram integration uses long polling. No public endpoint or webhook is needed, and nothing is exposed to the internet.
4. Everything runs locally on the owner's Mac (Apple M3 Max, 64 GB RAM) with no paid external APIs. Ollama and whisper.cpp run on the host with Apple GPU (Metal) acceleration. Only the bot runs in docker-compose.
5. The source audio and the transcript are kept in a per-job working directory between the summary and the button taps, so the audio does not have to be downloaded again. A small `job.json` file in that directory stores the job state, so the button still works after a bot restart. No database.
6. Working files are kept for 1 hour (`JOB_RETENTION`, default `PT1H`), counted from the moment the summary message is sent. When the hour ends, everything in the job directory is deleted, including any file that was not requested. After that a button tap reports that the files have expired and asks the user to resend the link. A file that was already delivered stays in the chat.
7. Jobs run one at a time (a simple queue), because the local models are the bottleneck. Audio preparation goes through the same queue.
8. The transcript `.txt` is written to the job directory when transcription finishes, and sent only when its button is tapped.

## Tech Stack
- Java 27, Spring Boot 4.1.2, Maven (set `<java.version>27</java.version>`)
- Telegram: `org.telegram:telegrambots-springboot-longpolling-starter` (long polling) with inline keyboards, callback queries, `sendAudio` and `sendDocument`. Its compatibility with Spring Boot 4 is unverified (see Open Questions). The fallback is the plain `telegrambots-longpolling` client wired as a Spring bean.
- Telegram file size: the cloud Bot API limits bot uploads to 50 MB. V1 uses the standard cloud API, with no local Bot API server. The audio step compresses only when needed (see Audio Delivery).
- Video download and audio extraction: `yt-dlp` and `ffmpeg`, installed in the bot's Docker image and invoked as external processes. A temporary 16 kHz mono WAV is made for Whisper and deleted after transcription.
- Transcription: whisper.cpp `whisper-server` on the macOS host (installed separately, e.g. via Homebrew, with Metal). Model `large-v3-turbo`, language forced to `en`. Audio is split into about 10-minute chunks so progress can be reported and failures retried per chunk. The bot reaches it at `http://host.docker.internal:<port>`.
- Summary: Ollama on the macOS host (native app or Homebrew, with Metal), called through its HTTP API (`/api/chat`) behind a small `LlmClient` interface so the backend can be swapped (for example for llama.cpp's `llama-server`) without touching the rest. Default model `qwen3:32b`. The model is configurable. Ollama's default context window is small, so each request sets `num_ctx` explicitly, and the chunk size is derived from it. Long transcripts use map-reduce: summarize chunks, then summarize the summaries.
- Deployment: docker-compose with one service, `bot`. Ollama and whisper.cpp stay on the host because Docker on macOS has no GPU access. A named volume holds the job working directories, and a separate named volume holds `allowed-users.json` so job cleanup never touches it.
- Tests: JUnit 5, Mockito, Spring Boot Test, WireMock for the Whisper and Ollama APIs
- Static analysis: SpotBugs with FindSecBugs and PMD (rules in `config/pmd-ruleset.xml`), both run in `./mvnw verify` and fail the build on findings

## Commands
```
Build:        ./mvnw clean package
Test:         ./mvnw test
Single test:  ./mvnw test -Dtest=AudioPreparerTest
Lint/format:  ./mvnw spotless:apply
Static check: ./mvnw spotbugs:check pmd:check
Verify:       ./mvnw verify
Run (dev):    ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
Run (jar):    java -jar target/video2podcast-bot.jar
Docker build: docker build -t video2podcast-bot .
Compose up:   docker compose up -d --build
Compose logs: docker compose logs -f bot
Compose down: docker compose down
Host (once):  ollama pull qwen3:32b
Host (run):   whisper-server --model <path-to-ggml-large-v3-turbo.bin> --port 8081
```
Required env vars: `TELEGRAM_BOT_TOKEN`, `ADMIN_USER_ID`, `ACCESS_FILE` (default `/data/allowed-users.json`, on a separate volume from the job files), `OLLAMA_BASE_URL` (default `http://host.docker.internal:11434`), `OLLAMA_MODEL` (default `qwen3:32b`), `WHISPER_BASE_URL` (default `http://host.docker.internal:8081`), `WORK_DIR`, `MAX_VIDEO_DURATION` (default `PT3H`), `JOB_RETENTION` (default `PT1H`), `ACCESS_REQUEST_COOLDOWN` (default `PT24H`). They are read from the environment only and never committed.

## Project Structure
```
src/main/java/com/video2podcast/
  bot/          → Telegram update and callback handling, HTML message formatting, keyboards, file sending
  job/          → Single-worker queue, status state machine, job.json store, retention cleanup
  media/        → yt-dlp / ffmpeg wrappers (download, WAV extraction, chunking, AudioPreparer)
  transcript/   → Whisper client, chunk merging
  summary/      → LlmClient interface, Ollama client, map-reduce, prompts
  access/       → Allowlist store (allowed-users.json), access requests, admin commands, authorization checks
  config/       → Typed @ConfigurationProperties, beans
src/main/resources/ → application.yml, application-dev.yml
src/test/java/      → Unit and integration tests mirroring main packages
docker-compose.yml  → bot service and the work volume
Dockerfile          → JDK 27 runtime + yt-dlp + ffmpeg
docs/               → Host setup (Ollama, whisper.cpp) and troubleshooting
SPEC.md             → This file
```

## Code Style
Constructor injection, records for DTOs, no field injection, no Lombok.

```java
@Service
public class AudioPreparer {
    private static final long TELEGRAM_LIMIT_BYTES = 50L * 1024 * 1024;
    private static final long TARGET_BYTES = 48L * 1024 * 1024;

    private final FfmpegRunner ffmpeg;

    public AudioPreparer(FfmpegRunner ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    public Path prepare(JobFiles files, Duration length) {
        if (Files.size(files.sourceAudio()) <= TELEGRAM_LIMIT_BYTES && files.isSendableFormat()) {
            return files.sourceAudio();
        }
        int kbps = (int) (TARGET_BYTES * 8 / length.toSeconds() / 1000);
        return ffmpeg.encodeAacMono(files.sourceAudio(), files.dir().resolve("out.m4a"), kbps);
    }
}

public record JobFiles(UUID id, Path dir, Path sourceAudio, Path transcript, String title, Duration length) {}
```
- Packages by feature, not by layer. Classes are `PascalCase`, methods and fields `camelCase`.
- Format with Spotless (google-java-format). External process calls go through one small wrapper class, never `Runtime.exec` inline.
- Errors map to a typed `JobFailure` with a user-safe message. Raw stack traces never reach the chat.
- All user-controlled text (title, summary) is HTML-escaped before it is placed in a Telegram message.
- Callback data is only `audio:<jobId>` or `transcript:<jobId>` (well under Telegram's 64-byte limit), and the job id is looked up and validated, never trusted as a path.

## Access Control
Access is an allowlist of Telegram **user ids** (not chat ids), checked on every message and every button tap. The bot works only in private chats. Messages from groups and channels are ignored.

**Roles:** the **owner** is the id in `ADMIN_USER_ID`. The owner always has access and can't be removed. **Allowed users** are stored in `allowed-users.json`. Everyone else is unknown.

**Commands**

| Command | Who | Effect |
|---|---|---|
| `/start` | allowed user | Short help |
| `/start` | unknown user | Creates an access request and tells the user it was sent to the owner |
| `/whoami` | anyone | Replies with the sender's own Telegram id |
| `/allow <id>` | owner | Adds the id to the allowlist (also approves a pending request) |
| `/deny <id>` | owner | Removes the id from the allowlist, or rejects a pending request |
| `/users` | owner | Lists allowed users and pending requests |

**Approval flow**
1. An unknown user sends `/start`. The bot stores a pending request (one per user) and replies "Access requested. The owner will review it."
2. The owner receives "Access request from <name> (@username, id 123)" with **Approve** and **Deny** buttons. The callback data is `access:approve:<userId>` or `access:deny:<userId>` and is accepted only from the owner.
3. On Approve, the user is added to the allowlist and notified. On Deny, the request is removed and the user is notified. A denied user can't send a new request for 24 hours (`ACCESS_REQUEST_COOLDOWN`, default `PT24H`).
4. Any other message from an unknown user gets a short reply: "No access. Send /start to request it. Your id: 123". These replies are rate-limited to one per minute per user.

**Storage:** `allowed-users.json` holds the allowed users (id, username, addedAt, addedBy) and pending requests (id, username, requestedAt, request message id), so the Approve and Deny buttons keep working after a restart. It is written atomically (temp file and rename) behind a lock and cached in memory. No database.

**Job ownership:** `job.json` stores `ownerUserId`. A button tap on a job is accepted only from its owner, even if the tapper is on the allowlist. The queue is shared and first come, first served.

**Audit:** allow, deny, approve, and request events are logged with who, whom, and when. Usernames and names are HTML-escaped wherever they are shown.

## Telegram Message Format
HTML parse mode, link previews off. The summary is capped at 1,500 characters, well under Telegram's 4,096-character limit.

```
<b>Video title</b>
Duration: 1h 42m

• summary point 1
• summary point 2
• summary point 3

[ Download audio ] [ Download transcript ]      ← inline buttons
```
While work is running, one status message is edited with the current stage (queued, downloading, transcribing n/N, summarizing). After a button is tapped, it changes to "Preparing audio…" (or "Sending transcript…") and is then replaced by "Audio sent" (or "Transcript sent"), so repeated taps do nothing. The other button stays active. The audio is sent with `sendAudio` (title and performer set from the video so it plays in Telegram's player). The transcript is sent with `sendDocument` as `<title>.txt`.

## Audio Delivery (compression rule)
- Download with a preference for `m4a`/AAC (`yt-dlp -f "bestaudio[ext=m4a]/bestaudio"`).
- If the source file is **50 MB or smaller** and already in a format Telegram's audio player accepts (MP3 or M4A), send it **as is, with no re-encoding**.
- If it is in another format but fits the limit, convert it to M4A at a high bitrate (128 kbps), with no size-driven compression.
- If it is **over 50 MB**, re-encode to mono AAC in M4A at a bitrate computed from the duration so the result is about 48 MB: `kbps = 48 MiB × 8 / duration`. For the maximum 3-hour video this is about 37 kbps, which is acceptable for speech. The result is verified to be under 50 MB, and if not the bitrate is lowered one step and the encode is retried once.
- The message sent with the audio states whether it was compressed and the resulting bitrate.

## Testing Strategy
- **Unit (JUnit 5, Mockito):** URL validation, job state transitions, `job.json` read/write and retention expiry, HTML escaping and message building, summary length cap, transcript chunk merging, map-reduce prompt building, and the `AudioPreparer` rule (under the limit and sendable returns the same file, over the limit computes the right bitrate, wrong format converts without shrinking).
- **Access (unit):** allowlist add and remove, pending request lifecycle, 24-hour deny cooldown, refusal rate limit, owner-only command checks, job-owner check on button taps, atomic `allowed-users.json` persistence and reload.
- **Integration (Spring Boot Test + WireMock):** the full flow with the yt-dlp/ffmpeg wrapper stubbed and the Whisper and Ollama APIs mocked: link, summary message with button, callback, audio sent. Also a callback for an expired job and one from a non-allowlisted user.
- **Functional (Spring Boot Test + WireMock, `*FT` classes):** the whole application runs in one JVM with the real Spring wiring. Telegram Bot API (via `TelegramUrl`), Whisper and Ollama are WireMock servers, and only `yt-dlp`/`ffmpeg` are replaced by a fake `ProcessRunner`. The tests drive the bot by feeding updates and assert on the HTTP requests WireMock received: access request and approval, link to summary message, transcript and audio button taps, expiry, and the failure paths. They run in `./mvnw verify` but do not count toward the coverage gate.
- **Manual smoke test:** one real short video end to end (audio stays uncompressed), then one video of about 3 hours (audio gets compressed and uploads successfully). Documented in `docs/`.
- Coverage requirement: at least **90% line coverage and 90% branch coverage** across all production code, including `access` and `config`. Both thresholds must be met independently using coverage collected from the unit-test run; integration-test coverage does not count toward these thresholds.
- Verification: `./mvnw clean verify` runs the unit tests with the JaCoCo agent, generates the coverage report, and enforces `LINE` and `BRANCH` `COVEREDRATIO` minimums of `0.90` via `jacoco:check` in the `verify` phase. The build fails if either threshold is missed. The HTML report is available at `target/site/jacoco/index.html`.
- No tests call real external services. CI never needs secrets.

## Boundaries
- **Always:**
  - Check the sender's user id against the allowlist for both messages and button taps before doing any work, and check that a job's button is tapped by the job's owner.
  - Accept `/allow`, `/deny`, `/users`, and the Approve and Deny buttons only from the owner.
  - Validate and sanitize URLs before passing them to `yt-dlp`. Pass process arguments as a list, never through a shell string.
  - Enforce the max video duration (3 hours) before downloading, using yt-dlp metadata.
  - Delete a job's working directory once both the audio and the transcript have been delivered, on failure, and 1 hour after the summary was sent. Clean up expired directories on startup.
  - HTML-escape all dynamic text in Telegram messages.
  - Run `./mvnw clean verify` before committing. Unit tests, the JaCoCo gate (at least 90% lines and 90% branches), Spotless, SpotBugs, and PMD must all pass.
  - Read secrets from the environment.
- **Ask first:**
  - Adding dependencies beyond the stack above.
  - Adding a database, a feed, or any hosting.
  - Changing the Telegram upload approach (for example a local Bot API server).
  - Switching the LLM backend from Ollama.
  - Adding any paid external API (V1 is fully local).
- **Never:**
  - Commit secrets or tokens.
  - Respond to non-allowlisted users with anything other than the access-request flow, the rate-limited refusal, and `/whoami`.
  - Process messages from group chats or channels.
  - Run shell commands built from unsanitized user input.
  - Create or send the audio or the transcript file before the user taps its button.
  - Delete or skip failing tests, or add SpotBugs or PMD suppressions, to get a build green without approval.
  - Expose any port of the bot to the network.

## Success Criteria
1. Sending a supported video URL from an allowlisted account produces an acknowledgement in chat within 5 seconds.
2. For a 10-minute video, the chat receives an HTML message with the title, duration, a summary of at most 1,500 characters, and the **Download audio** and **Download transcript** buttons. **No audio or transcript file is sent at this point.**
3. Tapping **Download audio** sends a playable audio message. The audio file is identical to the downloaded source when it is 50 MB or smaller and in a sendable format (no re-encoding). Tapping **Download transcript** sends a `.txt` document with the full English transcript. Either button works without the other having been tapped.
4. For a video of exactly 3 hours, tapping the button sends audio under 50 MB, compressed, with no Telegram size error. A video over 3 hours is rejected before download with a clear message.
5. The transcript is in English and covers the full audio (no missing chunks).
6. Tapping either button twice sends that file once. Tapping after the retention period, or after the working files are gone, gives an "expired, please resend the link" message.
7. The buttons still work after a bot restart (job state is restored from `job.json`).
8. A message or button tap from a non-allowlisted user does no processing. The user gets only the refusal, the access-request reply, or the `/whoami` reply.
9. An unknown user's `/start` sends the owner a request with **Approve** and **Deny** buttons. After Approve the user can use the bot and is notified. After Deny the user is notified and can't request again for 24 hours.
10. `/allow <id>`, `/deny <id>`, and `/users` work for the owner and are ignored for everyone else. The owner can't be removed.
11. The allowlist and pending requests survive a bot restart, and a pending request's buttons still work afterwards.
12. A user can't tap another user's **Download audio** or **Download transcript** button.
13. Invalid, private, too-long, or unsupported URLs produce a clear failure message, and the working directory is removed.
14. The user is told if Ollama or the Whisper server is unreachable, instead of a silent hang.
15. `./mvnw clean verify` passes: unit tests and JaCoCo demonstrate at least 90% line coverage and 90% branch coverage across all production code, with the build failing below either threshold. No test needs network access.

## Out of Scope (Deferred)
- RSS feed and podcast-app subscription: removed because audio is delivered in chat. It could return as a V2 option.
- Persistent episode history, search, and deduplication of repeated links: V1 keeps only short-lived job files.
- A local Telegram Bot API server for sending uncompressed files over 50 MB: possible later if compression quality becomes a problem.
- Concurrent jobs, per-user quotas, and fair scheduling between users: V1 has one shared one-at-a-time queue.
- Roles beyond owner and allowed user, and group-chat support.
- Webhook mode for Telegram: long polling is simpler.
- Playlist or channel auto-subscription: V1 handles single links only.
- Non-English transcription and translation.
- Chapters, cover art, and TTS rewriting of the summary.
- Other chat platforms (Discord, Slack).
- Running Ollama and Whisper inside Docker: no GPU on macOS, so deferred until the bot moves to a Linux host with a GPU.

## Open Questions
1. **Java 27 / Spring Boot 4.1.2 compatibility (to verify in the plan phase):** I'll check that the Telegram library, Spotless/google-java-format, JaCoCo, and Mockito work on Java 27 and Boot 4.1.2. If one doesn't, I'll use the fallback noted in the Tech Stack and flag it to you. Docker images must use a JDK 27 base.
2. ~~**whisper.cpp server API**~~ **Resolved in T3:** `POST /inference` (multipart: `file`, `response_format`, `language`, `temperature`) returns `{"text": ...}`. Errors are plain text. Ollama findings: `think: false` is required for `qwen3`, and a prompt longer than `num_ctx` is truncated silently. Details in `docs/host-setup.md`.
