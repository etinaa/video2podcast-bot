package com.video2podcast.transcript;

/** One call to whisper-server failed. The message is for logs only. */
public class WhisperException extends Exception {

  private static final long serialVersionUID = 1L;

  private final boolean connection;

  public WhisperException(String message, boolean connection, Throwable cause) {
    super(message, cause);
    this.connection = connection;
  }

  /** True when there was no connection (refused, unresolved host, connect timeout). */
  public boolean isConnection() {
    return connection;
  }
}
