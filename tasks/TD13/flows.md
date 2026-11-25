# TD13 flows

## Main: a summary call (unchanged)

```mermaid
sequenceDiagram
  participant W as Job worker
  participant C as OllamaLlmClient
  participant T as Virtual thread (CALLS)
  participant M as OllamaChatModel
  W->>C: complete(system, user, max)
  C->>T: submit chat.call(prompt)
  T->>M: call
  M-->>T: ChatResponse
  C->>T: get(timeout)
  T-->>C: ChatResponse
  C-->>W: LlmReply
```

## Failure: the call thread raised an `InterruptedException` (changed)

Cause chain of the `ExecutionException` contains an `InterruptedException`. This happens only in the call thread; the flag of the worker is not affected.

```mermaid
sequenceDiagram
  participant W as Job worker
  participant C as OllamaLlmClient
  participant T as Virtual thread
  T-->>C: ExecutionException(... InterruptedException)
  C->>C: failure(): no CONNECTION / TIMEOUT match, no interrupt branch
  C-->>W: LlmException(OTHER, "Ollama request failed"), warn log
  Note over W: Thread.currentThread().isInterrupted() stays false
```

## Failure: caller interrupted while waiting (unchanged)

```mermaid
sequenceDiagram
  participant W as Job worker
  participant C as OllamaLlmClient
  participant T as Virtual thread
  W--xC: interrupt during call.get
  C->>T: cancel(true)
  C->>W: set interrupt flag again
  C-->>W: LlmException(OTHER, "Ollama request interrupted")
```

Connection and timeout failures, and `TimeoutException` from `call.get`, are unchanged.
