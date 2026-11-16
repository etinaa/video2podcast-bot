# video2podcast-bot

Spec: `SPEC.md`. Plan: `tasks/plan.md`. Tasks: `tasks/todo.md`.

## Task status in `tasks/todo.md`

- `[ ]` not started
- `[~]` Code Review: implemented and committed, waiting for both reviewers
- `[x]` done

## Finishing a task

1. `./mvnw verify` passes, commit `T<N>: <title>` (see the commit rule in `tasks/todo.md`). Set the task to `[~]` in the same commit.
2. Start two independent reviews of that commit, in parallel:
   - Claude: the `code-reviewer` agent, passing the task id and the commit SHA.
   - Codex: `scripts/codex-review.sh T<N> <sha>` (run in the background; it prints the review).
3. Do not read one review before the other is done, and do not pass one reviewer's output to the other.
4. Fix every Critical and Important finding in a separate fix commit, then re-run both reviews on it.
5. When both end with `VERDICT: APPROVED`, set the task to `[x]` in a commit of its own.
6. Do not call a task finished while it is `[~]`. If the reviewers disagree, show both verdicts to the owner.
