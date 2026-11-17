# TD2 brief

## Owner's original words
"Начни работу над TD2" (the backlog ticket TD2 was created from the flaky `JobQueueTest` seen during T8, at the owner's request).

## Questions and answers
1. Where to fix the race: in production code (remove the task from `open` before completing its future) or in the test (bounded wait)? **Production code.** `size()` is the queue position T11 will show, so a stale count is a defect, not only a test problem.
2. How to prove it: add a deterministic test that reads `queue.size()` inside a `whenComplete` callback of the last task (success and failure), besides the one-off 200-run loop? **Yes.**

## Agreed task
See TD2 in `tasks/todo.md` (status `[~]`). No functional `*IT` tests: there is no user-visible behaviour yet.
