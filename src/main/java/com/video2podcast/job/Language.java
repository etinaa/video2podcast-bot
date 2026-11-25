package com.video2podcast.job;

import java.util.Optional;

/** The languages of a video that the bot transcribes and summarizes. */
public enum Language {
  EN("en", "English"),
  RU("ru", "Russian");

  private final String code;
  private final String displayName;

  Language(String code, String displayName) {
    this.code = code;
    this.displayName = displayName;
  }

  /** The ISO 639-1 code sent to Whisper, such as {@code en}. */
  public String code() {
    return code;
  }

  /** The English name, used in the summary prompts. */
  public String displayName() {
    return displayName;
  }

  /** Exact, case-sensitive match on {@link #code()}; empty for anything else, also for null. */
  public static Optional<Language> fromCode(String code) {
    for (Language language : values()) {
      if (language.code.equals(code)) {
        return Optional.of(language);
      }
    }
    return Optional.empty();
  }
}
