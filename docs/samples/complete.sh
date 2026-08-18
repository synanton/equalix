#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <task-id> [result-text]" >&2
  exit 1
fi

TASK_ID="$1"
RESULT_B64="$(printf '%s' "${2:-done}" | base64 | tr -d '\n')"

equalix_curl -X POST "${EQUALIX_URL}/api/v1/tasks/${TASK_ID}/complete" \
  -H "Content-Type: application/json" \
  -d "{\"success\":true,\"result\":\"${RESULT_B64}\"}"
echo "completed ${TASK_ID}"
