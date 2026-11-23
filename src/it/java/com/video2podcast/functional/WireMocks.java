package com.video2podcast.functional;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.Extension;
import java.net.BindException;

/**
 * One WireMock server per external service, started once per JVM. The ports are fixed for the whole
 * run, so Spring can cache the application context across test classes.
 */
final class WireMocks {

  static final WireMockServer TELEGRAM = start();
  static final WireMockServer WHISPER = start();
  static final WireMockServer OLLAMA = start();

  private WireMocks() {}

  private static WireMockServer start() {
    WireMockServer server = scenarioServer();
    Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    return server;
  }

  /** Caller owns the lifetime of a scenario server; no global hook retains its request bodies. */
  static WireMockServer scenarioServer(Extension... extensions) {
    return scenarioServer(0, extensions);
  }

  /** Try the preferred port first (0 asks the OS); retry a collision on a fresh dynamic port. */
  static WireMockServer scenarioServer(int preferredPort, Extension... extensions) {
    for (int attempt = 1; ; attempt++) {
      WireMockServer server =
          new WireMockServer(
              wireMockConfig()
                  .bindAddress("127.0.0.1")
                  .port(attempt == 1 ? preferredPort : 0)
                  .extensions(extensions));
      try {
        server.start();
        return server;
      } catch (RuntimeException failure) {
        try {
          server.stop();
        } catch (Exception cleanupFailure) {
          // Jetty can report STOPPED after a partial startup; retain it without losing the retry.
          failure.addSuppressed(cleanupFailure);
        }
        if (attempt == 3 || !isBindFailure(failure)) throw failure;
      }
    }
  }

  private static boolean isBindFailure(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof BindException
          || (cause.getMessage() != null && cause.getMessage().contains("Address already in use")))
        return true;
    }
    return false;
  }

  static void resetAll() {
    TELEGRAM.resetAll();
    WHISPER.resetAll();
    OLLAMA.resetAll();
  }

  static String url(WireMockServer server) {
    return server.baseUrl();
  }
}
