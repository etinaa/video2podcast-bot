package com.video2podcast.config;

import com.video2podcast.bot.SummaryMessagePublisher;
import com.video2podcast.bot.TelegramGateway;
import com.video2podcast.bot.UrlMessageHandler;
import com.video2podcast.job.JobQueue;
import com.video2podcast.job.JobStore;
import com.video2podcast.job.PipelineService;
import com.video2podcast.job.SummaryPublisher;
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
  PipelineService pipelineService(
      YtDlpClient ytDlp,
      FfmpegRunner ffmpeg,
      TranscriptionClient transcription,
      SummaryService summary,
      JobStore store,
      SummaryPublisher publisher,
      Clock clock,
      AppProperties props) {
    return new PipelineService(
        ytDlp, ffmpeg, transcription, summary, store, publisher, clock, props.jobs());
  }

  @Bean
  UrlMessageHandler urlMessageHandler(
      TelegramGateway gateway,
      UrlValidator validator,
      JobQueue queue,
      PipelineService pipeline,
      Clock clock) {
    return new UrlMessageHandler(gateway, validator, queue, pipeline, clock);
  }
}
