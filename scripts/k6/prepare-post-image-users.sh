#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
DB_NAME="${DB_NAME:-testdb}"
DB_USER="${DB_USER:-iyongjun}"
BASE_EMAIL="${BASE_EMAIL:-k6-test@example.com}"
TOKEN_FILE="${TOKEN_FILE:-/tmp/k6-post-image-access-tokens}"
USER_COUNT="${USER_COUNT:-5}"

if ! command -v psql >/dev/null 2>&1; then
  echo "psql 명령을 찾을 수 없습니다."
  exit 1
fi
if ! command -v curl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
  echo "curl과 jq가 필요합니다."
  exit 1
fi

read -r -s -p "${BASE_EMAIL} 계정 비밀번호: " TEST_PASSWORD
echo

base_count="$(psql -h localhost -U "$DB_USER" -d "$DB_NAME" -Atc \
  "select count(*) from users where email = '$BASE_EMAIL' and password is not null")"
if [[ "$base_count" != "1" ]]; then
  echo "기준 계정 ${BASE_EMAIL}을 찾을 수 없거나 password가 없습니다."
  exit 1
fi

for index in $(seq 1 "$USER_COUNT"); do
  email="k6-load-${index}@example.com"
  social_id="k6-load-${index}"

  psql -h localhost -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 \
    -v base_email="$BASE_EMAIL" -v email="$email" -v social_id="$social_id" <<'SQL' >/dev/null
INSERT INTO users (
    agreed_to_push_notification,
    agreed_to_terms,
    is_new_user,
    reply_rate,
    activity_point,
    created_at,
    updated_at,
    visit_count,
    user_role,
    visit_purpose,
    introduction,
    birth_date,
    email,
    first_name,
    hobby,
    languages,
    last_name,
    nationality,
    password,
    provider,
    sex,
    social_id,
    translate_language
)
SELECT
    false,
    true,
    false,
    0,
    0,
    now(),
    now(),
    0,
    'USER',
    'testing',
    'k6 load test user',
    '01/01/2000',
    :'email',
    'K6',
    'testing',
    'KO',
    'LoadTester',
    'KR',
    password,
    'local',
    'NoGender',
    :'social_id',
    'ko'
FROM users
WHERE email = :'base_email'
ON CONFLICT (email) DO UPDATE SET
    user_role = 'USER',
    is_new_user = false,
    updated_at = now();

DELETE FROM image
WHERE image_type = 'USER'
  AND related_id = (SELECT user_id FROM users WHERE email = :'email');

INSERT INTO image (related_id, image_type, url, order_index, moderation_status)
SELECT user_id, 'USER', 'default/character_02.svg', 0, 'CLEAN'
FROM users
WHERE email = :'email';
SQL
done

umask 077
: > "$TOKEN_FILE"
for index in $(seq 1 "$USER_COUNT"); do
  email="k6-load-${index}@example.com"
  token="$(curl -fsS -X POST "${BASE_URL}/api/v1/member/doLogin" \
    -H 'Content-Type: application/json' \
    -d "$(jq -n --arg email "$email" --arg password "$TEST_PASSWORD" \
      '{email:$email,password:$password}')" \
    | jq -er '.accessToken')"
  if [[ "$index" -gt 1 ]]; then
    printf ',' >> "$TOKEN_FILE"
  fi
  printf '%s' "$token" >> "$TOKEN_FILE"
done

chmod 600 "$TOKEN_FILE"
echo "k6 테스트 사용자 ${USER_COUNT}명과 Access Token 준비 완료"
echo "Token file: ${TOKEN_FILE}"
