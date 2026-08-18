#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <task-id>" >&2
  exit 1
fi

echo "=== task ==="
equalix_curl "${EQUALIX_URL}/api/v1/tasks/${1}"
echo
echo "=== system ==="
equalix_curl "${EQUALIX_URL}/api/v1/status"
echo
