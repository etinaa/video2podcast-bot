package com.video2podcast.bot;

import com.video2podcast.job.JobRecord;
import com.video2podcast.job.SummaryPublisher;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends the summary of a finished job through Telegram. */
public class SummaryMessagePublisher implements SummaryPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(SummaryMessagePublisher.class);

  private final TelegramGateway gateway;

  public SummaryMessagePublisher(TelegramGateway gateway) {
    this.gateway = gateway;
  }

  @Override
  public int publish(JobRecord job, String summary) {
    return gateway.sendMessage(job.chatId(), html(job, summary), Keyboards.delivery(job.jobId()));
  }

  @Override
  public void withdraw(JobRecord job, int messageId, String summary) {
    try {
      gateway.editMessage(job.chatId(), messageId, html(job, summary), List.of());
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn("Could not take the buttons off the summary of job {}", job.jobId(), e);
      }
    }
  }

  private static String html(JobRecord job, String summary) {
    return SummaryMessageFormatter.format(job.title(), job.durationSeconds(), summary);
  }
}
