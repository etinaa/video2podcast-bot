package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.video2podcast.job.JobFailure;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelegramDeferredNotifierTest {

  private final RecordingGateway gateway = new RecordingGateway();
  private final TelegramDeferredNotifier notifier = new TelegramDeferredNotifier(gateway);

  @Test
  void completedEditsTheStatusToDone() {
    notifier.completed(5, 9);

    assertThat(gateway.messages).isEmpty();
    assertThat(gateway.edits).containsExactly(new RecordingGateway.Edit(5, 9, "Done.", List.of()));
  }

  @Test
  void gaveUpSendsTheMessageAndEditsTheStatusToFailed() {
    notifier.gaveUp(5, 9);

    assertThat(gateway.messages)
        .containsExactly(
            new RecordingGateway.Sent(
                5, "We could not process this link. Please send it again later.", List.of()));
    assertThat(gateway.edits)
        .containsExactly(new RecordingGateway.Edit(5, 9, "Failed.", List.of()));
  }

  @Test
  void failedSendsTheEscapedFailureMessage() {
    notifier.failed(5, 9, new JobFailure(JobFailure.Kind.FAILED, "a <b> & c"));

    assertThat(gateway.messages).hasSize(1);
    assertThat(gateway.messages.get(0).html()).isEqualTo("a &lt;b&gt; &amp; c");
    assertThat(gateway.edits.get(0).html()).isEqualTo("Failed.");
  }

  @Test
  void aFailedSendStillLetsTheStatusEditRun() {
    gateway.failMessages = true;

    notifier.gaveUp(5, 9);
    notifier.failed(5, 9, JobFailure.of(JobFailure.Kind.PRIVATE));

    assertThat(gateway.edits)
        .extracting(RecordingGateway.Edit::html)
        .containsExactly("Failed.", "Failed.");
  }

  @Test
  void telegramErrorsAreDropped() {
    gateway.failMessages = true;
    gateway.failEdits = true;

    assertThatCode(
            () -> {
              notifier.completed(1, 2);
              notifier.gaveUp(1, 2);
              notifier.failed(1, 2, JobFailure.of(JobFailure.Kind.PRIVATE));
            })
        .doesNotThrowAnyException();
  }
}
