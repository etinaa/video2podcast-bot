package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Real closed-port connection refusals complement the feature's selective GET connection resets.
 * The latter keep inference/chat available for the waiting-job scenario; they are transport
 * failures, not literal connection refusals.
 */
class FailureModesIT {
  @ParameterizedTest
  @CsvSource({"true,false", "false,true", "true,true"})
  void refusedConnectionsAllowStartupButRejectWorkWithoutDownload(
      boolean whisperDown, boolean ollamaDown) throws Exception {
    PipelineWorld pipeline = new PipelineWorld();
    FailureModeWorld health = new FailureModeWorld(pipeline);
    health.begin();
    try {
      for (String service : List.of("whisper", "ollama")) {
        if (service.equals("whisper") ? whisperDown : ollamaDown) {
          try (ServerSocket socket = new ServerSocket(0)) {
            pipeline.properties.put(
                "app." + service + ".base-url", "http://127.0.0.1:" + socket.getLocalPort());
          }
        }
      }
      health.startupFinished();
      assertThat(health.logs).hasSize(2);
      for (String service : List.of("whisper", "ollama")) {
        boolean down = service.equals("whisper") ? whisperDown : ollamaDown;
        assertThat(
                health.logs.stream()
                    .filter(l -> l.text().startsWith("Health check: " + service + " ")))
            .singleElement()
            .satisfies(l -> {
              assertThat(l.text())
                  .isEqualTo(
                      "Health check: "
                          + service
                          + (down ? " unreachable (connection refused)" : " reachable"));
              assertThat(l.throwable()).isFalse();
            });
      }
      health.send(202, "/whoami");
      health.message(202, "Your Telegram id: <code>202</code>");
      health.send(202, "http://videos.example/refused");
      health.rejected();
      health.queuedTerminal(202, DeferredSupport.PARKED);
      assertThat(health.failures(202)).isEmpty();
      var jobs =
          PipelineWorld.json(PipelineWorld.read(pipeline.root.resolve("deferred-jobs.json")))
              .path("jobs");
      assertThat(jobs).hasSize(1);
      assertThat(jobs.get(0).path("ownerUserId").asLong()).isEqualTo(202);
      assertThat(jobs.get(0).path("attempts").asInt()).isZero();
      assertThat(health.tools).isEmpty();
      assertThat(pipeline.dirs()).isEmpty();
      assertThat(pipeline.service("whisper")).isEmpty();
      assertThat(pipeline.service("ollama")).isEmpty();
      pipeline.noUploads();
    } finally {
      health.close();
    }
  }
}
