#!/usr/bin/env bash
# Review snapshots and checked commits for one zone of the working tree. The Developer and the
# Tester work in main at the same time, so a zone is the set of paths one role owns.
#
# Usage:
#   zone.sh paths  <zone> <task>                     print the zone's pathspecs
#   zone.sh patch  <zone> <task> <round>             write tasks/<task>/review/<zone>-r<round>.patch
#   zone.sh commit <zone> <task> <round> <message>   commit the zone if it still equals that patch
#
# Zones: dev (production code, unit tests, build, deploy), test (src/it), design (task artifacts,
# the task list, features, architecture docs; committed without a review patch), tools (the team's
# own setup: `.claude/` and SPEC.md; committed without a review patch, by the owner's decision).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

zone_paths() {
  case "$1" in
    dev) printf '%s\n' src/main src/test pom.xml config deploy docs/host-setup.md Dockerfile compose.yaml .dockerignore .env.example ;;
    test) printf '%s\n' src/it ;;
    design) printf '%s\n' "tasks/$2" tasks/todo.md src/features docs/decisions.md docs/architecture ;;
    tools) printf '%s\n' .claude SPEC.md ;;
    *) echo "zone.sh: unknown zone $1" >&2; exit 2 ;;
  esac
}

load_paths() {
  paths=()
  local line
  # Only paths that exist now or in HEAD: git add fails on a pathspec that matches nothing.
  while IFS= read -r line; do
    if [ -e "$line" ] || git cat-file -e "HEAD:$line" 2>/dev/null; then paths+=("$line"); fi
  done < <(zone_paths "$1" "$2")
  [ ${#paths[@]} -gt 0 ] || { echo "zone.sh: zone $1 has no paths yet" >&2; exit 1; }
}

# Makes new files in the zone visible to git diff without staging their content.
mark_new_files() {
  git ls-files -z --others --exclude-standard -- "${paths[@]}" | xargs -0 git add -N --
}

patch() {
  local zone="$1" task="$2" round="$3"
  load_paths "$zone" "$task"
  local out="tasks/$task/review/$zone-r$round.patch"
  mkdir -p "tasks/$task/review"
  mark_new_files
  git diff HEAD --binary -- "${paths[@]}" >"$out"
  if [ ! -s "$out" ]; then
    rm -f "$out"
    echo "zone.sh: no changes in zone $zone" >&2
    exit 1
  fi
  git diff HEAD --stat -- "${paths[@]}"
  echo "$out"
}

# The Co-Authored-By lines for a zone: the models of the roles that write it (see role.sh for the
# model ids). The orchestrator and the Claude roles run Sonnet 5.5, the Codex roles run GPT-6.1-Sol.
claude_author="Claude Sonnet 5.5 <noreply@anthropic.com>"
codex_author="GPT-6.1-Sol (Codex) <noreply@openai.com>"

coauthors() {
  case "$1" in
    dev | tools) printf 'Co-Authored-By: %s' "$claude_author" ;;
    test) printf 'Co-Authored-By: %s' "$codex_author" ;;
    design) printf 'Co-Authored-By: %s\nCo-Authored-By: %s' "$claude_author" "$codex_author" ;;
    *) echo "zone.sh: unknown zone $1" >&2; exit 2 ;;
  esac
}

commit() {
  local zone="$1" task="$2" round="$3" message="$4"
  load_paths "$zone" "$task"
  local approved="tasks/$task/review/$zone-r$round.patch"
  local staged_other
  staged_other="$(git diff --cached --name-only -- . $(printf ":(exclude)%s " "${paths[@]}"))"
  if [ -n "$staged_other" ]; then
    echo "zone.sh: files outside zone $zone are staged:" >&2
    echo "$staged_other" >&2
    exit 1
  fi
  if [ "$zone" != design ] && [ "$zone" != tools ]; then
    [ -s "$approved" ] || { echo "zone.sh: no approved patch $approved" >&2; exit 1; }
    mark_new_files
    if ! git diff HEAD --binary -- "${paths[@]}" | cmp -s - "$approved"; then
      echo "zone.sh: zone $zone changed after $approved was reviewed" >&2
      exit 1
    fi
  fi
  git add -A -- "${paths[@]}"
  git commit -q -m "$message" -m "$(coauthors "$zone")"
  git log --oneline -1
}

case "${1:-}" in
  paths) [ $# -eq 3 ] || { echo "usage: zone.sh paths <zone> <task>" >&2; exit 2; }
    zone_paths "$2" "$3" ;;
  patch) [ $# -eq 4 ] || { echo "usage: zone.sh patch <zone> <task> <round>" >&2; exit 2; }
    patch "$2" "$3" "$4" ;;
  commit) [ $# -eq 5 ] || { echo "usage: zone.sh commit <zone> <task> <round> <message>" >&2; exit 2; }
    commit "$2" "$3" "$4" "$5" ;;
  *) sed -n '2,11p' "$0"; exit 2 ;;
esac
