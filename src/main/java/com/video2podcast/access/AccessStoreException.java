package com.video2podcast.access;

/** The allowlist file could not be read or written. */
public class AccessStoreException extends RuntimeException {

  public AccessStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
