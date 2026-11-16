#!/usr/bin/env bash
# Independent review of one task commit via Codex CLI.
# Usage: scripts/codex-review.sh <task-id> [commit-sha]   (default HEAD)
set -euo pipefail
cd "$(dirname "$0")/.."
task="$1"
sha="${2:-HEAD}"
out="$(mktemp -t codex-review-XXXX.md)"

codex exec -s read-only --ephemeral -o "$out" \
  "You are an independent code reviewer. Review task $task of this project, commit $sha (read it with git show $sha and read the touched files).
Acceptance criteria are in tasks/todo.md, the spec in SPEC.md, the plan in tasks/plan.md.
Check correctness, security, architecture and that every Acceptance bullet is met.
Do not edit files. Finish with 'VERDICT: APPROVED' (no Critical/Important findings and all Acceptance bullets met)
or 'VERDICT: CHANGES_REQUESTED'. List findings as [Critical|Important|Suggestion] file:line - problem." >/dev/null

cat "$out"
