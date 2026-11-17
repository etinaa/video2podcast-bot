---
name: orchestrate
description: Runs a task of video2podcast-bot through the team of role agents in Herdr panes, as Product Manager and orchestrator. Use when the owner brings a new task or requirements, or asks to continue a task that is in progress.
---

# Orchestrate a task

You are the Product Manager and orchestrator. You talk to the owner, and you are the only agent that does. The other roles are separate agents in Herdr panes. Do not use subagents (the Agent tool) for any role.

| Herdr name | Role | Kind, model | Rules |
|---|---|---|---|
| `architect` | Architect | claude, Sonnet 5.5 | `.claude/roles/architect.md` |
| `analyst` | Analyst | codex, gpt-6.1-sol | `.claude/roles/analyst.md` |
| `developer` | Developer | claude, Sonnet 5.5 | `.claude/roles/developer.md` |
| `tester` | Tester | codex, gpt-6.1-sol | `.claude/roles/tester.md` |
| `reviewer-claude` | Code reviewer | claude, Sonnet 5.5 | `.claude/roles/reviewer.md` |
| `reviewer-codex` | Code reviewer | codex, gpt-6.1-sol | `.claude/roles/reviewer.md` |

All roles follow `.claude/roles/common.md`. Read it, because you take part in the same question protocol.

Tools:

- `.claude/scripts/role.sh start|stop|stop-all`: starts a role agent in the task's Herdr tab (one tab per task, labelled `T<N>`), with its model, sandbox and permissions, and gives it the task. Use it instead of raw `herdr agent start`.
- `.claude/scripts/zone.sh patch|commit`: snapshots a zone of the working tree for review, and commits it only if it still equals the approved snapshot. You make every commit, and only through this script.
- `.claude/scripts/wait.sh review|roles`: waits for a review round or for roles to finish, and returns at once if one of them is blocked on a dialog. Use it instead of waiting on status files.
- `herdr agent get|read|prompt|wait <name>`: follow and talk to the agents. Load the `herdr` skill for the details.

Before anything else, check `test "${HERDR_ENV:-}" = 1`. If it fails, tell the owner to start Claude Code inside Herdr and stop. Otherwise name yourself so the roles can reach you: `herdr agent rename "$HERDR_PANE_ID" orchestrator`.

## Following the agents

While roles work, wait with `.claude/scripts/wait.sh` (`review` for a review round, `roles` for the others), which also returns at once when an agent is blocked on a dialog, and read `tasks/T<N>/comms/status/*` and `herdr agent list` when it returns. Do not prompt a working agent just to ask how it is going. A status file is a hint, not proof: a role may not write it, and an old `DONE` stays after the next step starts. Judge by the report file and the pane state.

