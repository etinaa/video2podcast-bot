package com.video2podcast.functional;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Required T19 target: full application, real media processes, mock model/Telegram HTTP. */
class LanguageIT {
  @ParameterizedTest
  @CsvSource({
    "ru,Russian,Русская речь.,Русское резюме.",
    "en,English,English speech.,English summary.",
    "de,German,Deutsche Rede.,unused"
  })
  void detectsLanguageAndPublishesOrRefuses(
      String code, String language, String text, String summary) throws Exception {
    PipelineWorld w = new PipelineWorld();
    w.begin();
    try {
      w.fixture(59, "m4a", "http://videos.example/video");
      w.defaultWhisper =
          PipelineWorld.Answer.json(
              PipelineWorld.stringify(java.util.Map.of("text", text, "language", code)));
      w.defaultContent = "- " + summary;
      w.send("http://videos.example/video");
      w.finishOne();
      w.drained();
      assertThat(w.service("whisper")).hasSize(1);
      var request = w.service("whisper").getFirst();
      LanguageSupport.field(request, "language", "auto");
      LanguageSupport.field(request, "response_format", "verbose_json");
      for (String field : java.util.List.of("translate", "detect_language"))
        LanguageSupport.field(request, field, "false");
      if (code.equals("de")) {
        w.reply("Only English and Russian videos are supported. Detected language: de.");
        assertThat(w.service("ollama")).isEmpty();
        assertThat(w.summaries()).isEmpty();
        assertThat(w.dirs()).isEmpty();
        var queue = w.root.resolve("deferred-jobs.json");
        if (Files.exists(queue))
          assertThat(PipelineWorld.json(PipelineWorld.read(queue)).path("jobs")).isEmpty();
      } else {
        assertThat(w.service("ollama")).hasSize(1);
        LanguageSupport.prompt(w.service("ollama").getFirst(), language);
        assertThat(LanguageSupport.input(w.service("ollama").getFirst())).isEqualTo(text);
        assertThat(Files.readAllBytes(w.publishedDir().resolve("transcript.txt")))
            .isEqualTo((text + "\n").getBytes(StandardCharsets.UTF_8));
        assertThat(w.body()).isEqualTo("• " + summary);
        new SummaryAssertionSteps(w).options("qwen3:32b", 8192);
      }
      w.noUploads();
    } finally {
      w.close();
    }
  }

  @org.junit.jupiter.api.Test
  void deliversBothLongCyrillicNamesWithoutTheMockMultipartHeaderLimit() throws Exception {
    PipelineWorld w = new PipelineWorld();
    w.begin();
    FailureModeWorld h = new FailureModeWorld(w);
    DeferredSteps deferred = new DeferredSteps(w, h);
    deferred.begin();
    LanguageSteps language = new LanguageSteps(w, h, deferred);
    LanguageDeliverySteps delivery = new LanguageDeliverySteps(language);
    try {
      delivery.captureUploads();
      w.fixture(59, "m4a", "http://videos.example/video");
      w.retitle("Я".repeat(101));
      w.defaultWhisper =
          PipelineWorld.Answer.json("{\"text\":\"Русская лекция.\",\"language\":\"russian\"}");
      w.defaultContent = "- Краткое содержание лекции.";
      language.completed("http://videos.example/video");
      delivery.tap("transcript");
      delivery.bounded("sendDocument", 100, "Я", ".txt");
      delivery.document("Я".repeat(100) + ".txt");
      delivery.utf8("Русская лекция.");
      delivery.tap("audio");
      delivery.bounded("sendAudio", 100, "Я", ".m4a");
      delivery.audio("Я".repeat(100) + ".m4a");
      delivery.validNames(204);
      delivery.noModels();
    } finally {
      try {
        h.close();
      } finally {
        delivery.closeRecorder();
      }
    }
  }
}
