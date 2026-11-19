# T10 flows

`LlmClient` below is `OllamaClient` in production. "check" is the truncation check from `api.md`.

## 1. Short transcript (main)

```mermaid
sequenceDiagram
  participant P as T11
  participant S as SummaryService
  participant L as LlmClient
  participant O as Ollama
  P->>S: summarize(text)
  Note over S: text.length() <= maxChunkChars
  S->>L: complete(SHORT, text, 1024)
  L->>O: POST /api/chat (model, num_ctx, think false)
  O-->>L: 200 content, prompt_eval_count
  L-->>S: LlmReply
  S->>S: check, format, cap 1500
  S-->>P: bullets
```

## 2. Long transcript, partials fit one reduction (main)

```mermaid
sequenceDiagram
  participant P as T11
  participant S as SummaryService
  participant L as LlmClient
  P->>S: summarize(text)
  S->>S: split into N chunks
  loop chunk 1..N, in order
    S->>L: complete(MAP, chunk, 1024)
    L-->>S: LlmReply (check each)
  end
  S->>L: complete(REDUCE_FINAL, partials joined, 1024)
  L-->>S: LlmReply (check)
  S->>S: format, cap 1500
  S-->>P: bullets
```
Total requests: N + 1.

## 3. Partials do not fit one request (further reduction)

```mermaid
sequenceDiagram
  participant S as SummaryService
  participant L as LlmClient
  Note over S: after the map step
  loop while joined partials > maxChunkChars (at most 8 rounds)
    S->>S: group partials in order, each group fits
    loop each group
      S->>L: complete(REDUCE_ROUND, group, 1024)
      L-->>S: LlmReply (check)
    end
  end
  S->>L: complete(REDUCE_FINAL, joined, 1024)
  L-->>S: LlmReply
```
Every group has at least 2 partials (guaranteed by the constructor rule), so each round shrinks the list. More than 8 rounds: `FAILED`.

## 4. Failures (any call: short, map, round or final)

```mermaid
sequenceDiagram
  participant P as T11
  participant S as SummaryService
  participant L as LlmClient
  participant O as Ollama
  P->>S: summarize(text)
  S->>L: complete(...)
  alt connection refused / unresolved / connect timeout
    L-->>S: LlmException CONNECTION
    S-->>P: JobFailure SUMMARY_UNREACHABLE
  else no answer within app.ollama.timeout
    L-->>S: LlmException TIMEOUT
    S-->>P: JobFailure TIMEOUT
  else non-2xx (404 model missing, 500), bad JSON, blank or missing content, bad token count
    L-->>S: LlmException OTHER
    S-->>P: JobFailure FAILED (summaryFailed)
  else answer valid but promptTokens < ceil(expected * 0.5)
    L-->>S: LlmReply
    S-->>P: JobFailure FAILED (truncated prompt)
  end
  Note over S: no later request is sent, no partial is returned
```

## 5. Invalid input and settings

- Constructor: `numCtx <= 0` or `textBudget < 2 * 1024` → `IllegalArgumentException` at start, no request.
- `summarize(null)` → `NullPointerException`. Blank transcript → `JobFailure FAILED`, no request.
- Answer with no usable bullet line after normalization → `FAILED`.

## 6. Output formatting

Final answer only: strip lines, drop blanks and one leading marker, prefix `• `, join with `\n`; over 1500 UTF-16 units → drop trailing bullets; a single bullet over 1500 → cut to 1499 (no split surrogate) plus `…`.

## Traceability to the Analyst's requirements

R1: flow 1-3, `OllamaClient` request. R2: Budget, `TextChunker`, flow 5. R3: flows 1-3, request counts. R4: `OllamaClient` mapping table. R5: truncation check in `SummaryService`. R6: `SummaryFormatter`. R7: flow 4 and the failure table. The `T10-summary.feature` scenarios (future, via T11) go through the same flows; T10 itself is covered by `OllamaClientTest` and `SummaryServiceTest`.
