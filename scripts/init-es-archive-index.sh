#!/usr/bin/env bash
set -euo pipefail

ES_URL="${ES_URL:-http://localhost:9200}"
INDEX_NAME="${INDEX_NAME:-chat_message_archive_v1}"
ALIAS_NAME="${ALIAS_NAME:-chat_message_archive}"
MAPPING_FILE="${MAPPING_FILE:-starshield-backend/src/main/resources/es/chat_message_archive_v1_mapping_standard.json}"

if [[ ! -f "$MAPPING_FILE" ]]; then
  echo "Mapping file not found: $MAPPING_FILE" >&2
  exit 1
fi

if ! curl -fsS "$ES_URL" >/dev/null; then
  echo "Elasticsearch is not reachable: $ES_URL" >&2
  echo "Start Elasticsearch first, then retry this script." >&2
  exit 1
fi

alias_index_status="$(curl -sS -o /dev/null -w "%{http_code}" "$ES_URL/$ALIAS_NAME")"
if [[ "$alias_index_status" == "200" && "$ALIAS_NAME" != "$INDEX_NAME" ]]; then
  echo "Index already exists with archive name: $ALIAS_NAME"
  echo "Skip alias binding because Elasticsearch cannot create an alias with the same name as an existing index."
  echo "Elasticsearch archive index is ready: $ALIAS_NAME (physical index)"
  exit 0
fi

status_code="$(curl -sS -o /dev/null -w "%{http_code}" "$ES_URL/$INDEX_NAME")"

if [[ "$status_code" == "200" ]]; then
  echo "Index already exists: $INDEX_NAME"
elif [[ "$status_code" == "404" ]]; then
  echo "Creating index: $INDEX_NAME"
  response_file="$(mktemp)"
  create_code="$(curl -sS -o "$response_file" -w "%{http_code}" -X PUT "$ES_URL/$INDEX_NAME" \
    -H "Content-Type: application/json" \
    --data-binary "@$MAPPING_FILE")"
  if [[ "$create_code" != "200" ]]; then
    echo "Failed to create index: $INDEX_NAME (HTTP $create_code)" >&2
    cat "$response_file" >&2
    echo >&2
    rm -f "$response_file"
    exit 1
  fi
  rm -f "$response_file"
else
  echo "Unexpected status when checking index $INDEX_NAME: HTTP $status_code" >&2
  exit 1
fi

echo "Binding alias: $ALIAS_NAME -> $INDEX_NAME"
response_file="$(mktemp)"
alias_status="$(curl -sS -o /dev/null -w "%{http_code}" "$ES_URL/_alias/$ALIAS_NAME")"
if [[ "$alias_status" == "200" ]]; then
  alias_payload="{
    \"actions\": [
      { \"remove\": { \"index\": \"*\", \"alias\": \"$ALIAS_NAME\" } },
      { \"add\": { \"index\": \"$INDEX_NAME\", \"alias\": \"$ALIAS_NAME\" } }
    ]
  }"
else
  alias_payload="{
    \"actions\": [
      { \"add\": { \"index\": \"$INDEX_NAME\", \"alias\": \"$ALIAS_NAME\" } }
    ]
  }"
fi
alias_code="$(curl -sS -o "$response_file" -w "%{http_code}" -X POST "$ES_URL/_aliases" \
  -H "Content-Type: application/json" \
  -d "$alias_payload")"
if [[ "$alias_code" != "200" ]]; then
  echo "Failed to bind alias: $ALIAS_NAME -> $INDEX_NAME (HTTP $alias_code)" >&2
  cat "$response_file" >&2
  echo >&2
  rm -f "$response_file"
  exit 1
fi
rm -f "$response_file"

echo "Elasticsearch archive index is ready: $ALIAS_NAME -> $INDEX_NAME"
