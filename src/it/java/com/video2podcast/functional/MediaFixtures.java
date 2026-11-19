package com.video2podcast.functional;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Small, real ffmpeg recordings cached on disk; duration does not require real-time encoding. */
final class MediaFixtures {
  private MediaFixtures() {}

  static synchronized Path recording(long seconds, String ext) throws IOException {
    Path dir = Path.of("src/it/resources/media");
    Files.createDirectories(dir);
    Path out = dir.resolve("tone-" + seconds + "." + ext);
    if (Files.isRegularFile(out)) return out;
    var args =
        new ArrayList<>(
            List.of(
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=16000",
                "-t",
                Long.toString(seconds)));
    if (ext.equals("mp4"))
      args.addAll(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "color=c=black:s=16x16:r=1",
              "-t",
              Long.toString(seconds),
              "-c:v",
              "libx264",
              "-pix_fmt",
              "yuv420p"));
    args.addAll(List.of("-c:a", "aac", "-b:a", "8k", out.toString()));
    Path temporary = Files.createTempFile(dir, "generating-", "." + ext);
    args.set(args.size() - 1, temporary.toString());
    try {
      run(args);
      Files.move(
          temporary, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
    return out;
  }

  static synchronized Path wav() throws IOException {
    Path p = Path.of("src/it/resources/media/fixture.wav");
    if (!Files.exists(p)) {
      Files.createDirectories(p.getParent());
      run(
          List.of(
              "ffmpeg",
              "-hide_banner",
              "-loglevel",
              "error",
              "-y",
              "-f",
              "lavfi",
              "-i",
              "sine=frequency=440:sample_rate=16000",
              "-t",
              "0.1",
              "-ac",
              "1",
              "-c:a",
              "pcm_s16le",
              p.toString()));
    }
    return p;
  }

  static void run(List<String> args) throws IOException {
    Path log = Files.createTempFile(Path.of("target"), "fixture-", ".log");
    Process p =
        new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      if (!p.waitFor(60, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new IOException("ffmpeg fixture timeout: " + args);
      }
      if (p.exitValue() != 0) throw new IOException(Files.readString(log));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }
}
