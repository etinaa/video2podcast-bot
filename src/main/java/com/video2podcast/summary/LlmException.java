package com.video2podcast.summary;

/** One call to the language model failed. The message is for logs only. */
public class LlmException extends Exception {

  private static final long serialVersionUID = 1L;

  /** What kind of failure it was. */
  public enum Reason {
    CONNECTION,
    TIMEOUT,
    OTHER
  }

  private final Reason reason;

  public LlmException(String message, Reason reason, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
