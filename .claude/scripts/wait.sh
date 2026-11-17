#!/usr/bin/env bash
# Waits for role agents without trusting their status files, which an agent sometimes forgets to
# write (see .claude/skills/orchestrate/SKILL.md). A role counts as finished from what it actually
# did: its report exists and its pane is no longer working.
#
# Usage:
#   wait.sh review <task> <zone> <round> [seconds]   both reviewers of a review round
#   wait.sh roles  <task> <role>... [-t seconds]     roles that work without a report file
#
# Exit codes: 0 all finished, 2 someone is blocked on a dialog (read it and ask the owner),
# 3 timed out (run it again if the roles are still working). It prints no verdicts.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

poll=5
default_timeout=540   # below the 10-minute limit of one tool call

# working | idle | done | blocked | gone (no such agent, for example a closed pane)
state() {
  herdr agent get "$1" 2>/dev/null | jq -r '.result.agent.agent_status // "gone"' 2>/dev/null || echo gone
}

review() {
  local task="$1" zone="$2" round="$3" timeout="${4:-$default_timeout}"
  local deadline=$((SECONDS + timeout))
  while true; do
    local pending=() blocked=() reviewer report st
    for reviewer in reviewer-claude reviewer-codex; do
      report="tasks/$task/review/$zone-r$round-$reviewer.md"
      st="$(state "$reviewer")"
      if [ "$st" = blocked ]; then blocked+=("$reviewer"); continue; fi
      # The verdict line is the first line of a finished report.
      if [ -s "$report" ] && head -1 "$report" | grep -q '^VERDICT:' \
        && { [ "$st" = idle ] || [ "$st" = done ] || [ "$st" = gone ]; }; then
        continue
      fi
      pending+=("$reviewer")
    done
    if [ ${#blocked[@]} -gt 0 ]; then echo "BLOCKED: ${blocked[*]}"; exit 2; fi
    if [ ${#pending[@]} -eq 0 ]; then echo "both reviewers finished ($zone round $round)"; exit 0; fi
    if [ "$SECONDS" -ge "$deadline" ]; then echo "TIMEOUT, still working: ${pending[*]}"; exit 3; fi
    sleep "$poll"
  done
}

roles() {
  local task="$1"; shift
  local timeout="$default_timeout" names=()
  while [ $# -gt 0 ]; do
    case "$1" in
      -t) timeout="$2"; shift 2 ;;
      *) names+=("$1"); shift ;;
    esac
  done
  local deadline=$((SECONDS + timeout)) grace=$((SECONDS + 15))
  local quiet=()   # consecutive polls in which each role was not working
  local i
  for i in "${!names[@]}"; do quiet[$i]=0; done
  while true; do
    local pending=() blocked=() st
    for i in "${!names[@]}"; do
      st="$(state "${names[$i]}")"
      if [ "$st" = blocked ]; then blocked+=("${names[$i]}"); quiet[$i]=0; continue; fi
      if [ "$st" = working ] || [ "$SECONDS" -lt "$grace" ]; then quiet[$i]=0; else quiet[$i]=$((quiet[$i] + 1)); fi
      # Two quiet polls in a row, so a role between two steps is not taken for finished.
      [ "${quiet[$i]}" -ge 2 ] || pending+=("${names[$i]}")
    done
    if [ ${#blocked[@]} -gt 0 ]; then echo "BLOCKED: ${blocked[*]}"; exit 2; fi
    if [ ${#pending[@]} -eq 0 ]; then
      echo "finished: ${names[*]}"
      for i in "${!names[@]}"; do
        echo "  ${names[$i]}: $(cat "tasks/$task/comms/status/${names[$i]}" 2>/dev/null || echo 'no status file')"
      done
      exit 0
    fi
    if [ "$SECONDS" -ge "$deadline" ]; then echo "TIMEOUT, still working: ${pending[*]}"; exit 3; fi
    sleep "$poll"
  done
}

case "${1:-}" in
  review) [ $# -ge 4 ] || { echo "usage: wait.sh review <task> <zone> <round> [seconds]" >&2; exit 2; }
    review "$2" "$3" "$4" "${5:-}" ;;
  roles) [ $# -ge 3 ] || { echo "usage: wait.sh roles <task> <role>... [-t seconds]" >&2; exit 2; }
    shift; roles "$@" ;;
  *) sed -n '2,13p' "$0"; exit 2 ;;
esac
