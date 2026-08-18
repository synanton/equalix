#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

FAIRNESS_KEY="${1:-tenant-a}"
MESSAGE="${2:-hello}"
WEIGHT="${3:-1.0}"
PAYLOAD="$(printf '%s' "${MESSAGE}" | base64 | tr -d '\n')"

RESPONSE="$(equalix_curl -X POST "${EQUALIX_URL}/api/v1/tasks" \
  -H "Content-Type: application/json" \
  -d "{\"fairnessKey\":\"${FAIRNESS_KEY}\",\"weight\":${WEIGHT},\"payload\":\"${PAYLOAD}\",\"sequential\":false}")"

TASK_ID="${RESPONSE//\"/}"
echo "created ${TASK_ID}"
equalix_curl "${EQUALIX_URL}/api/v1/tasks/${TASK_ID}"
echo
