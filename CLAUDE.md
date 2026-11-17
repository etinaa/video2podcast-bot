# video2podcast-bot

Spec: `SPEC.md`. Plan: `tasks/plan.md`. Tasks: `tasks/todo.md`.

## Team process

Every task goes through a team of role agents in Herdr panes: Architect, Analyst, Developer, Tester and two code reviewers. The main session is the Product Manager and orchestrator, the only agent that talks to the owner. Do not use subagents for these roles.

Run a task with the `orchestrate` skill (`.claude/skills/orchestrate/SKILL.md`). The rules for each role are in `.claude/roles/`.

In short:

1. The orchestrator agrees the task with the owner and adds it to `tasks/todo.md`.
2. The Architect and the Analyst design it in parallel.
3. The Developer, the Tester and the orchestrator approve the design.
4. The Developer and the Tester work in parallel in `main`.
5. Every change gets `VERDICT: APPROVED` from both reviewers before it is committed.

## Task status in `tasks/todo.md`

- `[ ]` not started
- `[~]` in progress with the team (design, implementation or review)
- `[x]` done: code and tests committed, each with two approvals

## Tests

- Unit tests: `src/test/java`, `*Test.java`. They are owned by the Developer and count toward the 90% JaCoCo gate.
- Integration and acceptance tests: `src/it/java`, `*IT.java` and Cucumber steps, run by Failsafe. They are owned by the Tester. The Developer may not change them.
- Scenarios: `src/features/*.feature`, in Gherkin and in English. They are owned by the Analyst.
- `./mvnw verify` runs everything. `./mvnw verify -Dno-it` leaves out `src/it`.
- `./mvnw verify -Pmutation` also runs PIT mutation testing on `src/main` with the unit tests only (`src/it` does not count) and fails when the mutation score is below 85%. Plain `verify` does not run PIT. See `docs/architecture/mutation-testing.md`.

## Commits

Only the orchestrator commits, through `.claude/scripts/zone.sh`:

- `T<N>: design`
- `T<N>: <title>` (code)
- `T<N>: acceptance tests`
- `T<N>: fix <what>`
- `T<N>: done`
