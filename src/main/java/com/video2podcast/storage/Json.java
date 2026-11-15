package com.video2podcast.storage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** The JSON settings shared by every file the bot persists. */
public final class Json {

  /**
   * Dates are ISO-8601 strings, null fields are left out, unknown fields are ignored, and trailing
   * text after the document is an error (so a damaged file is never half-accepted).
   */
  public static final JsonMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .serializationInclusion(JsonInclude.Include.NON_NULL)
          .build();

  private Json() {}
}
