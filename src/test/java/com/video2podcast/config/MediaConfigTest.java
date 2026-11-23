package com.video2podcast.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.media.AudioPreparer;
import com.video2podcast.media.DefaultProcessRunner;
import com.video2podcast.media.FfmpegRunner;
import com.video2podcast.media.ProcessResult;
import com.video2podcast.media.ProcessRunner;
import com.video2podcast.media.UrlValidator;
import com.video2podcast.media.YtDlpClient;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MediaConfigTest {

  private static AppProperties props() {
    return new AppProperties(
        null,
        null,
        null,
        null,
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

  private ApplicationContextRunner runner() {
    return new ApplicationContextRunner()
        .withBean(AppProperties.class, MediaConfigTest::props)
        .withUserConfiguration(MediaConfig.class);
  }

  @Test
  void createsTheRealRunnerAndTheMediaBeans() {
    runner()
        .run(context -> {
          assertThat(context.getBean(ProcessRunner.class)).isInstanceOf(DefaultProcessRunner.class);
          assertThat(context).hasSingleBean(UrlValidator.class);
          assertThat(context).hasSingleBean(YtDlpClient.class);
          assertThat(context).hasSingleBean(FfmpegRunner.class);
          assertThat(context).hasSingleBean(AudioPreparer.class);
        });
  }

  @Test
  void aSuppliedProcessRunnerReplacesTheRealOne() {
    ProcessRunner fake = (args, timeout) -> new ProcessResult(0, "", "");
    new ApplicationContextRunner()
        .withBean(AppProperties.class, MediaConfigTest::props)
        .withBean(ProcessRunner.class, () -> fake)
        .withUserConfiguration(MediaConfig.class)
        .run(context -> assertThat(context.getBean(ProcessRunner.class)).isSameAs(fake));
  }
}
