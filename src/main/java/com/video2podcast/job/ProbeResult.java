package com.video2podcast.job;

/** {@code reason} is empty when reachable, otherwise a fixed phrase without URL or stack trace. */
public record ProbeResult(boolean reachable, String reason) {

  public static ProbeResult up() {
    return new ProbeResult(true, "");
  }

  public static ProbeResult down(String reason) {
    return new ProbeResult(false, reason);
  }
}
