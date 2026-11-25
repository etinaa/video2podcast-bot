# T19 brief

Owner's request: "Я хочу, чтобы для видео на русском языке Summary и Transcript формировались на русском" (raised while discussing new features before the last checkpoint).

Q: Task type? A: Product (full team), proposed by the orchestrator and not changed by the owner.
Q: Which languages? A: Russian and English with auto-detection (Recommended).
Q: Language of the bot's own texts? A: Keep English (Recommended); localization of the interface is a separate feature.
Q: Other languages? A: Refuse at once (Recommended), after detection on the first chunk, no deferred queue.
Confirmed with the whole task ("yes"): manual check on a Russian video, SPEC update, file names from the title.

Agreed task: see T19 in `tasks/todo.md`.

## Start (2026-11-25)

Owner: "Начни задачу T19". The intake above stands. Since it was written, TD12 and TD13 moved the Ollama client to Spring AI (`summary/OllamaLlmClient.java`, D17), so the summary prompts and the language reach Ollama through `OllamaLlmClient`. The Files line `summary/*` still covers it. Depends on T9, T10, T11, T18 are all done.
