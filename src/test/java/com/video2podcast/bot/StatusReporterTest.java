package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatusReporterTest {

  private final RecordingGateway gateway = new RecordingGateway();
  private final StatusReporter reporter = new StatusReporter(gateway, 555, 77);

  @Test
  void editsTheStatusMessageThroughTheStages() {
    reporter.downloading();
    reporter.transcribing(1, 2);
    reporter.transcribing(2, 2);
    reporter.summarizing();

    assertThat(gateway.edits)
        .containsExactly(
            new RecordingGateway.Edit(555, 77, "Downloading…", List.of()),
            new RecordingGateway.Edit(555, 77, "Transcribing 1/2…", List.of()),
            new RecordingGateway.Edit(555, 77, "Transcribing 2/2…", List.of()),
            new RecordingGateway.Edit(555, 77, "Summarizing…", List.of()));
    assertThat(gateway.messages).isEmpty();
  }

  @Test
  void doesNotEditAgainWithTheSameText() {
    reporter.transcribing(1, 1);
    reporter.transcribing(1, 1);

    assertThat(gateway.edits).hasSize(1);
  }

  @Test
  void aRejectedEditIsSwallowedAndTheNextOneIsTried() {
    gateway.failEdits = true;
    assertThatCode(reporter::downloading).doesNotThrowAnyException();

    gateway.failEdits = false;
    reporter.summarizing();

    assertThat(gateway.edits)
        .extracting(RecordingGateway.Edit::html)
        .containsExactly("Summarizing…");
  }
}
