package com.video2podcast.job;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;

/** The shared {@code GET} probe of the service clients: any HTTP status means reachable. */
public final class HttpProbe {

  private HttpProbe() {}

  /** Never throws for an unreachable service; the reason is a fixed phrase. */
  public static ProbeResult get(HttpClient http, URI uri, Duration timeout) {
    try {
      HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
      http.send(request, BodyHandlers.discarding());
      return ProbeResult.up();
    } catch (ConnectException e) {
      return ProbeResult.down(
          e.getCause() instanceof UnresolvedAddressException
              ? "host not found"
              : "connection refused");
    } catch (UnresolvedAddressException e) {
      return ProbeResult.down("host not found");
    } catch (HttpTimeoutException e) {
      return ProbeResult.down("no answer in time");
    } catch (IOException e) {
      return ProbeResult.down("i/o error (" + e.getClass().getSimpleName() + ")");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ProbeResult.down("interrupted");
    }
  }
}
