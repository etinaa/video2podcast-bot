package com.video2podcast.bot;

import com.video2podcast.job.DeliveryService;
import com.video2podcast.job.DeliveryService.Claim;
import com.video2podcast.job.FileKind;
import com.video2podcast.job.FileState;
import com.video2podcast.job.JobFile;
import com.video2podcast.job.JobQueue;
import com.video2podcast.job.JobRecord;
import com.video2podcast.job.JobStoreException;
import com.video2podcast.media.AudioPreparer;
import com.video2podcast.media.PreparedAudio;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

/** Handles a tap on a download button: checks it, sends the file once and updates the buttons. */
public class DeliveryCallbackHandler {

  private static final Logger LOG = LoggerFactory.getLogger(DeliveryCallbackHandler.class);

  static final String SENDING = "Sending transcript…";
  static final String PREPARING = "Preparing audio…";
  static final String EXPIRED = "Files already downloaded or expired, please resend the link.";
  static final String NOT_OWNER = "This button is not for you.";
  static final String ALREADY_SENT = "Already sent.";
  static final String IN_PROGRESS = "Already being sent.";
  static final String ERROR = "Something went wrong, please try again.";
  static final String SEND_FAILED = "Could not send the transcript. Tap the button to try again.";
  static final String AUDIO_SEND_FAILED = "Could not send the audio. Tap the button to try again.";
  static final String PERFORMER = "Video2Podcast";
  private static final int MAX_TITLE = 256;

  private final TelegramGateway gateway;
  private final DeliveryService delivery;
  private final AudioPreparer audio;
  private final JobQueue queue;

  public DeliveryCallbackHandler(
      TelegramGateway gateway, DeliveryService delivery, AudioPreparer audio, JobQueue queue) {
    this.gateway = gateway;
    this.delivery = delivery;
    this.audio = audio;
    this.queue = queue;
  }

  /**
   * Called only by {@link BotUpdateHandler} for data that starts with {@link
   * Keyboards#TRANSCRIPT_PREFIX} or {@link Keyboards#AUDIO_PREFIX}. Answers the tap exactly once
   * and never throws for a failed Telegram call or a job store error.
   */
  void handle(CallbackQuery callback) {
    boolean isAudio = callback.getData().startsWith(Keyboards.AUDIO_PREFIX);
    FileKind kind = isAudio ? FileKind.AUDIO : FileKind.TRANSCRIPT;
    String jobId =
        callback
            .getData()
            .substring((isAudio ? Keyboards.AUDIO_PREFIX : Keyboards.TRANSCRIPT_PREFIX).length());
    Claim claim;
    try {
      claim = delivery.claim(jobId, callback.getFrom().getId(), kind);
    } catch (JobStoreException e) {
      LOG.error("Could not claim the {} of job {}", kind, jobId, e);
      answer(callback, ERROR);
      return;
    }
    switch (claim.outcome()) {
      case EXPIRED -> answer(callback, EXPIRED);
      case NOT_OWNER -> answer(callback, NOT_OWNER);
      case ALREADY_SENT -> answer(callback, ALREADY_SENT);
      case IN_PROGRESS -> answer(callback, IN_PROGRESS);
      case GRANTED -> {
        if (isAudio) {
          startAudio(callback, jobId, claim);
        } else {
          send(callback, jobId, claim);
        }
      }
    }
  }

  private void startAudio(CallbackQuery callback, String jobId, Claim claim) {
    JobRecord job = claim.job();
    answer(callback, PREPARING);
    editButtons(job, jobId, FileState.SENDING, transcriptState(job));
    try {
      queue.submit(() -> deliverAudio(jobId, claim));
    } catch (RejectedExecutionException e) {
      LOG.error("Could not queue the audio of job {}", jobId, e);
      failAudio(job, jobId);
    }
  }

  /** Runs in the queue worker. Never throws. */
  private void deliverAudio(String jobId, Claim claim) {
    JobRecord job = claim.job();
    PreparedAudio prepared = null;
    try {
      prepared = audio.prepare(claim.file(), job.durationSeconds());
      String title = FileNames.limit(job.title() == null ? "" : job.title(), MAX_TITLE);
      gateway.sendAudio(
          job.chatId(),
          prepared.file(),
          FileNames.audioName(title, extensionOf(prepared.file())),
          title,
          PERFORMER,
          caption(prepared));
    } catch (RuntimeException e) {
      LOG.error("Could not send the audio of job {}", jobId, e);
      failAudio(job, jobId);
      return;
    } finally {
      if (prepared != null && !prepared.file().equals(claim.file())) {
        deleteQuietly(prepared.file());
      }
    }
    Optional<JobRecord> done;
    try {
      done = delivery.complete(jobId, FileKind.AUDIO);
    } catch (RuntimeException e) {
      LOG.error("The audio of job {} was sent but could not be marked as sent", jobId, e);
      return;
    }
    editButtons(job, jobId, FileState.SENT, transcriptState(done.orElse(job)));
  }

  private void failAudio(JobRecord job, String jobId) {
    FileState transcript = transcriptState(job);
    try {
      Optional<JobRecord> released = delivery.release(jobId, FileKind.AUDIO);
      transcript = transcriptState(released.orElse(job));
    } catch (RuntimeException e) {
      LOG.error("Could not release the audio of job {}", jobId, e);
    }
    editButtons(job, jobId, FileState.AVAILABLE, transcript);
    try {
      gateway.sendMessage(job.chatId(), AUDIO_SEND_FAILED, List.of());
    } catch (RuntimeException e) {
      LOG.warn("Could not tell the user that the audio of job {} failed", jobId, e);
    }
  }

  private static String caption(PreparedAudio prepared) {
    return switch (prepared.mode()) {
      case UNCHANGED -> "Original audio, not compressed.";
      case CONVERTED -> "Converted to M4A, 128 kbps.";
      case COMPRESSED ->
          "Compressed to mono AAC, " + prepared.kbps() + " kbps, to fit Telegram's 50 MB limit.";
    };
  }

  /** The lower-case extension of the file, or "m4a" when it has no file name or no extension. */
  static String extensionOf(Path file) {
    Path fileName = file.getFileName();
    if (fileName == null) {
      return "m4a";
    }
    String name = fileName.toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "m4a" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  private static void deleteQuietly(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LOG.warn("Could not delete {}", file, e);
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

  private static FileState transcriptState(JobRecord job) {
    JobFile transcript = job.files().get(FileKind.TRANSCRIPT);
    return transcript == null ? FileState.AVAILABLE : transcript.state();
  }

  private static FileState audioState(JobRecord job) {
    JobFile audio = job.files().get(FileKind.AUDIO);
    return audio == null ? FileState.AVAILABLE : audio.state();
  }
}
