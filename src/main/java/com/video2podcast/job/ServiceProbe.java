package com.video2podcast.job;

import java.time.Duration;

/** One reachability check of an external service. */
@FunctionalInterface
public interface ServiceProbe {

  /** Never throws for an unreachable service; returns the result instead. */
  ProbeResult probe(Duration timeout);
}
