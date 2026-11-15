package com.video2podcast.job;

/**
 * Where a downloadable file is in its life. The only way forward is AVAILABLE, SENDING, SENT. A
 * failed send goes back to AVAILABLE, and a file that was never requested ends as EXPIRED.
 */
public enum FileState {
  AVAILABLE,
  SENDING,
  SENT,
  EXPIRED;

  public boolean canMoveTo(FileState next) {
    return switch (this) {
      case AVAILABLE -> next == SENDING || next == EXPIRED;
      case SENDING -> next == SENT || next == AVAILABLE;
      case SENT, EXPIRED -> false;
    };
  }
}
