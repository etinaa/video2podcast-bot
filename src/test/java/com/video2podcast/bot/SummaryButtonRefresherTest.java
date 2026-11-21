package com.video2podcast.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.job.FileKind;
import com.video2podcast.job.FileState;
import com.video2podcast.job.JobFile;
import com.video2podcast.job.JobRecord;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SummaryButtonRefresherTest {

  private static final Instant T0 = Instant.parse("2026-11-21T12:00:00Z");

  private final RecordingGateway gateway = new RecordingGateway();
  private final SummaryButtonRefresher refresher = new SummaryButtonRefresher(gateway);

  private static JobRecord job(Integer messageId, Map<FileKind, JobFile> files) {
    return new JobRecord("job-1", 42L, 4242L, messageId, "Title", 60, T0, files);
  }

  private static JobFile file(FileState state) {
    return new JobFile("f", state, T0, null);
  }

  @Test
  void showsTheStoredStatesOnTheSummaryMessage() {
    refresher.refresh(
        job(
            700,
            Map.of(
                FileKind.AUDIO,
                file(FileState.SENT),
                FileKind.TRANSCRIPT,
                file(FileState.EXPIRED))));

    assertThat(gateway.buttonEdits).hasSize(1);
    RecordingGateway.Buttons edit = gateway.buttonEdits.get(0);
    assertThat(edit.chatId()).isEqualTo(4242L);
    assertThat(edit.messageId()).isEqualTo(700);
    assertThat(edit.buttons())
        .isEqualTo(Keyboards.delivery("job-1", FileState.SENT, FileState.EXPIRED));
  }

  @Test
  void countsAMissingFileAsAvailable() {
    Map<FileKind, JobFile> files = new HashMap<>();
    files.put(FileKind.TRANSCRIPT, file(FileState.EXPIRED));

    refresher.refresh(job(700, files));

    assertThat(gateway.buttonEdits.get(0).buttons())
        .isEqualTo(Keyboards.delivery("job-1", FileState.AVAILABLE, FileState.EXPIRED));

    gateway.buttonEdits.clear();
    refresher.refresh(job(700, Map.of(FileKind.AUDIO, file(FileState.EXPIRED))));

    assertThat(gateway.buttonEdits.get(0).buttons())
        .isEqualTo(Keyboards.delivery("job-1", FileState.EXPIRED, FileState.AVAILABLE));
  }

  @Test
  void doesNothingWithoutASummaryMessage() {
    refresher.refresh(job(null, Map.of()));

    assertThat(gateway.buttonEdits).isEmpty();
  }

  @Test
  void neverThrowsWhenTelegramRejectsTheEdit() {
    gateway.failButtons = true;

    refresher.refresh(job(700, Map.of()));

    assertThat(gateway.buttonEdits).isEmpty();
  }
}
