package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.video2podcast.access.AccessService;
import com.video2podcast.bot.DeliveryCallbackHandler;
import com.video2podcast.bot.TelegramBot;
import com.video2podcast.bot.UrlMessageHandler;
import com.video2podcast.job.ButtonRefresher;
import com.video2podcast.job.FileKind;
import com.video2podcast.job.FileState;
import com.video2podcast.job.JobFile;
import com.video2podcast.job.JobRecord;
import com.video2podcast.job.JobStore;
import com.video2podcast.job.RetentionSweeper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.EnableScheduling;

class JobConfigTest {

  @TempDir Path dir;

  private AppProperties props() {
    return new AppProperties(
        new AppProperties.Telegram("123:abc", 1L, true, ""),
        new AppProperties.Access(Path.of("a.json"), Duration.ofHours(24)),
        new AppProperties.Ollama("http://localhost:11434", "m", 8192, Duration.ofMinutes(10)),
        new AppProperties.Whisper("http://localhost:8081", Duration.ofMinutes(10)),
        new AppProperties.Jobs(
            dir,
            Duration.ofHours(3),
            Duration.ofHours(1),
            Duration.ofMinutes(1),
            Duration.ofMinutes(30),
            Duration.ofMinutes(30),
            Duration.ofMinutes(1)),
        new AppProperties.Health(Duration.ofSeconds(3)),
        new AppProperties.Deferred(Duration.ofHours(2), 12));
  }

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(JobConfig.class)
        .withBean(AppProperties.class, this::props)
        .withBean(
            Clock.class,
            () -> Clock.fixed(Instant.parse("2026-11-21T14:00:00Z"), java.time.ZoneOffset.UTC))
        .withPropertyValues("app.jobs.sweep-interval=PT1M");
  }

  private void expiredJob() throws IOException {
    Path jobDir = dir.resolve("job-1");
    Files.createDirectories(jobDir);
    Files.writeString(jobDir.resolve("a.m4a"), "a");
    Instant past = Instant.parse("2026-11-21T13:00:00Z");
    new JobStore(dir, Clock.systemUTC())
        .create(
            new JobRecord(
                "job-1",
                1L,
                2L,
                7,
                "T",
                1,
                past,
                Map.of(FileKind.AUDIO, new JobFile("a.m4a", FileState.AVAILABLE, past, null))));
  }

  @Test
  void enablesScheduling() {
    assertThat(JobConfig.class.isAnnotationPresent(EnableScheduling.class)).isTrue();
  }

  @Test
  void recoversAtStartupAndUsesTheButtonRefresherWhenThereIsOne() throws IOException {
    expiredJob();
    List<JobRecord> refreshed = new ArrayList<>();
    runner()
        .withBean(ButtonRefresher.class, () -> refreshed::add)
        .run(context -> {
          assertThat(context).hasSingleBean(RetentionSweeper.class);
          assertThat(dir.resolve("job-1")).doesNotExist();
          assertThat(refreshed).hasSize(1);
        });
  }

  @Test
  void worksWithoutTelegram() throws IOException {
    expiredJob();
    runner()
        .run(context -> {
          assertThat(context).hasSingleBean(RetentionSweeper.class);
          assertThat(dir.resolve("job-1")).doesNotExist();
        });
  }

  @Test
  void theBotIsCreatedOnlyAfterStartupRecoveryFinished() {
    List<String> order = new ArrayList<>();
    class RecordingSweeper extends RetentionSweeper implements InitializingBean {
      RecordingSweeper() {
        super(null, job -> {}, null);
      }

      @Override
      public void afterPropertiesSet() {
        order.add("recovered");
      }
    }
    BeanPostProcessor recordBot =
        new BeanPostProcessor() {
          @Override
          public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (bean instanceof TelegramBot) {
              order.add("bot");
            }
            return bean;
          }
        };
    telegramRunner()
        .withBean("retentionSweeper", RetentionSweeper.class, RecordingSweeper::new)
        .withBean(BeanPostProcessor.class, () -> recordBot)
        .run(context -> assertThat(order).containsExactly("recovered", "bot"));
  }

  private ApplicationContextRunner telegramRunner() {
    return new ApplicationContextRunner()
        .withUserConfiguration(TelegramConfig.class)
        .withBean(AppProperties.class, this::props)
        .withBean(AccessService.class, () -> mock(AccessService.class))
        .withBean(UrlMessageHandler.class, () -> mock(UrlMessageHandler.class))
        .withBean(DeliveryCallbackHandler.class, () -> mock(DeliveryCallbackHandler.class))
        .withPropertyValues("app.telegram.enabled=true");
  }

  @Test
  void theTelegramBotBeanDefinitionDependsOnTheSweeper() {
    telegramRunner()
        .withBean("retentionSweeper", RetentionSweeper.class, () -> mock(RetentionSweeper.class))
        .run(context ->
            assertThat(context.getBeanFactory().getBeanDefinition("telegramBot").getDependsOn())
                .containsExactly("retentionSweeper"));
  }
}
