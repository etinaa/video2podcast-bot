package com.video2podcast.config;

import com.video2podcast.media.DefaultProcessRunner;
import com.video2podcast.media.FfmpegRunner;
import com.video2podcast.media.ProcessRunner;
import com.video2podcast.media.UrlValidator;
import com.video2podcast.media.YtDlpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class MediaConfig {

  @Bean
  @ConditionalOnMissingBean(ProcessRunner.class)
  ProcessRunner processRunner() {
    return new DefaultProcessRunner();
  }

  @Bean
  UrlValidator urlValidator() {
    return new UrlValidator();
  }

  @Bean
  YtDlpClient ytDlpClient(ProcessRunner runner, UrlValidator validator, AppProperties props) {
    return new YtDlpClient(runner, validator, props.jobs());
  }

  @Bean
  FfmpegRunner ffmpegRunner(ProcessRunner runner, AppProperties props) {
    return new FfmpegRunner(runner, props.jobs());
  }
}
