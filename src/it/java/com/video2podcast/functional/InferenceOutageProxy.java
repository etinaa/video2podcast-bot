package com.video2podcast.functional;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Loopback HTTP relay: serves health probes, then closes its listener before returning inference 1.
 */
final class InferenceOutageProxy implements AutoCloseable {
  private final ServerSocket listener;
  private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
  private final List<Future<?>> requests = new CopyOnWriteArrayList<>();
  private final HttpClient http = HttpClient.newHttpClient();
  private final String upstream;
  private final Future<?> acceptor;

  InferenceOutageProxy(String upstream) throws IOException {
    this.upstream = upstream;
    listener = new ServerSocket();
    listener.bind(new InetSocketAddress("127.0.0.1", 0));
    acceptor =
        clients.submit(() -> {
          while (!listener.isClosed()) {
            try {
              Socket connection = listener.accept();
              requests.add(clients.submit(() -> relay(connection)));
            } catch (IOException e) {
              if (!listener.isClosed()) throw new UncheckedIOException(e);
            }
          }
        });
  }

  String url() {
    return "http://127.0.0.1:" + listener.getLocalPort();
  }

  private void relay(Socket socket) {
    try (socket) {
      socket.setSoTimeout(15000);
      InputStream input = socket.getInputStream();
      String[] request = line(input).split(" ");
      Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      for (String row; !(row = line(input)).isEmpty(); ) {
        int colon = row.indexOf(':');
        headers.put(row.substring(0, colon), row.substring(colon + 1).strip());
      }
      if (headers.containsKey("Transfer-Encoding"))
        throw new IOException("Expected fixed-length fixture request");
      int size = Integer.parseInt(headers.getOrDefault("Content-Length", "0"));
      byte[] body = input.readNBytes(size);
      if (body.length != size) throw new EOFException("Incomplete request");
      var builder =
          HttpRequest.newBuilder(URI.create(upstream + request[1]))
              .timeout(java.time.Duration.ofSeconds(15))
              .method(
                  request[0],
                  size == 0
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofByteArray(body));
      if (headers.containsKey("Content-Type"))
        builder.header("Content-Type", headers.get("Content-Type"));
      var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      if (request[1].equals("/inference")) listener.close();
      OutputStream output = socket.getOutputStream();
      output.write(
          ("HTTP/1.1 "
                  + response.statusCode()
                  + " Fixture\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "
                  + response.body().length
                  + "\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      output.write(response.body());
      output.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static String line(InputStream input) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int value; (value = input.read()) != -1; ) {
      if (value == '\n') return bytes.toString(StandardCharsets.US_ASCII).stripTrailing();
      bytes.write(value);
      if (bytes.size() > 16384) throw new IOException("Oversized HTTP header");
    }
    throw new EOFException("Incomplete HTTP header");
  }

  @Override
  public void close() throws Exception {
    listener.close();
    acceptor.get(15, TimeUnit.SECONDS);
    for (Future<?> request : requests) request.get(15, TimeUnit.SECONDS);
    clients.close();
    http.close();
  }
}
