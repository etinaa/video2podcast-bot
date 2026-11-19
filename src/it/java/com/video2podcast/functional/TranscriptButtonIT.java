package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Full-context transcript callback checks; detailed scenario matrices are in CucumberIT. */
class TranscriptButtonIT extends FunctionalTest {
  TranscriptWorld w;

  @BeforeEach
  void fixtures() throws Exception {
    w = new TranscriptWorld();
    w.begin();
  }

  @AfterEach
  void cleanup() {
    w.close();
  }

  @Test
  void sendsRetainedBytesOnceAndKeepsAudioAvailable() throws Exception {
    w.tap("transcript:" + TranscriptWorld.JOB, 101, 701, 101, "private");
    w.state("SENT", true);
    w.multipart("Budget meeting.txt", 101);
    assertThat(w.file("AUDIO").path("state").asText()).isEqualTo("AVAILABLE");
    w.tap("transcript:" + TranscriptWorld.JOB, 101, 701, 101, "private");
    w.refusal("Already sent.");
    assertThat(w.calls("sendDocument")).hasSize(1);
    w.unchanged();
    w.noModels();
  }

  @Test
  void concurrentTapObservesDurableSendingStateAndCannotUpload() throws Exception {
    w.hold = true;
    w.tap("transcript:" + TranscriptWorld.JOB, 101, 701, 101, "private");
    w.state("SENDING", false);
    w.tap("transcript:" + TranscriptWorld.JOB, 101, 701, 101, "private");
    w.refusal("Already being sent.");
    assertThat(w.calls("sendDocument")).hasSize(1);
    w.finishUpload();
    w.state("SENT", true);
  }

  @Test
  void ownershipAndExpiryAreEnforcedOnPersistedJobs() throws Exception {
    w.allowed.add(202L);
    w.tap("transcript:" + TranscriptWorld.JOB, 202, 701, 101, "private");
    w.refusal("This button is not for you.");
    w.unchanged();
    w.change(j ->
        ((com.fasterxml.jackson.databind.node.ObjectNode) j.path("files").path("TRANSCRIPT"))
            .put("expiresAt", w.clock.instant().toString()));
    w.tap("transcript:" + TranscriptWorld.JOB, 101, 701, 101, "private");
    w.refusal("Expired, please resend the link.");
    assertThat(w.calls("sendDocument")).isEmpty();
    w.unchanged();
  }
}
