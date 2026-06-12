#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TOKEN_FILE="${TOKEN_FILE:-/tmp/k6-post-image-access-tokens}"
MANIFEST_FILE="${MANIFEST_FILE:-/tmp/k6-post-image-staging.json}"
IMAGE_FILE="${IMAGE_FILE:-}"
USER_COUNT="${USER_COUNT:-5}"
IMAGE_COUNT="${IMAGE_COUNT:-5}"

if ! command -v curl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
  echo "curl과 jq가 필요합니다."
  exit 1
fi
if [[ -z "$IMAGE_FILE" || ! -f "$IMAGE_FILE" ]]; then
  echo "IMAGE_FILE에 실제 이미지 파일 경로를 지정해야 합니다."
  exit 1
fi
if [[ ! -f "$TOKEN_FILE" ]]; then
  echo "Token file을 찾을 수 없습니다: $TOKEN_FILE"
  exit 1
fi
if (( IMAGE_COUNT < 1 || IMAGE_COUNT > 5 )); then
  echo "IMAGE_COUNT는 1~5여야 합니다."
  exit 1
fi

IFS=',' read -r -a tokens < "$TOKEN_FILE" || true
if (( ${#tokens[@]} < USER_COUNT )); then
  echo "Token 수가 USER_COUNT보다 적습니다: ${#tokens[@]} < $USER_COUNT"
  exit 1
fi

entries_file="$(mktemp)"
trap 'rm -f "$entries_file"' EXIT
umask 077

for (( user_index = 0; user_index < USER_COUNT; user_index++ )); do
  token="${tokens[$user_index]}"
  unique="$(date +%s)-$((user_index + 1))"
  files="$(jq -cn --argjson count "$IMAGE_COUNT" --arg unique "$unique" \
    '[range(0; $count) | {filename:("k6-post-only-" + $unique + "-" + (.|tostring) + ".jpg"),contentType:"image/jpeg"}]')"
  body="$(jq -cn --arg session "k6-post-only-$unique" --argjson files "$files" \
    '{imageType:"POST",uploadSessionId:$session,files:$files}')"

  presign_response="$(curl -fsS -X POST "${BASE_URL}/api/v1/images/presign" \
    -H "Authorization: Bearer ${token}" \
    -H 'Content-Type: application/json' \
    -d "$body")"

  if [[ "$(jq '.data | length' <<< "$presign_response")" != "$IMAGE_COUNT" ]]; then
    echo "VU $((user_index + 1)) Presign 응답 개수가 올바르지 않습니다."
    exit 1
  fi

  for (( image_index = 0; image_index < IMAGE_COUNT; image_index++ )); do
    put_url="$(jq -er ".data[$image_index].putUrl" <<< "$presign_response")"
    header_args=()
    while IFS=$'\t' read -r name value; do
      header_args+=(-H "${name}: ${value}")
    done < <(jq -r ".data[$image_index].headers | to_entries[] | [.key, .value] | @tsv" <<< "$presign_response")
    curl -fsS -X PUT "$put_url" "${header_args[@]}" --upload-file "$IMAGE_FILE" >/dev/null
  done

  keys="$(jq -c '[.data[].key]' <<< "$presign_response")"
  jq -cn --argjson vuIndex "$((user_index + 1))" --argjson keys "$keys" \
    '{vuIndex:$vuIndex,keys:$keys}' >> "$entries_file"
  echo "staging 준비: $((user_index + 1))/$USER_COUNT"
done

jq -s '.' "$entries_file" > "$MANIFEST_FILE"
chmod 600 "$MANIFEST_FILE"
echo "Post-only staging 준비 완료: $MANIFEST_FILE"
