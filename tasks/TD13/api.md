# TD13 contracts

## Maven (`pom.xml`)

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-ollama</artifactId>   <!-- was spring-ai-starter-model-ollama; version from spring-ai-bom ${spring-ai.version} 2.1.0 -->
</dependency>
<!-- only if tools.jackson no longer resolves after the change (see components.md) -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-jackson</artifactId>   <!-- managed by Boot 4.1.2 -->
</dependency>
```

Required result of `./mvnw dependency:tree -Dincludes=org.springframework.ai`: `spring-ai-ollama`, `spring-ai-model`, `spring-ai-retry`, `spring-ai-commons`, `spring-ai-template-st`, `spring-ai-systemone` may appear. No `spring-ai-autoconfigure-*`, no `spring-ai-starter-*`, no `spring-ai-client-chat`. Also check `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` of the Spring AI jars on the classpath lists nothing. The Developer gives the output; the orchestrator writes it to `evidence.md`.

## Configuration

`application.yml`: delete the `spring.autoconfigure.exclude` key and its comment. `spring.application.name` stays. No new property. `OLLAMA_*` as before.

## Java

Signatures do not change:

```java
public interface LlmClient { LlmReply complete(String system, String user, int maxOutputTokens) throws LlmException; }
public final class OllamaLlmClient implements LlmClient { /* constructor and complete() as now */ }
```

`OllamaLlmClient.failure(Throwable)` (private static), new rule over the cause chain, first match wins:

1. `HttpConnectTimeoutException`, `ConnectException`, `UnresolvedAddressException` → `LlmException(CONNECTION)`
2. `HttpTimeoutException`, `SocketTimeoutException` → `LlmException(TIMEOUT)`
3. otherwise warn log (abbreviated message) → `LlmException(OTHER, "Ollama request failed")`

The `InterruptedException` branch is removed. `failure()` never touches `Thread.currentThread()`. `callWithin` is unchanged: its `catch (InterruptedException)` still cancels, re-sets the flag and throws `OTHER "Ollama request interrupted"`.

## Tests (Developer, `src/test/java`)

- `OllamaLlmClientTest.anInterruptionIsAnOtherFailureAndKeepsTheFlag` is renamed, e.g. `anInterruptionInTheCallThreadIsAnOtherFailureAndLeavesTheCallerFlagClear`. Same input; expect `reason() == OTHER` and `Thread.currentThread().isInterrupted()` false. Clear the flag in `finally` anyway. The message should be `"Ollama request failed"`.
- `SpringAiWiringTest` stays unchanged: it is the guard that only `ollamaApi` and `ollamaChatModel` exist.
- Update the comment in `SummaryConfig` ("so the starter's own chat beans back off") and the `SpringAiWiringTest` Javadoc ("the Spring AI starter adds nothing else") if they still mention the starter. Comments only.

## docs/decisions.md D17 (Architect writes)

Update the dependency to `spring-ai-ollama`, replace the starter-exclusion consequence with "no Spring AI auto-configuration on the classpath", and note the interrupt rule. Done in this task's design commit, with today's date as an update note.