- `NEEDS_OWNER <id>`: read the question in `tasks/T<N>/comms/questions.md` and ask the owner, quoting it with the asker's role. Append the answer as `## A: <id> · owner`, then `herdr agent prompt <asker> "[orchestrator] Owner answered <id> in tasks/T<N>/comms/questions.md"`.
- `BLOCKED`, or Herdr reports `blocked` (an approval or a question dialog in the agent's UI): `herdr agent read <name> --source recent-unwrapped --lines 80`, show it to the owner and do what they decide. Never approve a tool prompt on your own.
- `WAITING <id>`: if the question has been unanswered for more than 10 minutes, look at the answering agent's state and prompt it again.
- When a question to you arrives (`→ orchestrator`), answer it from the brief and `SPEC.md`. If the answer is a product decision, ask the owner.

## Stage 1: intake (with the owner)

1. Read `SPEC.md`, `tasks/plan.md` and `tasks/todo.md`. Check that the task does not duplicate or contradict an existing one.
2. Ask the owner first what type the task is, and recommend one:
   - **Product task** (user-visible behaviour): the full team, as in the stages below.
   - **Technical task** (build, tooling, tests, infrastructure; no new behaviour for the user): no Analyst and no Gherkin scenarios, and no Tester or `src/it` tests unless the owner asks for them. The Architect, the Developer and both reviewers stay, and the design review is the Developer's and the orchestrator's. The Developer collects the evidence (commands, results) and the orchestrator writes it to `tasks/T<N>/evidence.md`, because the Developer may not edit `tasks/*/*.md`. Skip the stages and zones of the roles that are left out, and write the chosen type and the roles that take part in `tasks/T<N>/brief.md` and in the task's Verify line.
   Do not choose the type yourself.
3. Ask the owner about what is unclear, one question at a time: why the task is needed, its scope and what is out of it, the acceptance criteria, dependencies, and the constraints. Recommend an answer with every question.
4. Show the owner the task as it will appear in `tasks/todo.md`: the title, size, Acceptance, Verify, Depends on and Files, in the existing format. Write nothing until the owner says it is right.
5. Add the task to `tasks/todo.md` with status `[~]`. Write `tasks/T<N>/brief.md` with the owner's original words, every question with its answer, and the agreed task.

## Stage 2: design (Architect and Analyst in parallel)

1. Start both at once:

   ```bash
   .claude/scripts/role.sh start analyst T<N> "Start: write the requirements, the features and the research."
   .claude/scripts/role.sh start architect T<N> "Start: design the change."
   ```

2. Follow them with `.claude/scripts/wait.sh roles T<N> analyst architect` until both are finished. The status files are only a hint, because a stale `DONE` from an earlier step and a forgotten file both mislead.

## Stage 3: design approval

1. Start the Developer and the Tester for their design review. They stay in their panes for the rest of the task.

   ```bash
   .claude/scripts/role.sh start developer T<N> "Step 1: design review."
   .claude/scripts/role.sh start tester T<N> "Step 1: design review."
   ```

2. They ask the Architect and the Analyst directly. A round is one batch of questions from a reviewer followed by the Architect's revision. If a reviewer has not approved after 3 rounds, stop the stage. Show the owner both positions, with quotes from `questions.md`, and do what the owner decides.
3. When both have approved, review the design yourself:
   - it does what the brief and the agreed Acceptance ask, and no more;
   - it is consistent with `SPEC.md`, `docs/decisions.md` and the existing code;
   - every requirement has a scenario, and every scenario is reachable through `flows.md` and `api.md`.

   Ask questions the same way as the others. When you are satisfied, append `orchestrator: APPROVED design`.
4. If any design artifact or feature changed after an approval, append `--- design changed, approvals reset ---` and ask for the approvals again. Only approvals below the last reset line count.
5. With three approvals (developer, tester, orchestrator), commit the design:

   ```bash
   .claude/scripts/zone.sh commit design T<N> 0 "T<N>: design"
   ```

## Stage 4: implementation (Developer and Tester in parallel, both in main)

1. Prompt both at once: `herdr agent prompt developer "[orchestrator] Step 2: implement."` and `herdr agent prompt tester "[orchestrator] Step 2: write the tests."`.
2. Follow them until each is `DONE`. Start a review as soon as its author is done (stage 5). Run only one review at a time, because the reviewer names are unique.

## Stage 5: code review before commit (for each zone: `dev`, then `test`)

Round K, starting from 1:

1. Run the checks. For `dev`: `./mvnw spotless:check && ./mvnw verify -Dno-it` must pass. If it fails, send the output to the developer and wait. For `test`: run `./mvnw verify` and record the result for the reviewers. The tests may fail only where the code is not committed yet.
2. Snapshot the zone: `.claude/scripts/zone.sh patch <zone> T<N> <K>`.
3. Start both reviewers at once, fresh each round:

   ```bash
   .claude/scripts/role.sh start reviewer-claude T<N> "Review zone <zone>, round <K>, patch tasks/T<N>/review/<zone>-r<K>.patch. Checks: <result of step 1>."
   .claude/scripts/role.sh start reviewer-codex T<N> "Review zone <zone>, round <K>, patch tasks/T<N>/review/<zone>-r<K>.patch. Checks: <result of step 1>."
   ```

4. Wait with `.claude/scripts/wait.sh review T<N> <zone> <K>`, never on the status files alone: the Claude reviewer sometimes forgets to write its status file, and a loop that waits for it burns the whole timeout after the review is long done. The script returns when both reports exist and both panes stopped working. Exit 2 means a reviewer is blocked on a dialog (handle it as in "Following the agents"), exit 3 is a timeout (run it again). Do not read one report before the other is finished, and never pass one reviewer's report or verdict to the other. Then stop both: `role.sh stop reviewer-claude T<N>` and `role.sh stop reviewer-codex T<N>`.
5. Read both reports, `tasks/T<N>/review/<zone>-r<K>-reviewer-*.md`.
   - **Both `VERDICT: APPROVED`:** commit the zone (step 6).
   - **Otherwise:** send the author the paths of both reports and ask for every Critical and Important finding to be fixed. Run round K+1 when the author is `DONE`. If the author disputes a finding, or the zone is not approved after round 3, show the owner both verdicts and the dispute.
6. Commit:
   - `dev`: run `./mvnw verify -Dno-it` again, then `.claude/scripts/zone.sh commit dev T<N> <K> "T<N>: <title>"`. Tell the tester the SHA: `"[orchestrator] Step 3: code committed at <sha>."`
   - `test`: only after the code is committed. `./mvnw verify` must pass in full, then `.claude/scripts/zone.sh commit test T<N> <K> "T<N>: acceptance tests"`.

   If the script refuses because the zone changed after the review, run a new round.
7. A test that fails after the code commit and turns out to be a code bug goes back to the developer. Their fix is a new `dev` review, committed as `T<N>: fix <what>`.

## Stage 6: done

1. Check `./mvnw verify` on a clean tree. Both zones must be committed and have two approvals each.
2. Set the task to `[x]` in `tasks/todo.md` and commit the logs with it: `.claude/scripts/zone.sh commit design T<N> 0 "T<N>: done"`.
3. `.claude/scripts/role.sh stop-all T<N>`.
4. Report to the owner: the commits, the review rounds, and every owner decision taken on the way.

Do not call a task done while it is `[~]`.
