#!/usr/bin/env bash
# Starts and stops the team's role agents in Herdr panes (see .claude/skills/orchestrate/SKILL.md).
# Every task gets its own Herdr tab, labelled with the task id; the role agents live in its panes.
#
# Usage:
#   role.sh start <role> <task> [first-message]   start the role's agent and give it the task
#   role.sh stop <role> <task>                    close the pane the role's agent runs in
#   role.sh stop-all <task>                       close the task's tab
#
# Roles: architect analyst developer tester reviewer-claude reviewer-codex
set -euo pipefail

[ "${HERDR_ENV:-}" = 1 ] || { echo "role.sh: not running inside a Herdr pane" >&2; exit 1; }

root="$(git rev-parse --show-toplevel)"
state="$root/.git/herdr"
mkdir -p "$state"

sonnet="claude-sonnet-5-5"
sol="gpt-6.1-sol"

# The Codex workspace-write sandbox blocks the Herdr unix socket, so a Codex role could not run
# `herdr agent prompt`. This permissions profile is the :workspace profile (same writable roots,
# still no internet) plus the one socket. `-s workspace-write` cannot be combined with it.
codex_perms=(
  -c 'default_permissions="herdr"'
  -c 'permissions.herdr.extends=":workspace"'
  -c 'permissions.herdr.network.enabled=true'
  -c "permissions.herdr.network.unix_sockets={\"${HERDR_SOCKET_PATH:-}\"=\"allow\"}"
)

# Prints the kind, then the native agent arguments, one per line.
agent_args() {
  local role="$1" task="$2"
  case "$role" in
    architect | developer)
      printf '%s\n' claude --model "$sonnet" --settings "$root/.claude/roles/$role.settings.json" ;;
    reviewer-claude)
      printf '%s\n' claude --model "$sonnet" --settings "$root/.claude/roles/reviewer.settings.json" ;;
    analyst)
      # Live web search comes from the profile ~/.codex/analyst.config.toml (web_search = "live").
      # A profile, like --search, works only in embedded mode: with the shared background server
      # running, Codex falls back to it and stops at a startup warning. --no-daemon asks for
      # embedded mode up front.
      printf '%s\n' codex --no-daemon -p analyst -m "$sol" -a on-request "${codex_perms[@]}" \
        -C "$root/tasks" --add-dir "$root/src/features" --add-dir "$root/docs" ;;
    tester)
      printf '%s\n' codex -m "$sol" -a on-request "${codex_perms[@]}" \
        -C "$root/src/it" --add-dir "$root/target" --add-dir "$root/tasks/$task/comms" \
        --add-dir "$HOME/.m2" ;;
    reviewer-codex)
      printf '%s\n' codex -m "$sol" -a on-request "${codex_perms[@]}" \
        -C "$root/tasks/$task/review" --add-dir "$root/tasks/$task/comms" ;;
    *) echo "role.sh: unknown role $role" >&2; exit 2 ;;
  esac
}

role_file() {
  case "$1" in
    reviewer-*) echo ".claude/roles/reviewer.md" ;;
    *) echo ".claude/roles/$1.md" ;;
  esac
}

# Prints a free shell pane in the task's tab: the tab's root pane the first time, then a new split
# of the pane created last, alternating right and down.
free_pane() {
  local task="$1" tab_file="$state/$task.tab" last_file="$state/$task.last-pane"
  local count_file="$state/$task.splits"
  if [ ! -s "$tab_file" ]; then
    local created
    created="$(herdr tab create --workspace "$HERDR_WORKSPACE_ID" --cwd "$root" --label "$task" --no-focus)"
    jq -r '.result.tab.tab_id' <<<"$created" >"$tab_file"
    jq -r '.result.root_pane.pane_id' <<<"$created" | tee "$last_file"
    echo 0 >"$count_file"
    return
  fi
  local splits direction pane
  splits="$(cat "$count_file")"
  if [ $((splits % 2)) -eq 0 ]; then direction=right; else direction=down; fi
  pane="$(herdr pane split --pane "$(cat "$last_file")" --direction "$direction" --cwd "$root" --no-focus 2>/dev/null \
    | jq -r '.result.pane.pane_id // empty' 2>/dev/null || true)"
  if [ -z "$pane" ]; then
    # The saved pane or tab is gone (the last pane of the tab was closed): start a new tab.
    rm -f "$tab_file" "$last_file" "$count_file"
    free_pane "$task"
    return
  fi
  echo $((splits + 1)) >"$count_file"
  echo "$pane" | tee "$last_file"
}

