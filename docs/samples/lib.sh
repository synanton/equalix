#!/usr/bin/env bash
# Shared settings for Equalix sample scripts.

EQUALIX_URL="${EQUALIX_URL:-http://localhost:8080}"
EQUALIX_API_KEY="${EQUALIX_API_KEY:-changeme}"

equalix_curl() {
  curl -sS -H "X-API-Key: ${EQUALIX_API_KEY}" "$@"
}
