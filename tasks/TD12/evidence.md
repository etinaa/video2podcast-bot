# TD12 evidence


Date: 2026-11-24. Developer. No production code changed.

## Result: all four capabilities are present. Nothing is missing.

## Version
- Spring AI **2.1.0** (Maven Central, GA; 2.0.x is the first line for Boot 4, 2.0.3 is the latest 2.0).
- `spring-ai-ollama` 2.1.0 depends on Spring Framework 7.1.0 and Jackson 3.1.8. Correction after step 2: Boot 4.1.2 resolves Spring Framework 7.0.10 and Jackson 3.1.7, not 7.1; the combination with Spring AI 2.1.0 compiles and passes all tests (see D17).
- Import `org.springframework.ai:spring-ai-bom:2.1.0`; dependency `spring-ai-starter-model-ollama`.
- Resolved and compiled in a scratch project whose parent is `spring-boot-starter-parent:4.1.2` (scratchpad, not in the repo).

## Proof (scratch unit test against a JDK `HttpServer` stub of `/api/chat`)
Per-request options, built with `OllamaChatOptions.builder().model("qwen3:32b").numCtx(8192).numPredict(500).temperature(0.2).disableThinking().keepAlive("10m")`, passed in `new Prompt(messages, options)` to `OllamaChatModel.call(...)`. The model itself was built with a different default model ("default").

Request body the server received:

    {"model":"qwen3:32b","messages":[{"role":"system","content":"sys"},{"role":"user","content":"usr"}],"stream":false,"keep_alive":"10m","tools":[],"options":{"temperature":0.2,"num_predict":500,"num_ctx":8192},"think":false}

| Needed | Result |
|---|---|
| model per request | `"model":"qwen3:32b"` overrides the default |
| `num_ctx` | `options.num_ctx = 8192` |
| `num_predict` | `options.num_predict = 500` (our `maxOutputTokens`) |
| `think:false` | top-level `"think":false` via `disableThinking()` |
| `keep_alive`, `temperature`, `stream:false` | also sent as today (`10m`, 0.2, false) |
| `prompt_eval_count` | stub answer had `prompt_eval_count:42`; `ChatResponse.getMetadata().getUsage().getPromptTokens()` returned 42 |

## Differences to handle in step 2 (not blockers)
- Body has an extra `"tools":[]`. Harmless for Ollama; `src/it` WireMock stubs must not match the body strictly (Tester to check).
- `prompt_eval_count` is read as `Usage.getPromptTokens()`. Absent/zero must still map to the "no valid prompt_eval_count" failure, so keep the `< 1` check in our code. The missing-field case is not yet proven: to test in step 2.
- Timeouts and error kinds are not proven here. Spring AI 2.1 `OllamaApi` builds on `RestClient`/`WebClient` builders: the answer timeout (`OLLAMA_TIMEOUT`) must be set on the underlying request factory, and the failure mapping (`SUMMARY_UNREACHABLE` / `TIMEOUT` / `FAILED`) must be derived from the exceptions it throws, including Spring AI's retry wrapper. This is design work for step 2, tested with unit tests; no missing capability.
- Auto-configuration: the starter adds `OllamaChatModel`, `OllamaApi` and related beans, and may add the `spring.ai.ollama.*` properties. Step 2 must keep other beans unchanged (restrict to chat, disable model pull `init.pull-model-strategy=never`, map `OLLAMA_*` to our `app.ollama.*`).

## Decision
Go on with Spring AI 2.1.0: no capability is missing, so the brief does not require an owner decision (orchestrator, 2026-11-24).


## Live check (2026-11-24, orchestrator and owner)

The bot ran locally on the working tree with `OllamaLlmClient` (Spring AI 2.1.0), Whisper `ggml-large-v3-turbo` on 127.0.0.1:8081 and Ollama 0.40.16 on 127.0.0.1:11434 (`OLLAMA_MODEL` default `qwen3:32b`, `OLLAMA_NUM_CTX` default 8192). The owner sent `https://www.youtube.com/watch?v=j2TinDauTEI` and received a normal summary (title, `Duration: 3m 36s`, eight bullets). The bot log has no WARN or ERROR line. `GET /api/ps` on Ollama after the request: `qwen3:32b` loaded with `context_length` 8192, so `num_ctx` from the Spring AI request reached the model.

## Gates

- `./mvnw spotless:check` and `./mvnw verify -Dno-it`: green, 914 unit tests.
- `./mvnw verify -Pmutation -Dno-it`: green, mutation score 94% (1063 of 1133 killed; threshold 85%).
- The Developer's full `./mvnw verify` (before round 1 fixes): green, 488 IT cases, no `src/it` change needed.
