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

## Revision (owner, after the review)
- Success scenarios use the real `ffmpeg` and `yt-dlp`, not mocks; mocks stay only for failures and argument checks.
- No external DNS: a thin `ProcessRunner` decorator in the harness adds `--proxy` to the `yt-dlp` call; WireMock holds the fixtures and answers with a `Content-Type`. Checked by hand with the real `yt-dlp` and a made-up host.
- Fixtures are generated with `ffmpeg` and stored in `src/it/resources`.
- Two download scenarios: separate audio track (m4a) and combined video with sound (mp4). The second needs the format fallback `/best`, which is a separate task (TD5) and the owner agreed to it ("по сценарию 2 действуй по рекомендации").
