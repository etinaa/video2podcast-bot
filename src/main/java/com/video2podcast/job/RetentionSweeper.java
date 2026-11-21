package com.video2podcast.job;

import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Decides when a job's files and directory are reclaimed: once at startup, after an interrupted
 * run, and then on a fixed delay. The per-job rules and locking live in {@link JobStore}; this
 * class walks the jobs, keeps one failing job from stopping the others and asks for new buttons
 * when files expired. Knows nothing about Telegram.
 */
public class RetentionSweeper {

  private static final Logger LOG = LoggerFactory.getLogger(RetentionSweeper.class);

  private final JobStore store;
  private final ButtonRefresher buttons;
  private final Clock clock;

  public RetentionSweeper(JobStore store, ButtonRefresher buttons, Clock clock) {
    this.store = store;
    this.buttons = buttons;
    this.clock = clock;
  }

  /**
   * Init method, so it finishes before the bot gets its first update. Per job: removes an unusable
   * directory, resets interrupted sends, then sweeps with startup rules. The buttons are refreshed
   * once when the job lost a file or a send was reset and the job is still there.
   */
  public void recoverAtStartup() {
    for (String id : ids()) {
      try {
        JobStore.Recovered recovered = store.recover(id);
        JobStore.Swept swept = store.sweep(id, clock.instant(), true);
        if (!swept.expired().isEmpty()) {
          buttons.refresh(swept.job());
        } else if (recovered.recovery() == JobStore.Recovery.RESET && !swept.removed()) {
          buttons.refresh(swept.job() == null ? recovered.job() : swept.job());
        }
      } catch (RuntimeException e) {
        LOG.warn("Startup cleanup of job {} failed", id, e);
      }
    }
  }

  @Scheduled(
      fixedDelayString = "${app.jobs.sweep-interval}",
      initialDelayString = "${app.jobs.sweep-interval}")
  public void sweep() {
    for (String id : ids()) {
      try {
        JobStore.Swept swept = store.sweep(id, clock.instant(), false);
        if (!swept.expired().isEmpty()) {
          buttons.refresh(swept.job());
        }
      } catch (RuntimeException e) {
        LOG.warn("Cleanup of job {} failed", id, e);
      }
    }
  }

  private List<String> ids() {
    try {
      return store.jobIds();
    } catch (RuntimeException e) {
      LOG.warn("Cannot list the jobs", e);
      return List.of();
    }
  }
}
