#!/usr/bin/env bash
# Runs the bot locally with the dev profile, reading settings from .env.
# Extra arguments are passed to Maven, e.g. ./run.sh -Dspring-boot.run.arguments=--debug
set -euo pipefail

cd "$(dirname "$0")"

if [[ ! -f .env ]]; then
  echo "Missing .env. Copy .env.example to .env and fill in the values." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

if [[ -z "${TELEGRAM_BOT_TOKEN:-}" ]]; then
  echo "TELEGRAM_BOT_TOKEN is not set in .env" >&2
  exit 1
fi
if [[ ! "${ADMIN_USER_ID:-}" =~ ^[0-9]+$ ]]; then
  echo "ADMIN_USER_ID in .env must be a number (send /whoami to the bot to find yours)" >&2
  exit 1
fi

exec ./mvnw -B spring-boot:run -Dspring-boot.run.profiles=dev "$@"
