package com.video2podcast.bot;

import com.video2podcast.job.DeliveryService;
import com.video2podcast.job.DeliveryService.Claim;
import com.video2podcast.job.FileKind;
import com.video2podcast.job.FileState;
import com.video2podcast.job.JobFile;
import com.video2podcast.job.JobRecord;
import com.video2podcast.job.JobStoreException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

/** Handles a tap on a download button: checks it, sends the file once and updates the buttons. */
public class DeliveryCallbackHandler {

  private static final Logger LOG = LoggerFactory.getLogger(DeliveryCallbackHandler.class);

  static final String SENDING = "Sending transcript…";
  static final String EXPIRED = "Expired, please resend the link.";
  static final String NOT_OWNER = "This button is not for you.";
  static final String ALREADY_SENT = "Already sent.";
  static final String IN_PROGRESS = "Already being sent.";
  static final String ERROR = "Something went wrong, please try again.";
  static final String SEND_FAILED = "Could not send the transcript. Tap the button to try again.";

  private final TelegramGateway gateway;
  private final DeliveryService delivery;

  public DeliveryCallbackHandler(TelegramGateway gateway, DeliveryService delivery) {
    this.gateway = gateway;
    this.delivery = delivery;
  }

  /**
   * Called only by {@link BotUpdateHandler} for data that starts with {@link
   * Keyboards#TRANSCRIPT_PREFIX}. Answers the tap exactly once and never throws for a failed
   * Telegram call or a job store error.
   */
  void handle(CallbackQuery callback) {
    String jobId = callback.getData().substring(Keyboards.TRANSCRIPT_PREFIX.length());
    Claim claim;
    try {
      claim = delivery.claim(jobId, callback.getFrom().getId(), FileKind.TRANSCRIPT);
    } catch (JobStoreException e) {
      LOG.error("Could not claim the transcript of job {}", jobId, e);
      answer(callback, ERROR);
      return;
    }
    switch (claim.outcome()) {
      case EXPIRED -> answer(callback, EXPIRED);
      case NOT_OWNER -> answer(callback, NOT_OWNER);
      case ALREADY_SENT -> answer(callback, ALREADY_SENT);
      case IN_PROGRESS -> answer(callback, IN_PROGRESS);
      case GRANTED -> send(callback, jobId, claim);
    }
  }

  private void send(CallbackQuery callback, String jobId, Claim claim) {
    JobRecord job = claim.job();
    answer(callback, SENDING);
    editButtons(job, jobId, audioState(job), FileState.SENDING);
    try {
      gateway.sendDocument(job.chatId(), claim.file(), FileNames.transcriptName(job.title()), null);
    } catch (RuntimeException e) {
      LOG.error("Could not send the transcript of job {}", jobId, e);
      fail(job, jobId);
      return;
    }
    Optional<JobRecord> done;
    try {
      done = delivery.complete(jobId, FileKind.TRANSCRIPT);
    } catch (RuntimeException e) {
      LOG.error("The transcript of job {} was sent but could not be marked as sent", jobId, e);
      return;
    }
    editButtons(job, jobId, audioState(done.orElse(job)), FileState.SENT);
  }

  private void fail(JobRecord job, String jobId) {
    try {
      delivery.release(jobId, FileKind.TRANSCRIPT);
    } catch (RuntimeException e) {
      LOG.error("Could not release the transcript of job {}", jobId, e);
    }
    editButtons(job, jobId, audioState(job), FileState.AVAILABLE);
    try {
      gateway.sendMessage(job.chatId(), SEND_FAILED, List.of());
    } catch (RuntimeException e) {
      LOG.warn("Could not tell the user that the transcript of job {} failed", jobId, e);
    }
  }

  private void editButtons(JobRecord job, String jobId, FileState audio, FileState transcript) {
    if (job.summaryMessageId() == null) {
      return;
    }
    try {
      gateway.editButtons(
          job.chatId(), job.summaryMessageId(), Keyboards.delivery(jobId, audio, transcript));
    } catch (RuntimeException e) {
      LOG.warn("Could not update the buttons of job {}", jobId, e);
    }
  }

  private void answer(CallbackQuery callback, String text) {
    String callbackId = callback.getId();
    try {
      gateway.answerCallback(callbackId, text);
    } catch (RuntimeException e) {
      LOG.warn("Could not answer callback {}", callbackId, e);
    }
  }

  private static FileState audioState(JobRecord job) {
    JobFile audio = job.files().get(FileKind.AUDIO);
    return audio == null ? FileState.AVAILABLE : audio.state();
  }
}
