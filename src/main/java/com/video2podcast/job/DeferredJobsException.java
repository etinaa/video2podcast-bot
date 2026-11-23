package com.video2podcast.job;

/** The deferred-jobs file could not be read, renamed or written. */
public class DeferredJobsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DeferredJobsException(String message, Throwable cause) {
    super(message, cause);
  }
}
