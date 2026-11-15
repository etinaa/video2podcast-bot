package com.video2podcast.bot;

public class TelegramGatewayException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public TelegramGatewayException(String message, Throwable cause) {
    super(message, cause);
  }
}
