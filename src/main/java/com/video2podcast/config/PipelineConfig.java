package com.video2podcast.config;

import com.video2podcast.access.AccessService;
import com.video2podcast.bot.DeliveryCallbackHandler;
import com.video2podcast.bot.SummaryButtonRefresher;
import com.video2podcast.bot.SummaryMessagePublisher;
import com.video2podcast.bot.TelegramDeferredNotifier;
import com.video2podcast.bot.TelegramGateway;
import com.video2podcast.bot.UrlMessageHandler;
import com.video2podcast.job.ButtonRefresher;
import com.video2podcast.job.DeferredJobRunner;
import com.video2podcast.job.DeferredJobs;
import com.video2podcast.job.DeferredNotifier;
import com.video2podcast.job.DeliveryService;
import com.video2podcast.job.HealthChecks;
import com.video2podcast.job.JobQueue;
import com.video2podcast.job.JobStore;
import com.video2podcast.job.PipelineService;
import com.video2podcast.job.SummaryPublisher;
import com.video2podcast.media.AudioPreparer;
import com.video2podcast.media.FfmpegRunner;
import com.video2podcast.media.UrlValidator;
import com.video2podcast.media.YtDlpClient;
import com.video2podcast.summary.SummaryService;
import com.video2podcast.transcript.TranscriptionClient;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the link-to-summary flow. It needs the Telegram gateway, so it exists with the bot. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "app.telegram",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PipelineConfig {

  @Bean
  SummaryPublisher summaryPublisher(TelegramGateway gateway) {
    return new SummaryMessagePublisher(gateway);
  }

  @Bean
  ButtonRefresher summaryButtonRefresher(TelegramGateway gateway) {
    return new SummaryButtonRefresher(gateway);
  }

  @Bean
  PipelineService pipelineService(
      YtDlpClient ytDlp,
      FfmpegRunner ffmpeg,
      TranscriptionClient transcription,
      SummaryService summary,
      JobStore store,
      SummaryPublisher publisher,
      Clock clock,
      HealthChecks health,
      AppProperties props) {
    return new PipelineService(
        ytDlp, ffmpeg, transcription, summary, store, publisher, clock, props.jobs(), health);
  }

  @Bean
  UrlMessageHandler urlMessageHandler(
      TelegramGateway gateway,
      UrlValidator validator,
      JobQueue queue,
      PipelineService pipeline,
      DeferredJobs deferred,
      Clock clock) {
    return new UrlMessageHandler(gateway, validator, queue, pipeline, deferred, clock);
  }

  @Bean
  DeferredJobs deferredJobs(AppProperties props, Clock clock) {
    return new DeferredJobs(props.access().file().resolveSibling("deferred-jobs.json"), clock);
  }

  @Bean
  DeferredNotifier deferredNotifier(TelegramGateway gateway) {
    return new TelegramDeferredNotifier(gateway);
  }

  @Bean
  DeferredJobRunner deferredJobRunner(
      DeferredJobs jobs,
      PipelineService pipeline,
      JobQueue queue,
      DeferredNotifier notifier,
      AccessService access,
      Clock clock,
      AppProperties props) {
    return new DeferredJobRunner(
        jobs, pipeline, queue, notifier, access::isAllowed, clock, props.deferred().maxAttempts());
  }

  @Bean
  DeliveryService deliveryService(JobStore store, Clock clock) {
    return new DeliveryService(store, clock);
  }

  @Bean
  DeliveryCallbackHandler deliveryCallbackHandler(
      TelegramGateway gateway, DeliveryService delivery, AudioPreparer audio, JobQueue queue) {
    return new DeliveryCallbackHandler(gateway, delivery, audio, queue);
  }
}
