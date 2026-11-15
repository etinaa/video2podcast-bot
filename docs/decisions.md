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

