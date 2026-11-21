package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Instant;
import org.junit.jupiter.api.*;

/**
 * Focused acceptance entry point for real scheduler registration and full-context restart wiring.
 */
class ExpiryIT {
  TranscriptWorld w;

  @BeforeEach
  void fixtures() throws Exception {
    w = new TranscriptWorld();
    w.beginRetention();
    w.clock.now = Instant.parse("2026-11-21T12:00:00Z");
    w.change(j -> {
      for (String kind : java.util.List.of("AUDIO", "TRANSCRIPT"))
        w.retention.file(j, kind).put("expiresAt", "2026-11-21T13:00:00Z");
    });
    w.retention.baseline();
  }

  @AfterEach
  void close() {
    w.close();
  }

  @Test
  void idleApplicationExpiresFilesThroughRegisteredWallClockSchedule() {
    w.clock.now = Instant.parse("2026-11-21T13:00:00Z");
    // No test invokes the registered Runnable: release its wait and let Spring's timer run it.
    w.retention.schedule.automatic = true;
    w.retention.schedule.permits.release();
    PipelineWorld.until(() -> {
      assertThat(w.dir).doesNotExist();
      assertThat(w.calls("editMessageReplyMarkup")).hasSize(1);
      assertThat(w.retention.schedule.completed.get()).isGreaterThan(0);
    });
    w.labels(w.calls("editMessageReplyMarkup").getFirst(), "Audio expired", "Transcript expired");
    assertThat(w.calls("sendAudio")).isEmpty();
    assertThat(w.calls("sendDocument")).isEmpty();
    w.noModels();
  }

  @Test
  void expiredCallbackAndFreshContextUseOriginalDurableDeadlines() throws Exception {
    byte[] source = Files.readAllBytes(w.dir.resolve("source.m4a"));
    w.retention.state("TRANSCRIPT", "SENDING", null);
    w.retention.restart();
    w.retention.assertState("TRANSCRIPT", "AVAILABLE", null);
    assertThat(w.file("TRANSCRIPT").path("expiresAt").asText()).isEqualTo("2026-11-21T13:00:00Z");
    assertThat(Files.readAllBytes(w.dir.resolve("source.m4a"))).containsExactly(source);
    assertThat(w.calls("sendDocument")).isEmpty();
    w.tap("transcript:" + TranscriptWorld.JOB, 101, 701, 101, "private");
    w.multipart("Budget meeting.txt", 101);
    w.clock.now = Instant.parse("2026-11-21T13:00:00Z");
    w.retention.tick();
    assertThat(w.dir).doesNotExist();
    w.tap("audio:" + TranscriptWorld.JOB, 101, 701, 101, "private");
    w.refusal("Expired, please resend the link.");
    assertThat(w.calls("sendAudio")).isEmpty();
  }
}
