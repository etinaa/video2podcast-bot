# T14 api

## Java

```java
package com.video2podcast.job;

/** Best effort: shows the current file states on the summary message buttons. Never throws. */
public interface ButtonRefresher {
  void refresh(JobRecord job);
}
```

`bot/SummaryButtonRefresher implements ButtonRefresher`, constructor `(TelegramGateway gateway)`. If `job.summaryMessageId() == null` it does nothing. Otherwise `gateway.editButtons(job.chatId(), job.summaryMessageId(), Keyboards.delivery(job.jobId(), audioState, transcriptState))`, where a missing file kind counts as `AVAILABLE` (as in `DeliveryCallbackHandler`). It catches `RuntimeException` and logs a warning with the job id only.

### JobStore additions

```java
/** What {@link #recover} did to one job. */
public enum Recovery { REMOVED, RESET, UNCHANGED }

/** Result of {@link #recover}. {@code job} is the stored job after a RESET, otherwise null. */
public record Recovered(Recovery recovery, JobRecord job) {}

/**
 * Result of {@link #sweep}. {@code job} is the job after the change (null when there was no usable
 * job.json or the job was kept untouched with a SENDING file); {@code expired} lists files this call
 * moved to EXPIRED; {@code removed} is true when the directory is gone.
 */
public record Swept(JobRecord job, List<FileKind> expired, boolean removed) {
  public static Swept none();   // job null, expired empty, removed false
}

/**
 * Startup only. Under the job lock: if there is no usable job.json, deletes the whole directory
 * (REMOVED); else moves every SENDING file to AVAILABLE and saves (RESET); else UNCHANGED.
 * Preserves expiresAt and sentAt. Does not recreate files.
 * @throws JobStoreException on an I/O error
 */
public Recovered recover(String jobId);

/**
 * Applies rules 1-5 of components.md under the job lock.
 * - no usable job.json -> none()
 * - any file SENDING -> none() (nothing is read as changed, nothing deleted)
 * - each AVAILABLE file with a deadline reached (expiresAt != null && !now.isBefore(expiresAt);
 *   when atStartup also expiresAt == null) is deleted from disk (a missing file is fine), then EXPIRED
 * - if the map is non-empty and every file is SENT or EXPIRED, the directory is deleted (removed=true)
 * - else job.json is saved when something expired
 * @throws JobStoreException on an I/O error; the on-disk state is still consistent for a retry
 */
public Swept sweep(String jobId, Instant now, boolean atStartup);
```

`FileState.canMoveTo` already allows `AVAILABLE -> EXPIRED` and `SENDING -> AVAILABLE`; `update`'s `checkStates` is not used because the sweep saves directly under the lock. `discard` changes: delete all but `job.json`, then `job.json` last. Names avoid `delete`/`remove` verbs on `JobStore` (D10: SpotBugs EI_EXPOSE_REP2 heuristic); keep `recover`, `sweep`, `discard`.

```java
package com.video2podcast.job;

public class RetentionSweeper {
  public RetentionSweeper(JobStore store, ButtonRefresher buttons, Clock clock);

  /** Init method. recover() for every job id, then sweep(atStartup=true), then refresh buttons of RESET jobs that remain and were not refreshed. Never throws. */
  public void recoverAtStartup();

  /** Runtime pass: sweep(id, clock.instant(), false) for every job id. Never throws. */
  @Scheduled(fixedDelayString = "${app.jobs.sweep-interval}", initialDelayString = "${app.jobs.sweep-interval}")
  public void sweep();
}
```

Both methods log a `JobStoreException` or `IllegalArgumentException` per job (job id only, with the exception) and continue with the next id. `fixedDelay` guarantees runs never overlap. The first scheduled run is one interval after startup.

## Configuration

| Property | Env | Default | Meaning |
|---|---|---|---|
| `app.jobs.sweep-interval` | `JOB_SWEEP_INTERVAL` | `PT1M` | Time between the end of one sweep and the start of the next. `ExpiryIT` sets e.g. `PT1S`. |

`AppProperties.Jobs(workDir, maxVideoDuration, retention, metadataTimeout, downloadTimeout, ffmpegTimeout, sweepInterval)`: every `new Jobs(...)` in tests gains the last argument. `JobConfig` is annotated `@EnableScheduling`.

## Telegram

One request per job whose files expired: `editMessageReplyMarkup` for `chatId` / `summaryMessageId`, one row, audio first. Callback data `audio:<jobId>` and `transcript:<jobId>` are unchanged. Labels (existing in `Keyboards`): `Download audio`/`Preparing audio…`/`Audio sent`/`Audio expired`, `Download transcript`/`Sending transcript…`/`Transcript sent`/`Transcript expired`. No `deleteMessage`, `sendMessage` or upload. A rejected edit is logged only.

## Files

`job.json` format is unchanged. Retention uses the stored per-file `expiresAt`. No fallback age is derived from `createdAt` (see questions.md, A: Q-analyst-2).

## Startup ordering contract (Q-tester-1)

`TelegramBotInitializer` (starter 10.3.0) is an `InitializingBean`; its `afterPropertiesSet` registers every `SpringLongPollingBot` bean, so polling can begin there. Ordering is therefore enforced by bean dependency, not by the init method alone:

- `JobConfig` declares `@Bean(initMethod = "recoverAtStartup") RetentionSweeper retentionSweeper(...)` (the bean name is exactly `retentionSweeper`).
- `TelegramConfig.telegramBot(...)` is annotated `@DependsOn("retentionSweeper")`. The initializer receives the bot list through `ObjectProvider`, so the `telegramBot` bean must be created, and with it `retentionSweeper` fully initialised (init method returned), before `afterPropertiesSet` can register anything.
- Scheduled work: `@Scheduled` tasks are registered after all singletons exist (`ScheduledAnnotationBeanPostProcessor`, on `ContextRefreshedEvent`) and the first run is one interval later, so `sweep()` never runs before `recoverAtStartup()` returned.
- `RetentionSweeper` is the only bean that depends on neither `TelegramBot` nor the initializer, so there is no cycle. `retentionSweeper` needs the `ButtonRefresher` (via `ObjectProvider`, `SummaryButtonRefresher` needs only `TelegramGateway`, not the bot).

Developer coverage (unit, `src/test`): a context test with `ApplicationContextRunner` that registers a recording `RetentionSweeper` subclass and a bean standing in for the bot, and asserts the recorded order (recover before the bot bean is created); plus an assertion that the `telegramBot` bean definition has `retentionSweeper` in `getDependsOn()`. The Tester's harness excludes the starter, so it does not prove this order and does not have to.
