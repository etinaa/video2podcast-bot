package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.video2podcast.functional.PipelineWorld.Event;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Assertions on multipart and chat wire contracts, independent of production language helpers. */
final class LanguageSupport {
  private LanguageSupport() {}

  static String field(Event event, String name) {
    var matcher =
        Pattern.compile(
                "name=\""
                    + Pattern.quote(name)
                    + "\"[^\\r]*\\r\\n(?:[^\\r]+\\r\\n)*\\r\\n([^\\r]*)\\r\\n")
            .matcher(new String(event.bytes(), StandardCharsets.UTF_8));
    assertThat(matcher.find()).as("multipart field %s", name).isTrue();
    return matcher.group(1);
  }

  static void field(Event event, String name, String expected) {
    assertThat(field(event, name)).as("%s for %s", name, event.chunk()).isEqualTo(expected);
  }

  static void prompt(Event event, String language) {
    String system = event.json().path("messages").get(0).path("content").asText();
    assertThat(system).contains(language, "bullet", "- ");
    assertThat(system).doesNotContain("even if");
    if (language.equals("Russian")) assertThat(system).doesNotContain("English");
  }

  static String input(Event event) {
    return event.json().path("messages").get(1).path("content").asText();
  }
}
