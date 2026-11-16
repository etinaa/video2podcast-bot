package com.video2podcast.functional;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;

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
    WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    return server;
  }

  static void resetAll() {
    TELEGRAM.resetAll();
    WHISPER.resetAll();
    OLLAMA.resetAll();
  }

  static String url(WireMockServer server) {
    return "http://localhost:" + server.port();
  }
}
