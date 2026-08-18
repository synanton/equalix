#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

FAIRNESS_KEY="${1:-seq-demo}"
PAYLOAD="$(printf 'step' | base64 | tr -d '\n')"

create_seq() {
  local seq="$1"
  local extra="$2"
  equalix_curl -X POST "${EQUALIX_URL}/api/v1/tasks" \
    -H "Content-Type: application/json" \
    -d "{\"fairnessKey\":\"${FAIRNESS_KEY}\",\"weight\":1.0,\"payload\":\"${PAYLOAD}\",\"sequential\":true,\"sequenceNumber\":${seq}${extra}}"
}

ID1="$(create_seq 1 "" | tr -d '"')"
echo "seq 1 ${ID1}"
ID2="$(create_seq 2 ",\"dependsOnTaskId\":\"${ID1}\",\"requiresPreviousResult\":true" | tr -d '"')"
echo "seq 2 ${ID2}"
ID3="$(create_seq 3 ",\"dependsOnTaskId\":\"${ID2}\",\"requiresPreviousResult\":true" | tr -d '"')"
echo "seq 3 ${ID3}"

echo "listing ${FAIRNESS_KEY}:"
equalix_curl "${EQUALIX_URL}/api/v1/tasks?fairnessKey=${FAIRNESS_KEY}"
echo
