# TD11 brief

Owner's request: "Заводи TD11" (after the TD1 full gate failed with 401/403 on localhost twice).

Q: Task type? A: Technical (Recommended).

Root cause found by the orchestrator on 2026-11-23: other programs (Codex app-server daemon pid 36259, other Codex processes, IntelliJ IDEA) hold ports on 127.0.0.1; a WireMock bound to all interfaces on the same port loses to the more specific listener, and requests to localhost reach the foreign program, which answers 401/403. Proof: a wildcard socket on 54050 got `403 ... not_allowed_local ... baseline_policy` for a request to 127.0.0.1:54050.

Agreed task: see TD11 in `tasks/todo.md`. The Tester owns the fix in `src/it`; the Developer handles `TelegramConfigTest` (unit test). TD1 waits for this task, then gets its final `clean verify`.
