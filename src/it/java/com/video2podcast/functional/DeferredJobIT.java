package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Dedicated real-context deferred flow; the full scenario matrix runs in CucumberIT. */
class DeferredJobIT {
  @Test
  void whisperOutageSurvivesRestartAndCompletesThroughScheduledRegistration() throws Exception {
    PipelineWorld w = new PipelineWorld();
    FailureModeWorld h = new FailureModeWorld(w);
    h.begin();
    DeferredSupport d = new DeferredSupport(w, h);
    d.install();
    try {
      w.fake = false;
      w.fixture(59, "m4a", "http://videos.example/video");
      h.next("whisper", "connection refusal");
      h.send(w.user, "http://videos.example/video");
      d.parked(w.user);
      assertThat(d.entry(w.user).path("attempts").asInt()).isZero();
      assertThat(w.dirs()).isEmpty();
      int notices = d.telegram().size();
      d.tick();
      assertThat(d.entry(w.user).path("attempts").asInt()).isEqualTo(1);
      assertThat(d.telegram()).hasSize(notices);
      d.restart();
      assertThat(d.entry(w.user).path("attempts").asInt()).isEqualTo(1);
      h.policy("whisper", "HTTP 200");
      d.tick();
      d.summary(w.user);
      assertThat(d.entries()).isEmpty();
      w.noUploads();
      d.tick();
      assertThat(w.summaries()).hasSize(1);
    } finally {
      h.close();
    }
  }

  @Test
  void realSchedulerFinishesRecoveredLinkWithoutAnyFurtherTelegramUpdate() throws Exception {
    PipelineWorld w = new PipelineWorld();
    FailureModeWorld h = new FailureModeWorld(w);
    h.begin();
    DeferredSupport observations = new DeferredSupport(w, h);
    try {
      w.fake = false;
      w.properties.put("app.deferred.retry-interval", "PT1S");
      w.fixture(59, "m4a", "http://videos.example/video");
      h.next("whisper", "connection refusal");
      h.send(w.user, "http://videos.example/video");
      observations.parked(w.user);
      h.policy("whisper", "HTTP 200");
      observations.summary(w.user);
      PipelineWorld.until(() -> assertThat(observations.entries()).isEmpty());
      assertThat(w.commands.stream().filter(a -> PipelineWorld.stage(a).equals("download")))
          .hasSize(1);
      w.noUploads();
    } finally {
      h.close();
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {2, 12})
  void scheduledOutagesExhaustBudgetOnceAndNeverReplay(int limit) throws Exception {
    PipelineWorld w = new PipelineWorld();
    FailureModeWorld h = new FailureModeWorld(w);
    h.begin();
    DeferredSupport d = new DeferredSupport(w, h);
    d.install();
    try {
      if (limit != 12) w.properties.put("app.deferred.max-attempts", Integer.toString(limit));
      h.next("whisper", "connection refusal");
      h.send(w.user, "http://videos.example/video");
      d.parked(w.user);
      int notices = d.telegram().size();
      for (int i = 1; i < limit; i++) {
        d.tick();
        assertThat(d.entry(w.user).path("attempts").asInt()).isEqualTo(i);
        assertThat(d.telegram()).hasSize(notices);
      }
      d.tick();
      assertThat(d.entries()).isEmpty();
      h.queuedTerminal(w.user, "Failed.");
      assertThat(h.messages(w.user).stream().filter(e -> e.text().equals(DeferredSupport.GAVE_UP)))
          .hasSize(1);
      int probes = h.probes.size();
      int finalNotices = d.telegram().size();
      d.tick();
      assertThat(h.probes).hasSize(probes);
      assertThat(d.telegram()).hasSize(finalNotices);
      assertThat(w.commands).isEmpty();
    } finally {
      h.close();
    }
  }
}
