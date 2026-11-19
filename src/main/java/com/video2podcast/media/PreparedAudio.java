package com.video2podcast.media;

import java.nio.file.Path;

/**
 * An audio file ready to upload.
 *
 * @param kbps the bitrate of an encoded file; 0 for {@code UNCHANGED}, where it is not known
 */
public record PreparedAudio(Path file, Mode mode, int kbps) {

  /** How the file was made. */
  public enum Mode {
    UNCHANGED,
    CONVERTED,
    COMPRESSED
  }
}
