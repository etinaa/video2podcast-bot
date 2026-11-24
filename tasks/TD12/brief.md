# TD12 brief

Owner's request (2026-11-24): "Я обнаружил, что для ollama создается самописный клиент. Мы используем spring framework. Нужно тогда использовать Spring AI модуль".

Q: Task type? A: Technical (Recommended): no Analyst or Architect.
Q: Scope? A: Only the Ollama client (Recommended): the `LlmClient` implementation moves to Spring AI; the interface, prompts and `SummaryService` stay. The health check and Whisper are not touched.
Agreed with the whole task ("yes"): the first step is a compatibility check (Spring AI version for Spring Boot 4.1.x, per-request `num_ctx`, `num_predict`, `think:false`, `prompt_eval_count`); if something is missing the Developer stops and the owner decides.

Agreed task: see TD12 in `tasks/todo.md`. Recorded in the backlog only, not started.
