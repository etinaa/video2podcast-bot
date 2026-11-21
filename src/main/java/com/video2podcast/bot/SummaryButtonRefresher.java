package com.video2podcast.bot;

import com.video2podcast.job.ButtonRefresher;
import com.video2podcast.job.FileKind;
import com.video2podcast.job.FileState;
import com.video2podcast.job.JobFile;
import com.video2podcast.job.JobRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shows the stored file states on the buttons of a job's summary message. */
public class SummaryButtonRefresher implements ButtonRefresher {

  private static final Logger LOG = LoggerFactory.getLogger(SummaryButtonRefresher.class);

  private final TelegramGateway gateway;

  public SummaryButtonRefresher(TelegramGateway gateway) {
    this.gateway = gateway;
  }

  @Override
  public void refresh(JobRecord job) {
    if (job.summaryMessageId() == null) {
      return;
    }
    String jobId = job.jobId();
    try {
      gateway.editButtons(
          job.chatId(),
          job.summaryMessageId(),
          Keyboards.delivery(jobId, state(job, FileKind.AUDIO), state(job, FileKind.TRANSCRIPT)));
    } catch (RuntimeException e) {
      LOG.warn("Could not update the buttons of job {}", jobId, e);
    }
  }

  private static FileState state(JobRecord job, FileKind kind) {
    JobFile file = job.files().get(kind);
    return file == null ? FileState.AVAILABLE : file.state();
  }
}