start() {
  local role="$1" task="$2" message="${3:-}"
  mkdir -p "$root/tasks/$task/comms/status" "$root/tasks/$task/review"
  local args=() line
  while IFS= read -r line; do args+=("$line"); done < <(agent_args "$role" "$task")
  local kind="${args[0]}"
  local pane
  pane="$(free_pane "$task")"
  # Recorded before the start, so `role.sh stop` can close the pane when the agent fails to start.
  echo "$pane" >"$state/$task.$role.pane"
  # A pane that was just split may not be at its shell prompt yet (agent_pane_busy): try again a few
  # times, and if it still fails close the pane and forget it, so no dead pane stays in the tab.
  local attempt started=0
  for attempt in 1 2 3; do
    if herdr agent start "$role" --kind "$kind" --pane "$pane" --timeout 60000 -- "${args[@]:1}" >/dev/null; then
      started=1
      break
    fi
    [ "$attempt" -lt 3 ] && sleep 2
  done
  if [ "$started" -ne 1 ]; then
    echo "role.sh: $role did not start in pane $pane after 3 attempts" >&2
    herdr pane close "$pane" >/dev/null 2>&1 || true
    rm -f "$state/$task.$role.pane"
    if [ "$(cat "$state/$task.last-pane" 2>/dev/null)" = "$pane" ]; then
      local other
      other="$(ls "$state"/"$task".*.pane 2>/dev/null | head -1 || true)"
      if [ -n "$other" ]; then cp "$other" "$state/$task.last-pane"; else rm -f "$state/$task".*; fi
    fi
    exit 1
  fi
  herdr agent prompt "$role" "You are the $role of task $task. Read $(role_file "$role") and follow it. \
The task is $task in tasks/todo.md, its brief is tasks/$task/brief.md, and its working files are in \
tasks/$task/. ${message}" >/dev/null
  echo "$role started in pane $pane"
}

stop() {
  local role="$1" task="$2"
  local pane_file="$state/$task.$role.pane"
  [ -s "$pane_file" ] || { echo "role.sh: $role is not running for $task" >&2; exit 1; }
  local pane
  pane="$(cat "$pane_file")"
  herdr pane close "$pane" >/dev/null
  rm -f "$pane_file"
  # The next split must not start from the closed pane.
  if [ "$(cat "$state/$task.last-pane" 2>/dev/null)" = "$pane" ]; then
    local other
    other="$(ls "$state"/"$task".*.pane 2>/dev/null | head -1 || true)"
    if [ -n "$other" ]; then cp "$other" "$state/$task.last-pane"; else rm -f "$state/$task".*; fi
  fi
  echo "$role stopped"
}

stop_all() {
  local task="$1"
  [ -s "$state/$task.tab" ] && herdr tab close "$(cat "$state/$task.tab")" >/dev/null
  rm -f "$state/$task".*
  echo "$task tab closed"
}

case "${1:-}" in
  start) [ $# -ge 3 ] || { echo "usage: role.sh start <role> <task> [first-message]" >&2; exit 2; }
    start "$2" "$3" "${4:-}" ;;
  stop) [ $# -eq 3 ] || { echo "usage: role.sh stop <role> <task>" >&2; exit 2; }
    stop "$2" "$3" ;;
  stop-all) [ $# -eq 2 ] || { echo "usage: role.sh stop-all <task>" >&2; exit 2; }
    stop_all "$2" ;;
  *) sed -n '2,10p' "$0"; exit 2 ;;
esac
