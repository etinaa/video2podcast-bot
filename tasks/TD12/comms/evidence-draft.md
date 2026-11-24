# TD12 step 1: compatibility check (draft)

Date: 2026-11-24. Developer. No production code changed.

## Result: all four capabilities are present. Nothing is missing.

## Version
- Spring AI **2.1.0** (Maven Central, GA; 2.0.x is the first line for Boot 4, 2.0.3 is the latest 2.0).
- `spring-ai-ollama` 2.1.0 depends on Spring Framework 7.1.0 and Jackson 3.1.8. Boot 4.1.2 (the project parent) ships Framework 7.1.x, so 2.1.0 matches. (2.0.3 uses Framework 7.0.11, one minor behind.)
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

## Request for the owner decision
Go on with Spring AI 2.1.0 (no capability missing).

STATUS: DONE
