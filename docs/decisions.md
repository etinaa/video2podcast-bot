# Decisions

## D1: Telegram library (T2)

**Decision:** use `org.telegram:telegrambots-springboot-longpolling-starter` 10.3.0, plus
`telegrambots-client` 10.3.0 for sending. No fallback is needed.

**Evidence (Java 27.0.1, Spring Boot 4.1.2):**
- The starter's auto-configuration (`TelegramBotStarterConfiguration`) loads on Boot 4. It finds our
  `TelegramBot` bean (a `SpringLongPollingBot`) and registers it at startup.
- Started with a fake token, the app reaches Telegram (`deleteWebhook`) and fails fast with
  `401`. A real token proceeds to long polling.
- `telegrambots-client` is not pulled in by the starter, so it is declared explicitly.

**Consequences:**
- The bot token is validated at startup: a wrong token stops the application with a 401 error
  instead of failing quietly later.
- The Telegram integration is disabled in tests with `app.telegram.enabled=false`
  (`src/test/resources/application.properties`), so tests never reach the network. It is on by
  default (`TELEGRAM_ENABLED`). With the bot enabled, a missing token fails with a clear message.
- The rest of the code talks to Telegram only through `TelegramGateway`. `TelegramClientGateway` is
  tested against WireMock using `TelegramUrl` to point the client at a local server.
- The Telegram client lower-cases method names in request paths (`/sendmessage`). Telegram accepts
  this, and the WireMock stubs rely on it.

## D2: Allowlist persistence

**Decision:** `allowed-users.json` is read and written with Jackson 2 (`jackson-databind` and
`jackson-datatype-jsr310`, already on the classpath through the Telegram libraries, now declared
explicitly). Writes go to `<file>.tmp`, are flushed, then renamed over the target (atomic move).

**Why Jackson 2:** Spring Boot 4 defaults to Jackson 3, but the Telegram client needs Jackson 2
anyway. Using it avoids a second JSON stack. Switching later only touches `AccessStore` (and
`JobStore` from T7).

**Fail closed:** a corrupt file is renamed aside and the bot starts with an empty allowlist, so an
unreadable file can never grant access. Only the owner (from `ADMIN_USER_ID`) works until users are
approved again. Denials are persisted too, so the cooldown survives restarts.

## D3: Job state on disk

**Decision:** each job has a directory `<WORK_DIR>/<jobId>/` with a `job.json` that is the only
source of truth about it. `JobStore` changes a job only under that job's own lock and writes the
file atomically (the same `AtomicFiles` helper the allowlist uses). `transition(jobId, kind,
expected, next)` is a compare-and-set, so when two taps race exactly one gets `APPLIED`.

**Job ids** are random UUIDs (36 characters, so `transcript:<jobId>` stays under Telegram's 64-byte
callback limit). An id is validated against `[A-Za-z0-9_-]{1,40}` before it is turned into a path, so
a forged callback can never point outside the work directory. File names inside `job.json` must be
plain names without separators.

**File states:** `AVAILABLE -> SENDING -> SENT`, `SENDING -> AVAILABLE` (failed send or restart
recovery), and `AVAILABLE -> EXPIRED`. `SENT` and `EXPIRED` are final. A `job.json` that is missing,
not valid JSON or incomplete is reported as "no such job" so the buttons answer "expired".

**Queue:** `JobQueue` is one worker thread. A task that throws is logged and its future fails, and
the next task still runs. `close()` waits up to 30 seconds for queued tasks.

