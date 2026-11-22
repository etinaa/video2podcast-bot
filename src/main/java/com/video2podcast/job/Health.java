package com.video2podcast.job;

/** The outcome of one check of both services. */
public record Health(ProbeResult whisper, ProbeResult ollama) {

  public boolean allReachable() {
    return whisper.reachable() && ollama.reachable();
  }
}
