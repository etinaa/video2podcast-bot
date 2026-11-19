package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.video2podcast.job.JobFailure;
import com.video2podcast.job.JobProgress;
import com.video2podcast.job.JobQueue;
import com.video2podcast.job.JobRequest;
import com.video2podcast.job.PipelineService;
import com.video2podcast.media.UrlValidator;
import com.video2podcast.support.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UrlMessageHandlerTest {

  private static final String URL = "https://videos.example/v";
  private static final Sender SENDER = new Sender(7, 555, "ann", "Ann");
  private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
  private static final String UNSUPPORTED = "This link is not a supported video.";
  private static final String FAILED = "Could not process this video. Please try again later.";

  private final ThreadSafeGateway gateway = new ThreadSafeGateway();
  private final JobQueue queue = new JobQueue(Duration.ofMillis(100));
  private final PipelineService pipeline = mock(PipelineService.class);
  private final UrlMessageHandler handler =
      new UrlMessageHandler(gateway, new UrlValidator(), queue, pipeline, new MutableClock(NOW));

  /** The gateway is called from the worker and from the completion thread. */
  private static class ThreadSafeGateway implements TelegramGateway {
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<RecordingGateway.Sent> sent = new CopyOnWriteArrayList<>();
    final List<RecordingGateway.Edit> edits = new CopyOnWriteArrayList<>();
    volatile boolean failSends;
    volatile boolean failEdits;
    volatile String rejectedText;
    volatile CountDownLatch blockSend;

    @Override
    public int sendMessage(long chatId, String html, List<InlineButton> buttons) {
      awaitBlock();
      if (failSends || html.equals(rejectedText)) {
        throw new TelegramGatewayException("down", null);
      }
      calls.add("send " + html);
      sent.add(new RecordingGateway.Sent(chatId, html, buttons));
      return 70 + sent.size();
    }

    @Override
    public void editMessage(long chatId, int messageId, String html, List<InlineButton> buttons) {
      if (failEdits) {
        throw new TelegramGatewayException("cannot edit", null);
      }
      calls.add("edit " + html);
      edits.add(new RecordingGateway.Edit(chatId, messageId, html, buttons));
    }

    private void awaitBlock() {
      CountDownLatch latch = blockSend;
      if (latch != null) {
        try {
          latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    @Override
    public void editButtons(long chatId, int messageId, List<InlineButton> buttons) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void sendDocument(long chatId, Path file, String fileName, String caption) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void sendAudio(
        long chatId, Path file, String fileName, String title, String performer, String caption) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void answerCallback(String callbackQueryId, String text) {
      throw new UnsupportedOperationException();
    }
  }

  @AfterEach
  void tearDown() {
    queue.close();
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached in 10 s");
      }
      Thread.sleep(5);
    }
  }

  @Test
  void aRejectedLinkGetsOneMessageAndNothingIsQueued() {
    handler.handle(SENDER, "http://localhost/video");

    assertThat(gateway.sent)
        .containsExactly(new RecordingGateway.Sent(555, UNSUPPORTED, List.of()));
    assertThat(gateway.edits).isEmpty();
    assertThat(queue.size()).isZero();
    verify(pipeline, never()).run(any(), any());
  }

  @Test
  void textThatIsNotALinkIsRejectedToo() {
    handler.handle(SENDER, "hello there");

    assertThat(gateway.calls).containsExactly("send " + UNSUPPORTED);
  }

  @Test
  void aRejectedLinkWhoseReplyCannotBeSentDoesNotThrow() {
    gateway.failSends = true;

    handler.handle(SENDER, "hello");

    assertThat(gateway.calls).isEmpty();
  }

  @Test
  void aSuccessfulJobShowsQueuedThenDone() throws Exception {
    handler.handle(SENDER, URL);

    await(() -> gateway.edits.size() == 1);
    assertThat(gateway.calls).containsExactly("send Queued: position 1", "edit Done.");
    assertThat(gateway.sent.get(0).buttons()).isEmpty();
    assertThat(gateway.edits.get(0))
        .isEqualTo(new RecordingGateway.Edit(555, 71, "Done.", List.of()));
  }

  @Test
  void theJobGetsTheStrippedLinkTheSenderAndTheAcceptanceTime() throws Exception {
    handler.handle(SENDER, "  " + URL + " \n");

    await(() -> gateway.edits.size() == 1);
    ArgumentCaptor<JobRequest> request = ArgumentCaptor.forClass(JobRequest.class);
    verify(pipeline).run(request.capture(), any());
    assertThat(request.getValue().url()).isEqualTo(URL);
    assertThat(request.getValue().ownerUserId()).isEqualTo(7);
    assertThat(request.getValue().chatId()).isEqualTo(555);
    assertThat(request.getValue().createdAt()).isEqualTo(NOW);
    assertThat(request.getValue().jobId()).isNotBlank();
  }

  @Test
  void stageReportsEditTheStatusMessageBetweenQueuedAndDone() throws Exception {
    doAnswer(
            call -> {
              JobProgress progress = call.getArgument(1);
              progress.downloading();
              progress.transcribing(1, 1);
              progress.summarizing();
              return null;
            })
        .when(pipeline)
        .run(any(), any());

    handler.handle(SENDER, URL);

    await(() -> gateway.edits.size() == 4);
    assertThat(gateway.calls)
        .containsExactly(
            "send Queued: position 1",
            "edit Downloading…",
            "edit Transcribing 1/1…",
            "edit Summarizing…",
            "edit Done.");
    assertThat(gateway.edits).extracting(RecordingGateway.Edit::messageId).containsOnly(71);
  }

  @Test
  void aJobFailureIsSentBeforeTheFailedStatus() throws Exception {
    doThrow(JobFailure.of(JobFailure.Kind.NO_SPEECH)).when(pipeline).run(any(), any());

    handler.handle(SENDER, URL);

    await(() -> gateway.edits.size() == 1);
    assertThat(gateway.calls)
        .containsExactly(
            "send Queued: position 1", "send No speech was found in this video.", "edit Failed.");
  }

  @Test
  void theFailureMessageIsHtmlEscaped() throws Exception {
    doThrow(new JobFailure(JobFailure.Kind.FAILED, "a <b> & c")).when(pipeline).run(any(), any());

    handler.handle(SENDER, URL);

    await(() -> gateway.edits.size() == 1);
    assertThat(gateway.sent.get(1).html()).isEqualTo("a &lt;b&gt; &amp; c");
  }

  @Test
  void anUnexpectedExceptionIsReportedAsTheGenericFailure() throws Exception {
    doThrow(new IllegalStateException("secret path /tmp/x")).when(pipeline).run(any(), any());

    handler.handle(SENDER, URL);

    await(() -> gateway.edits.size() == 1);
    assertThat(gateway.calls)
        .containsExactly("send Queued: position 1", "send " + FAILED, "edit Failed.");
  }

  @Test
  void theJobHasLeftTheQueueWhenTheFinalStatusIsEdited() throws Exception {
    AtomicBoolean queueEmptyAtDone = new AtomicBoolean();
    TelegramGateway watching =
        new ThreadSafeGateway() {
          @Override
          public void editMessage(long chatId, int id, String html, List<InlineButton> buttons) {
            queueEmptyAtDone.set(queue.size() == 0);
            super.editMessage(chatId, id, html, buttons);
          }
        };
    UrlMessageHandler other =
        new UrlMessageHandler(watching, new UrlValidator(), queue, pipeline, new MutableClock(NOW));

    other.handle(SENDER, URL);

    await(() -> !((ThreadSafeGateway) watching).edits.isEmpty());
    assertThat(queueEmptyAtDone).isTrue();
  }

  @Test
  void aSecondLinkWhileTheFirstRunsShowsPosition2() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            call -> {
              release.await(10, TimeUnit.SECONDS);
              return null;
            })
        .when(pipeline)
        .run(any(), any());

    handler.handle(SENDER, URL);
    handler.handle(SENDER, URL + "2");
    release.countDown();

    await(() -> gateway.edits.size() == 2);
    assertThat(gateway.sent)
        .extracting(RecordingGateway.Sent::html)
        .containsExactly("Queued: position 1", "Queued: position 2");
  }

  @Test
  void aFailedQueuedMessageStopsTheJobWithoutRunningItOrSayingAnything() throws Exception {
    gateway.failSends = true;

    handler.handle(SENDER, URL);

    await(() -> queue.size() == 0);
    verify(pipeline, never()).run(any(), any());
    assertThat(gateway.calls).isEmpty();
  }

  @Test
  void theNextJobStillRunsAfterAFailedQueuedMessage() throws Exception {
    gateway.failSends = true;
    handler.handle(SENDER, URL);
    await(() -> queue.size() == 0);
    gateway.failSends = false;

    handler.handle(SENDER, URL);

    await(() -> gateway.edits.size() == 1);
    verify(pipeline).run(any(), any());
  }

  @Test
  void aFailedFinalEditIsSwallowedAndTheQueueIsFree() throws Exception {
    gateway.failEdits = true;

    handler.handle(SENDER, URL);

    await(() -> queue.size() == 0);
    verify(pipeline).run(any(), any());
    gateway.failEdits = false;
    handler.handle(SENDER, URL);
    await(() -> gateway.edits.size() == 1);
  }

  @Test
  void aRejectedFailureMessageIsSwallowedAndFailedIsStillShown() throws Exception {
    doThrow(JobFailure.of(JobFailure.Kind.FAILED)).doNothing().when(pipeline).run(any(), any());
    gateway.rejectedText = FAILED;

    handler.handle(SENDER, URL);

    await(() -> gateway.edits.size() == 1);
    assertThat(gateway.calls).containsExactly("send Queued: position 1", "edit Failed.");
    handler.handle(SENDER, URL);
    await(() -> gateway.edits.size() == 2);
    assertThat(gateway.edits.get(1).html()).isEqualTo("Done.");
  }

  @Test
  void aJobCancelledWhileRunningIsReportedAsTheGenericFailure() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            call -> {
              started.countDown();
              awaitUninterruptibly(release);
              return null;
            })
        .when(pipeline)
        .run(any(), any());
    handler.handle(SENDER, URL);
    started.await(10, TimeUnit.SECONDS);

    queue.close();

    await(() -> gateway.edits.size() == 1);
    release.countDown();
    assertThat(gateway.calls)
        .containsExactly("send Queued: position 1", "send " + FAILED, "edit Failed.");
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    while (latch.getCount() > 0) {
      try {
        latch.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void aClosedQueueGetsTheGenericFailureAndNoStatusMessage() {
    queue.close();

    handler.handle(SENDER, URL);

    assertThat(gateway.calls).containsExactly("send " + FAILED);
  }

  @Test
  void aStatusMessageThatNeverArrivesStopsTheJobAndReportsAFailure() throws Exception {
    JobQueue shortQueue = new JobQueue(Duration.ofMillis(100));
    UrlMessageHandler impatient =
        new UrlMessageHandler(
            gateway,
            new UrlValidator(),
            shortQueue,
            pipeline,
            new MutableClock(NOW),
            Duration.ofMillis(50));
    CountDownLatch slowSend = new CountDownLatch(1);
    gateway.blockSend = slowSend;
    Thread sender = Thread.ofVirtual().start(() -> impatient.handle(SENDER, URL));
    Thread.sleep(300);
    gateway.blockSend = null;
    slowSend.countDown();
    sender.join(10_000);

    await(() -> gateway.edits.size() == 1);
    verify(pipeline, never()).run(any(), any());
    assertThat(gateway.calls).contains("send " + FAILED, "edit Failed.");
    shortQueue.close();
  }

  @Test
  void aWorkerStoppedWhileWaitingForTheStatusMessageDoesNotRunTheJob() throws Exception {
    JobQueue closing = new JobQueue(Duration.ofMillis(100));
    UrlMessageHandler other =
        new UrlMessageHandler(
            gateway, new UrlValidator(), closing, pipeline, new MutableClock(NOW));
    CountDownLatch slowSend = new CountDownLatch(1);
    gateway.blockSend = slowSend;
    Thread sender = Thread.ofVirtual().start(() -> other.handle(SENDER, URL));
    await(() -> closing.size() == 1);
    Thread.sleep(100);
    closing.close();
    gateway.blockSend = null;
    slowSend.countDown();
    sender.join(10_000);

    await(() -> gateway.edits.size() == 1);
    verify(pipeline, never()).run(any(), any());
    assertThat(gateway.calls).contains("send " + FAILED, "edit Failed.");
  }
}
