# T10 brief

## Owner's original words
"Приступайте к T10. Задача стандартная" (the task text was agreed earlier in `tasks/todo.md`, Phase 3).

## Task type
Standard (product task): the full team (Analyst, Architect, Developer, Tester, two code reviewers).

## Questions and answers
None at intake: the task is fully described in `tasks/todo.md`, and its dependency (T3) is done.

## Rules that apply from earlier decisions
- Scenarios go through the external boundary only (owner's rule, `analyst.md`). T10 has no Telegram trigger before T11, so behaviour that is not visible from outside yet stays with the unit tests (`OllamaClientTest`, `SummaryServiceTest` on WireMock), and the Analyst says so in `requirements.md`.
- Mutation gate: `./mvnw verify -Pmutation` must keep a score of at least 85% (TD3).

## Agreed task
See T10 in `tasks/todo.md`.
