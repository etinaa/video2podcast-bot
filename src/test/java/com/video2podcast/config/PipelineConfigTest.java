package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.video2podcast.access.AccessService;
import com.video2podcast.bot.DeliveryCallbackHandler;
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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PipelineConfigTest {

  private static AppProperties props() {
    return new AppProperties(
        new AppProperties.Telegram("123:abc", 1L, true, ""),
        new AppProperties.Access(Path.of("a.json"), Duration.ofHours(24)),
        new AppProperties.Ollama("http://localhost:11434", "m", 8192, Duration.ofMinutes(10)),
        new AppProperties.Whisper("http://localhost:8081", Duration.ofMinutes(10)),
        new AppProperties.Jobs(
            Path.of("w"),
            Duration.ofHours(3),
            Duration.ofHours(1),
            Duration.ofMinutes(1),
            Duration.ofMinutes(30),
            Duration.ofMinutes(30),
            Duration.ofMinutes(1)),
        new AppProperties.Health(Duration.ofSeconds(3)),
        new AppProperties.Deferred(Duration.ofHours(2), 12));
  }

  private ApplicationContextRunner runner(boolean enabled) {
    return new ApplicationContextRunner()
        .withUserConfiguration(PipelineConfig.class)
        .withBean(AppProperties.class, PipelineConfigTest::props)
        .withBean(TelegramGateway.class, () -> mock(TelegramGateway.class))
        .withBean(YtDlpClient.class, () -> mock(YtDlpClient.class))
        .withBean(FfmpegRunner.class, () -> mock(FfmpegRunner.class))
        .withBean(AudioPreparer.class, () -> mock(AudioPreparer.class))
        .withBean(TranscriptionClient.class, () -> mock(TranscriptionClient.class))
        .withBean(SummaryService.class, () -> mock(SummaryService.class))
        .withBean(JobStore.class, () -> mock(JobStore.class))
        .withBean(JobQueue.class, () -> mock(JobQueue.class))
        .withBean(UrlValidator.class, UrlValidator::new)
        .withBean(HealthChecks.class, () -> mock(HealthChecks.class))
        .withBean(AccessService.class, () -> mock(AccessService.class))
        .withBean(Clock.class, Clock::systemUTC)
        .withPropertyValues("app.telegram.enabled=" + enabled);
  }

  @Test
  void createsThePipelineBeansWhenTheBotIsEnabled() {
    runner(true)
        .run(context -> {
          assertThat(context).hasSingleBean(SummaryPublisher.class);
          assertThat(context).hasSingleBean(PipelineService.class);
          assertThat(context).hasSingleBean(ButtonRefresher.class);
          assertThat(context).hasSingleBean(UrlMessageHandler.class);
          assertThat(context).hasSingleBean(DeliveryService.class);
          assertThat(context).hasSingleBean(DeferredJobs.class);
          assertThat(context).hasSingleBean(DeferredNotifier.class);
          assertThat(context).hasSingleBean(DeferredJobRunner.class);
          assertThat(context).hasSingleBean(DeliveryCallbackHandler.class);
          assertThat(context.getBean(SummaryPublisher.class)).isNotNull();
          assertThat(context.getBean(PipelineService.class)).isNotNull();
          assertThat(context.getBean(UrlMessageHandler.class)).isNotNull();
        });
  }

  @Test
  void createsNothingWhenTheBotIsDisabled() {
    runner(false)
        .run(context -> {
          assertThat(context).doesNotHaveBean(SummaryPublisher.class);
          assertThat(context).doesNotHaveBean(PipelineService.class);
          assertThat(context).doesNotHaveBean(ButtonRefresher.class);
          assertThat(context).doesNotHaveBean(UrlMessageHandler.class);
          assertThat(context).doesNotHaveBean(DeliveryService.class);
          assertThat(context).doesNotHaveBean(DeferredJobRunner.class);
          assertThat(context).doesNotHaveBean(DeliveryCallbackHandler.class);
        });
  }
}
