package com.video2podcast.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.BindException;

/**
 * Starts a WireMock server on 127.0.0.1 only. Other programs may listen on the same port on
 * 127.0.0.1, and a wildcard listener would lose to them, so the mock never binds to all interfaces.
 */
public final class LoopbackWireMock {

  private static final int ATTEMPTS = 3;

  private LoopbackWireMock() {}

  /** A started server on a free port. */
  public static WireMockServer start() {
    return start(0);
  }

  /** Tries {@code preferredPort} first (0 asks the OS); a collision is retried on a fresh port. */
  public static WireMockServer start(int preferredPort) {
    for (int attempt = 1; ; attempt++) {
      WireMockServer server =
          new WireMockServer(
              wireMockConfig().bindAddress("127.0.0.1").port(attempt == 1 ? preferredPort : 0));
      try {
        server.start();
        return server;
      } catch (RuntimeException failure) {
        try {
          server.stop();
        } catch (Exception cleanupFailure) {
          // Jetty can report STOPPED after a partial startup; keep it without losing the retry.
          failure.addSuppressed(cleanupFailure);
        }
        if (attempt == ATTEMPTS || !isBindFailure(failure)) {
          throw failure;
        }
      }
    }
  }

  private static boolean isBindFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof BindException
          || (cause.getMessage() != null
              && cause.getMessage().contains("Address already in use"))) {
        return true;
      }
    }
    return false;
  }
}
