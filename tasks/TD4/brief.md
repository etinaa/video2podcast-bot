# TD4 brief

## Owner's original words
- "Сценарии cucumber не должны дергать код сервиса напрямую. Это могут быть вызовы через внешний API, появление данных в файлах, БД, очередях и т.д."
- "Вариант 1, переписать при T11" (then moved into its own task)
- "Создай техническую задачу для аналитика по исправлению сценариев тестов. Пусть ее проверят Архитектор и ты"

## Task type
Technical. Roles: Analyst (writes the features and the mapping table), Architect (reviews), orchestrator (reviews). No Developer, no Tester, no code reviewers.

## Questions and answers
- Scope: T8, T9 and TD2 scenarios. Owner: yes.
- TD2: the Analyst keeps what shows in the "Queued: position N" reply and leaves the rest to `JobQueueTest`, with a note in the mapping table. Owner: yes.
- Number TD4 in the backlog. Owner: yes.

## Agreed task
See TD4 in `tasks/todo.md`. T11 now depends on TD4 and writes the steps for the rewritten scenarios.
