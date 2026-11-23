# TD9 brief

Owner's request: "Начать TD9". Origin: the T17 smoke run (2026-11-23), the converted M4A shows `00:00` in the Telegram chat until opened; the owner asked to put it in the backlog (TD9).

Type (owner): technical (no Analyst/Architect).

Q: What type is the task? A: Technical (Recommended).

Agreed task: see TD9 in `tasks/todo.md`. `sendAudio` gets a `duration` parameter from `job.durationSeconds()`; the field is left out when the stored value is not positive. Nothing else changes. The orchestrator writes the T13 scenario (tag `@TD9`); Developer and Tester implement.
