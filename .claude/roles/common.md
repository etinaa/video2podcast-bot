# Rules for every role

You are one agent in a team. Each agent runs in its own Herdr pane. The orchestrator (Product Manager) runs in the main pane, started the task and talks to the owner. You never talk to the owner directly.

Team, by Herdr agent name:

| Name | Role | Owns |
|---|---|---|
| `orchestrator` | Product Manager | `tasks/todo.md`, `tasks/T<N>/brief.md`, commits |
| `architect` | Architect | `tasks/T<N>/{components,data-flow,flows,api}.md`, `docs/architecture/`, `docs/decisions.md` |
| `analyst` | Analyst | `tasks/T<N>/{requirements,research}.md`, `src/features/*.feature` |
| `developer` | Developer | `src/main`, `src/test` (unit tests), `pom.xml`, `config/`, `deploy/`, `docs/host-setup.md` |
| `tester` | Tester | `src/it` (integration tests, Cucumber steps) |
| `reviewer-claude`, `reviewer-codex` | Code reviewers | `tasks/T<N>/review/*-<own name>.md` only |

Every agent may also write to `tasks/T<N>/comms/` (questions, approvals, status).

Paths in these files are relative to the repository root. Codex agents start in a subdirectory, because the sandbox makes only that directory and a few others writable. Codex agents run shell commands from the root with `cd "$(git rev-parse --show-toplevel)"`. Claude agents already start in the repository root: never put `cd` in a command, and give every path relative to the root. A compound command with `cd` and a relative read stops for manual approval whenever a `Read` deny rule exists (the reviewers have one), so a `cd` only creates a prompt.

Write only to the paths your role owns. If you need a change elsewhere, ask its owner.

Do not run `git add`, `git commit`, `git stash`, `git checkout`, `git reset` or anything else that changes the index, the branch or other people's files. The orchestrator makes every commit.

Read before you start: `SPEC.md`, `tasks/plan.md`, the task in `tasks/todo.md`, and everything already in `tasks/T<N>/`.

## Questions

All questions and answers go into `tasks/T<N>/comms/questions.md`, which is append-only. Several agents write to it at the same time, so append with the shell and never edit or rewrite it:

```bash
cat >> tasks/T<N>/comms/questions.md <<'EOF'

## Q-architect-3 · architect → analyst
Does a playlist link count as one job or one job per video?
EOF
```

- Question ids are `Q-<your name>-<n>`, numbered from 1 by each asker.
- An answer is a new entry: `## A: Q-architect-3 · analyst`, followed by the answer.
- Then tell the other agent, without waiting for a reply:

  ```bash
  herdr agent prompt analyst "[architect] Question Q-architect-3 in tasks/T<N>/comms/questions.md"
  ```

- Ask the role that owns the topic: requirements and scenarios go to `analyst`, components, contracts and flows to `architect`, a failing or unclear test to `tester`, code to `developer`.
- If the answer is not in `SPEC.md`, the task or the documentation, address the question to `owner` (`· architect → owner`). Do not prompt anyone. End your turn with `STATUS: NEEDS_OWNER Q-architect-3`. The orchestrator asks the owner and writes the answer.
- While you wait for an answer, carry on with what does not depend on it. When nothing is left, end your turn with `STATUS: WAITING <question id>`.
- When a message from another agent arrives, answer it first and then go back to your work.

## Status

End every turn with exactly one status line, the last line of your reply:

- `STATUS: DONE`: your work for the current step is finished.
- `STATUS: WAITING <question id>`
- `STATUS: NEEDS_OWNER <question id>`
- `STATUS: BLOCKED <one line why>`: something outside your role stops you, for example a tool is missing or a permission was denied.

Also write the same line to your status file with the shell, so the orchestrator can read it from the file:

```bash
echo "STATUS: DONE" > tasks/T<N>/comms/status/<your name>
```
