package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.video2podcast.job.FileKind;
import com.video2podcast.job.JobFile;
import com.video2podcast.job.JobRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SummaryMessagePublisherTest {

  private static final JobRecord JOB =
      new JobRecord(
          "job1",
          7,
          555,
          null,
          "A <talk>",
          300,
          Instant.parse("2026-11-19T10:00:00Z"),
          Map.of(FileKind.AUDIO, JobFile.available("source.m4a")));

  private static final String HTML = "<b>A &lt;talk&gt;</b>\nDuration: 5m\n\n• a";

  private final RecordingGateway gateway = new RecordingGateway();
  private final SummaryMessagePublisher publisher = new SummaryMessagePublisher(gateway);

  @Test
  void publishSendsTheSummaryWithTheTwoButtonsAndReturnsItsId() {
    int id = publisher.publish(JOB, "• a");

    assertThat(id).isEqualTo(1);
    assertThat(gateway.messages)
        .containsExactly(new RecordingGateway.Sent(555, HTML, Keyboards.delivery("job1")));
  }

  @Test
  void withdrawEditsTheSameTextWithoutButtons() {
    publisher.withdraw(JOB, 42, "• a");

    assertThat(gateway.edits).containsExactly(new RecordingGateway.Edit(555, 42, HTML, List.of()));
  }

  @Test
  void withdrawNeverThrows() {
    gateway.failEdits = true;

    assertThatCode(() -> publisher.withdraw(JOB, 42, "• a")).doesNotThrowAnyException();
  }
}
