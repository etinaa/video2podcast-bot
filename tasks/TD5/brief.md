# TD5 brief

## Owner's original words
- "Может нужны 2 сценария? один с отдельной аудио дорожкой и один без"
- "по сценарию 2 действуй по рекомендации" (the recommendation: add `/best` as a fallback to the format selector)
- "техническая" (task type)

## Context
Found while rewriting the T8 scenarios (TD4): the real yt-dlp 2026.10.26 fails with "Requested format is not available" on a local mp4 that has video and sound together, with the selector `bestaudio[ext=m4a]/bestaudio`. With a separate m4a file the download works.

## Task type and roles
Technical. The Developer writes, both code reviewers review, the orchestrator approves. No Architect, no Analyst, no Tester (the owner chose "техническая" after seeing this list).

## Agreed task
See TD5 in `tasks/todo.md`. The evidence is kept in `tasks/TD5/evidence.md`, written by the orchestrator from the Developer's report.
