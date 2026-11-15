package com.video2podcast.job;

/** A job file or directory could not be read, written or deleted. */
public class JobStoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public JobStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
