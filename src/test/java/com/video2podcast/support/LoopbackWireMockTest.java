package com.video2podcast.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class LoopbackWireMockTest {

  private static final InetAddress LOOPBACK = ipv4Loopback();

  @Test
  void startsAServerThatAcceptsConnectionsOnLoopback() throws IOException {
    WireMockServer server = LoopbackWireMock.start();
    try (Socket socket = new Socket(LOOPBACK, server.port())) {
      assertThat(server.isRunning()).isTrue();
      assertThat(socket.isConnected()).isTrue();
    } finally {
      server.stop();
    }
  }

  @Test
  void usesAnotherPortWhenAForeignListenerHoldsThePreferredOne() throws IOException {
    try (ServerSocket foreign = new ServerSocket(0, 1, LOOPBACK)) {
      WireMockServer server = LoopbackWireMock.start(foreign.getLocalPort());
      try {
        assertThat(server.isRunning()).isTrue();
        assertThat(server.port()).isNotEqualTo(foreign.getLocalPort());
      } finally {
        server.stop();
      }
    }
  }

  private static InetAddress ipv4Loopback() {
    try {
      return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }
}
