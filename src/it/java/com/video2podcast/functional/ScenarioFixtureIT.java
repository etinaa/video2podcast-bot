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

  @Test
  void transcriptionWaitIgnoresEarlierSubmissionOutcomes() {
    PipelineWorld w = new PipelineWorld();
    w.lastUpdateNanos = 3;
    w.events.add(event("editmessagetext", "Failed.", 101, 10, 2));
    assertThat(w.transcriptionHasSettled()).isFalse();
    w.events.clear();
    w.events.add(
        new PipelineWorld.Event("ollama", "/api/chat", new byte[0], 2, java.time.Instant.EPOCH));
    assertThat(w.transcriptionHasSettled()).isFalse();
  }

  @Test
  void transcriptionWaitRequiresTheCurrentFailedOrSummarizingStatus() {
    PipelineWorld w = new PipelineWorld();
    w.lastUpdateNanos = 3;
    var queued = event("sendmessage", "Queued: position 1", 101, 0, 3);
    w.events.add(queued);
    w.responseIds.put(queued, 11);
    w.events.add(event("editmessagetext", "Failed.", 202, 11, 4));
    w.events.add(event("editmessagetext", "Failed.", 101, 10, 5));
    assertThat(w.transcriptionHasSettled()).isFalse();
    w.events.add(event("editmessagetext", "Failed.", 101, 11, 6));
    assertThat(w.transcriptionHasSettled()).isTrue();
    w.events.clear();
    // An earlier queued job can reach Ollama after the latest submission was accepted.
    w.events.add(queued);
    w.events.add(
        new PipelineWorld.Event("ollama", "/api/chat", new byte[0], 4, java.time.Instant.EPOCH));
    assertThat(w.transcriptionHasSettled()).isFalse();
    w.events.add(event("editmessagetext", "Summarizing…", 101, 10, 5));
    w.events.add(event("editmessagetext", "Summarizing…", 202, 11, 6));
    assertThat(w.transcriptionHasSettled()).isFalse();
    w.events.add(event("editmessagetext", "Summarizing…", 101, 11, 7));
    assertThat(w.transcriptionHasSettled()).isTrue();
  }

  @Test
  void startupProbeDeadlinesExcludeSlowApplicationBoot() {
    FailureModeWorld health =
        new FailureModeWorld(new PipelineWorld()) {
          @Override
          void startupFinished() {}
        };
    try {
      long probeStart = java.time.Duration.ofSeconds(11).toNanos();
      health.startup = java.time.Duration.ofSeconds(1).toNanos();
      health.observation = health.startup;
      for (String service : java.util.List.of("whisper", "ollama")) {
        health.probes.add(
            new FailureModeWorld.Probe(
                service,
                service.equals("whisper") ? "/" : "/api/version",
                new byte[0],
                probeStart));
        var policy = new FailureModeWorld.Policy();
        policy.hold = new PipelineWorld.Hold();
        health.policies.put(service, policy);
        health.logs.add(
            new FailureModeWorld.Log(
                "Health check: " + service + " unreachable (no answer in time)",
                false,
                probeStart + java.time.Duration.ofSeconds(1).toNanos()));
      }
      new FailureModeSteps(health).startupDeadline();
      health.logs.clear();
      health.heldService = "whisper";
      health.held = health.policies.get("whisper").hold;
      health.pendingAt(probeStart + java.time.Duration.ofMillis(100).toNanos(), true);
    } finally {
      health.updates.shutdownNow();
    }
  }

  @Test
  void t11WhisperRefusalOccursAfterMediaPreparationAndReachesTranscription() throws Exception {
    try (PipelineWorld w = new PipelineWorld()) {
      w.begin();
      w.fake = true;
      new PipelineSetupSteps(w).failure("connection refusal on both Whisper attempts");
      w.send("http://videos.example/t11-refusal");
      assertThat(w.finishOne().text()).isEqualTo("Failed.");
      w.reply(FailureModeWorld.WHISPER);
      assertThat(w.commands.stream().map(PipelineWorld::stage).toList())
          .containsExactly("metadata", "download", "WAV", "split");
      assertThat(w.calls("editMessageText")).anyMatch(e -> e.text().equals("Transcribing 1/1…"));
      assertThat(w.service("ollama")).isEmpty();
      assertThat(w.dirs()).isEmpty();
    }
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
