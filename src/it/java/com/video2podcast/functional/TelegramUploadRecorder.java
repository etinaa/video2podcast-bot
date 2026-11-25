package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.video2podcast.functional.PipelineWorld.Event;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Captures original Telegram uploads before WireMock's fixed 512-byte multipart-header parser. JSON
 * calls still pass through WireMock. No upload name, header or payload is rewritten.
 */
final class TelegramUploadRecorder implements AutoCloseable {
  private final PipelineWorld w;
  private final HttpServer server;
  private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
  private final HttpClient client = HttpClient.newHttpClient();
  private final Map<String, List<Upload>> uploads = new ConcurrentHashMap<>();

  TelegramUploadRecorder(PipelineWorld world) throws IOException {
    w = world;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(handlers);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().toString();
            String method = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            byte[] response;
            int status;
            if (Set.of("senddocument", "sendaudio").contains(method)) {
              var event = new Event("telegram", path, bytes, System.nanoTime(), w.clock.instant());
              w.events.add(event);
              uploads
                  .computeIfAbsent(method, key -> new CopyOnWriteArrayList<>())
                  .add(new Upload(bytes, exchange.getRequestHeaders().getFirst("Content-Type")));
              w.responseReady.put(event, System.nanoTime());
              response = FunctionalTest.MESSAGE_OK.getBytes(StandardCharsets.UTF_8);
              status = 200;
            } else {
              var request =
                  HttpRequest.newBuilder(URI.create(w.telegram.baseUrl() + path))
                      .timeout(java.time.Duration.ofSeconds(15))
                      .header("Content-Type", exchange.getRequestHeaders().getFirst("Content-Type"))
                      .method(
                          exchange.getRequestMethod(),
                          HttpRequest.BodyPublishers.ofByteArray(bytes))
                      .build();
              var upstream = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
              response = upstream.body();
              status = upstream.statusCode();
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
          }
        });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  Upload one(String method) {
    List<Upload> requests = uploads.getOrDefault(method.toLowerCase(Locale.ROOT), List.of());
    assertThat(requests).hasSize(1);
    return requests.getFirst();
  }

  record Part(String headers, byte[] bytes) {
    String text() {
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  record Upload(byte[] bytes, String contentType) {
    Part part(String name) {
      assertThat(contentType).startsWith("multipart/form-data;");
      String boundary =
          contentType.substring(contentType.indexOf("boundary=") + 9).replace("\"", "");
      // ISO-8859-1 is a reversible byte mapping, so binary audio remains byte-for-byte intact.
      String body = new String(bytes, StandardCharsets.ISO_8859_1);
      for (String block : body.split(Pattern.quote("--" + boundary), -1)) {
        int split = block.indexOf("\r\n\r\n");
        if (split < 0) continue;
        String headers =
            new String(
                block.substring(0, split).getBytes(StandardCharsets.ISO_8859_1),
                StandardCharsets.UTF_8);
        var matcher =
            Pattern.compile("(?:^|;)\\s*name=\"([^\"]*)\"")
                .matcher(headers.substring(headers.indexOf(':') + 1));
        if (matcher.find() && matcher.group(1).equals(name)) {
          assertThat(block).endsWith("\r\n");
          return new Part(
              headers,
              block.substring(split + 4, block.length() - 2).getBytes(StandardCharsets.ISO_8859_1));
        }
      }
      return null;
    }
  }

  @Override
  public void close() {
    server.stop(0);
    handlers.close();
    client.close();
  }
}
