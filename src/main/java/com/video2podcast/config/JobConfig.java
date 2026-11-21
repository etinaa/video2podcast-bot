package com.video2podcast.config;

import com.video2podcast.job.ButtonRefresher;
import com.video2podcast.job.JobQueue;
import com.video2podcast.job.JobStore;
import com.video2podcast.job.RetentionSweeper;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class JobConfig {

  @Bean
  JobStore jobStore(AppProperties props, Clock clock) {
    return new JobStore(props.jobs().workDir(), clock);
  }

  @Bean
  JobQueue jobQueue() {
    return new JobQueue();
  }

  /**
   * Recovers at startup, before the bot gets updates, then sweeps on a schedule. Without Telegram
   * there is no summary message to update, so the buttons step does nothing.
   */
  @Bean(initMethod = "recoverAtStartup")
  RetentionSweeper retentionSweeper(
      JobStore store, ObjectProvider<ButtonRefresher> buttons, Clock clock) {
    return new RetentionSweeper(store, buttons.getIfAvailable(() -> job -> {}), clock);
  }
}
