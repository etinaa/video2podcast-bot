package com.video2podcast.bot;

/** Telegram HTML mode: only these three characters must be escaped. */
final class Html {

  private Html() {}

  static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
