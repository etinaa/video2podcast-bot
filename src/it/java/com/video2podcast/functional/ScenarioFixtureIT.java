package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Guards scenario setup/teardown rather than silently accepting a broken HTTP fixture. */
class ScenarioFixtureIT {
  @Test
  void startupRequiresTheAllowlistAcknowledgementToReachTelegram() throws Exception {
    try (PipelineWorld w = new PipelineWorld()) {
      w.begin();
      w.reject = n -> n.path("text").asText().equals("User " + w.user + " allowed.");
      assertThatThrownBy(w::start)
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("allowlist acknowledgement");
    }
  }

  @Test
  void closedRetainedJobWorldCannotRemainActiveInSharedSteps() throws Exception {
    TranscriptWorld w = new TranscriptWorld();
    w.begin();
    assertThat(w.active).isTrue();
    w.close();
    assertThat(w.active).isFalse();
  }

  @Test
  void terminalStatusMustBelongToTheLatestSubmissionAndStoredChat() {
    PipelineWorld w = new PipelineWorld();
    var oldQueued = event("sendmessage", "Queued: position 1", 101, 0, 1);
    var currentQueued = event("sendmessage", "Queued: position 1", 101, 0, 3);
    w.events.add(oldQueued);
    w.responseIds.put(oldQueued, 10);
    w.events.add(event("editmessagetext", "Done.", 101, 10, 2));
    w.lastUpdateNanos = 3;
    w.events.add(currentQueued);
    w.responseIds.put(currentQueued, 11);
    // Even a late terminal event for the previous job must not finish this submission.
    w.events.add(event("editmessagetext", "Done.", 101, 10, 4));
    w.events.add(event("editmessagetext", "Failed.", 202, 11, 5));
    assertThat(w.latestTerminal()).isNull();
    var currentTerminal = event("editmessagetext", "Done.", 101, 11, 6);
    w.events.add(currentTerminal);
    assertThat(w.latestTerminal()).isSameAs(currentTerminal);
  }

  private static PipelineWorld.Event event(
      String method, String text, long chat, int message, long nanos) {
    String body =
        PipelineWorld.stringify(
            java.util.Map.of("text", text, "chat_id", chat, "message_id", message));
    return new PipelineWorld.Event(
        "telegram",
        "/bot/test/" + method,
        body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        nanos,
        java.time.Instant.EPOCH);
  }
}
