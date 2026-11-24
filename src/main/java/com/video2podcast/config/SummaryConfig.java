package com.video2podcast.config;

import com.video2podcast.summary.LlmClient;
import com.video2podcast.summary.OllamaLlmClient;
import com.video2podcast.summary.OllamaProbe;
import com.video2podcast.summary.SummaryService;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
public class SummaryConfig {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /** A number or an array where text is expected is an invalid answer, not a value to coerce. */
  private static final JacksonJsonHttpMessageConverter STRICT_JSON =
      new JacksonJsonHttpMessageConverter(
          JsonMapper.builder()
              .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
              .withCoercionConfig(
                  LogicalType.Textual,
                  config ->
                      config
                          .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                          .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                          .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
              .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
              .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .build());

  /** The Spring AI model is built here, so the starter's own chat beans back off. */
  @Bean
  OllamaApi ollamaApi(AppProperties props) {
    AppProperties.Ollama config = props.ollama();
    requireValid(config);
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(config.timeout());
    return OllamaApi.builder()
        .baseUrl(config.baseUrl())
        .restClientBuilder(
            RestClient.builder()
                .requestFactory(factory)
                .configureMessageConverters(
                    converters -> converters.registerDefaults().withJsonConverter(STRICT_JSON)))
        .build();
  }

  @Bean
  OllamaChatModel ollamaChatModel(OllamaApi api, AppProperties props) {
    return OllamaChatModel.builder()
        .ollamaApi(api)
        .options(OllamaChatOptions.builder().model(props.ollama().model()).build())
        .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
        .build();
  }

  @Bean
  LlmClient llmClient(OllamaChatModel chat, AppProperties props) {
    return new OllamaLlmClient(
        chat, props.ollama().model(), props.ollama().numCtx(), props.ollama().timeout());
  }

  @Bean
  OllamaProbe ollamaProbe(AppProperties props) {
    return new OllamaProbe(props.ollama().baseUrl());
  }

  @Bean
  SummaryService summaryService(LlmClient llm, AppProperties props) {
    return new SummaryService(llm, props.ollama());
  }

  private static void requireValid(AppProperties.Ollama config) {
    if (config.model() == null || config.model().isBlank()) {
      throw new IllegalArgumentException("app.ollama.model must not be blank");
    }
    if (config.timeout() == null || config.timeout().isZero() || config.timeout().isNegative()) {
      throw new IllegalArgumentException("app.ollama.timeout must be positive");
    }
  }
}
