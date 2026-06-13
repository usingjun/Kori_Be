# Post 이미지 k6 부하 테스트 가이드

## 목적

실제 Post 이미지 생성 흐름에서 다음 구간을 측정한다.

```text
Presigned URL 발급
→ 실제 NCP Object Storage 이미지 업로드
→ Post 생성 API 요청
→ 비동기 이미지 Copy/DB 저장 시작
```

Post 이미지 저장은 `@Async`이므로 k6의 Post 생성 응답시간에는 비동기 Copy 완료시간이 포함되지 않는다. Copy 완료와 Outbox 처리 지연은 DB 상태를 별도로 확인해야 한다.

## 사전 준비

- 애플리케이션을 로컬 또는 전용 테스트 환경에서 실행한다.
- 실제 사진 파일 하나를 준비한다. 권장 크기는 7~9MB다.
- 쓰기 가능한 `BOARD_ID`를 확인한다. `BOARD_ID=1`은 사용할 수 없다.
- VU마다 서로 다른 테스트 사용자 Access Token을 준비한다.
- 실제 NCP Object Storage와 DB에 데이터가 생성되므로 운영 환경에서는 실행하지 않는다.

같은 사용자는 5분 동안 Post를 최대 3개만 작성할 수 있다. 따라서 `ITERATIONS`는 최대 3이며, 여러 VU를 사용할 때는 VU 수만큼 Access Token이 필요하다.

## 1회 확인 실행

```bash
BASE_URL='http://localhost:8080' \
BOARD_ID='2' \
ACCESS_TOKEN='테스트 사용자 Access Token' \
IMAGE_FILE='/절대경로/test-image.jpg' \
VUS='1' \
ITERATIONS='1' \
k6 run scripts/k6/post-image-load-test.js
```

환경변수 전달이 되지 않는 실행 환경에서는 k6의 `-e` 옵션을 사용한다.

```bash
k6 run \
  -e BASE_URL='http://localhost:8080' \
  -e BOARD_ID='2' \
  -e ACCESS_TOKEN='테스트 사용자 Access Token' \
  -e IMAGE_FILE='/절대경로/test-image.jpg' \
  -e VUS='1' \
  -e ITERATIONS='1' \
  scripts/k6/post-image-load-test.js
```

## 여러 사용자 부하 실행

Access Token은 쉼표로 구분한다.

```bash
BASE_URL='http://localhost:8080' \
BOARD_ID='2' \
ACCESS_TOKENS='token-user-1,token-user-2,token-user-3,token-user-4,token-user-5' \
IMAGE_FILE='/절대경로/test-image.jpg' \
VUS='5' \
ITERATIONS='1' \
k6 run scripts/k6/post-image-load-test.js
```

### 로컬 5 VU 테스트 사용자 자동 준비

기존 `k6-test@example.com` 계정의 암호화된 password와 프로필 형식을 기준으로 `k6-load-1@example.com`부터 `k6-load-5@example.com`까지 생성한다. 실행 중 기준 계정 비밀번호를 화면에 표시하지 않고 입력받아 각 계정의 Access Token을 발급한다.

```bash
bash scripts/k6/prepare-post-image-users.sh
```

발급된 Access Token 5개는 권한 `600`인 다음 임시 파일에 쉼표로 구분하여 저장된다.

```text
/tmp/k6-post-image-access-tokens
```

준비 후 5 VU 실행:

```bash
k6 run \
  -e BASE_URL='http://localhost:8080' \
  -e BOARD_ID='2' \
  -e ACCESS_TOKENS="$(cat /tmp/k6-post-image-access-tokens)" \
  -e IMAGE_FILE='/tmp/k6-test-image.jpg' \
  -e VUS='5' \
  -e ITERATIONS='1' \
  scripts/k6/post-image-load-test.js
```

스크립트는 로컬 `testdb`와 `iyongjun` DB 사용자를 기본값으로 사용한다. 다른 값은 `DB_NAME`, `DB_USER`, `BASE_EMAIL`, `USER_COUNT` 환경변수로 변경할 수 있다.

`IMAGE_COUNT` 기본값은 5이며 1~5 사이에서 변경할 수 있다.

```bash
IMAGE_COUNT='5'
```

## 측정 항목

| metric | 의미 |
| --- | --- |
| `post_image_presign_duration` | Presigned URL 발급 시간 |
| `post_image_object_upload_duration` | 이미지 한 장의 실제 NCP 업로드 시간 |
| `post_image_create_duration` | Post 생성 API 응답시간 |
| `post_image_uploaded_bytes` | 전체 업로드 byte |
| `http_req_failed` | 전체 HTTP 실패율 |
| `checks` | 단계별 성공 검증 비율 |

## 결과 해석 주의사항

- `post_image_create_duration`은 비동기 이미지 Copy 완료시간이 아니다.
- Post API가 `201`이어도 이후 비동기 Copy가 실패할 수 있다.
- 테스트 후 `ImageOperation`, `ImageOperationStep`, Outbox, DLQ 상태를 확인해야 한다.
- 테스트가 생성한 Post, Image, NCP object는 자동 삭제되지 않는다. 테스트 환경의 데이터를 별도로 정리해야 한다.
- 여러 VU에서 같은 Access Token을 공유하면 Post 도배 제한 때문에 실패하므로 성능 결과로 해석할 수 없다.

## 단일 사용자 Baseline

2026-06-11 실제 `9.6MB` JPEG 한 개를 동일 요청에서 5번 업로드한 `1 VU × 1 iteration` 결과:

| 항목 | 결과 |
| --- | ---: |
| checks | 100% |
| HTTP 실패율 | 0% |
| 총 업로드 용량 | 약 50MB |
| Presigned URL 발급 | 9.12ms |
| 이미지 한 장 업로드 평균 | 1.33초 |
| 이미지 한 장 업로드 최소/최대 | 939.6ms / 2.62초 |
| Post 생성 API 응답 | 18.78ms |
| 전체 사용자 흐름 | 2.72초 |

이 결과는 단일 사용자의 Presign 발급, 실제 NCP 업로드, Post API 접수까지의 baseline이다. Post 생성 이후 `@Async` 이미지 Copy 완료시간은 포함하지 않는다.

다음 단계는 서로 다른 테스트 계정과 Access Token을 준비하여 `5 VU × 1 iteration`으로 동일 흐름을 동시에 실행하는 것이다.

## k6 최종 측정 결과

### 5 VU

실제 `9.6MB` JPEG 5장씩, 사용자 5명이 동시에 요청했다.

| 항목 | 결과 |
| --- | ---: |
| 전체 성공률 | 100% |
| HTTP 실패율 | 0% |
| 전체 업로드 용량 | 약 252MB |
| 전체 사용자 흐름 평균 | 4.74초 |
| 전체 사용자 흐름 p95 | 5.61초 |
| 이미지 업로드 평균 | 3.11초 |
| 이미지 업로드 p95 | 3.68초 |
| Post 생성 API 평균 | 12.87ms |
| Post 생성 API p95 | 21.28ms |

### 50 VU

실제 `9.6MB` JPEG 5장씩, 사용자 50명이 동시에 요청했다.

| 항목 | 결과 |
| --- | ---: |
| 전체 성공률 | 100% |
| HTTP 실패율 | 0% |
| 전체 업로드 용량 | 약 2.5GB |
| 전체 사용자 흐름 평균 | 48.35초 |
| 전체 사용자 흐름 p95 | 49.1초 |
| 이미지 업로드 평균 | 41.8초 |
| 이미지 업로드 p95 | 46.05초 |
| Post 생성 API 평균 | 2.01초 |
| Post 생성 API p95 | 5.03초 |

모든 요청은 성공했지만 이미지 업로드와 Post 생성 API 성능 threshold를 초과했다.

### 100 VU

실제 `9.6MB` JPEG 5장씩, 사용자 100명이 동시에 요청했다. 잔여 부하 영향을 확인하기 위해 독립 재실행했다.

| 항목 | 첫 실행 | 독립 재실행 |
| --- | ---: | ---: |
| NCP 이미지 업로드 성공 | 6/500 | 14/500 |
| NCP 이미지 업로드 실패 | 494/500 | 486/500 |
| HTTP 실패율 | 82.33% | 81.00% |
| Post 생성 API 실행 | 0건 | 0건 |
| 실행시간 | 약 61초 | 약 61초 |

두 번 모두 Presigned URL 발급은 성공했지만, NCP PUT 요청 대부분이 약 60초 후 `request timeout`으로 실패했다. 이미지 한 장이라도 업로드에 실패하면 k6 스크립트가 Post 생성 전에 중단하므로 Post API는 호출되지 않았다.

## k6 최종 결론

```text
5 VU × 이미지 5장
→ 안정적으로 성공

50 VU × 이미지 5장
→ 전부 성공하지만 사용자 대기시간이 약 49초까지 증가

100 VU × 이미지 5장
→ NCP PUT 500개 동시 요청 대부분 timeout
```

- 사용자 중복 또는 Post 도배 방지가 실패 원인이 아니다.
- 100 VU 실패 지점은 Post API가 아니라 클라이언트 k6에서 NCP Object Storage로 보내는 Presigned PUT이다.
- 현재 로컬 실행 환경과 NCP bucket 기준으로 250개 동시 PUT는 느리지만 성공했고, 500개 동시 PUT는 안정적으로 처리하지 못했다.
- 실패 원인이 로컬 Mac의 네트워크·socket 한계인지 NCP 측 제한인지는 분산 부하 발생기 또는 별도 네트워크 환경 없이 확정할 수 없다.
- 사용자당 이미지 업로드 동시성을 제한하거나, 부하 발생기를 분산하여 다시 측정하는 것이 다음 개선 검증 후보다.

## 병목 분리 테스트

통합 테스트에서 확인한 지연을 다음 두 구간으로 분리한다.

```text
upload-only: Presign → NCP PUT
post-only: 이미 준비된 staging key → Post API → 비동기 Copy/DB 처리 시작
```

### 1. upload-only

`UPLOAD_BATCH_SIZE`는 VU 한 명이 동시에 실행하는 NCP PUT 수다. 이미지 5장 기준 `5`, `2`, `1`을 비교하면 전체 동시 PUT 수가 병목에 미치는 영향을 확인할 수 있다.

```bash
k6 run \
  -e BASE_URL='http://localhost:8080' \
  -e ACCESS_TOKENS="$(cat /tmp/k6-post-image-access-tokens)" \
  -e IMAGE_FILE='/tmp/k6-test-image.jpg' \
  -e IMAGE_COUNT='5' \
  -e UPLOAD_BATCH_SIZE='5' \
  -e VUS='100' \
  -e ITERATIONS='1' \
  scripts/k6/post-image-upload-only-test.js
```

같은 조건에서 `UPLOAD_BATCH_SIZE=5 → 2 → 1` 순서로 각각 실행한다.

| 결과 | 해석 |
| --- | --- |
| batch 크기를 줄이면 성공률이 크게 개선됨 | 동시 NCP PUT 수가 직접 병목인 근거 |
| batch 크기를 줄여도 동일하게 timeout | 로컬 업로드 대역폭, socket, NCP 측 제한을 추가 분리해야 함 |
| Presign만 느림 | 애플리케이션 Presign API 병목 후보 |

#### 100 VU 측정 결과

실제 `9.6MB` JPEG 5장씩, 사용자 100명 기준으로 비교했다.

| `UPLOAD_BATCH_SIZE` | 최대 동시 PUT 추정 | NCP PUT 성공 | 결과 |
| ---: | ---: | ---: | --- |
| 5 | 약 500개 | 3/500 | 대부분 약 60초 후 timeout |
| 2 | 약 200개 | 500/500 | 전부 성공, 업로드 p95 44.79초 |
| 1 | 약 100개 | 500/500 | 전부 성공했지만 업로드 p95 20.74초 |

`UPLOAD_BATCH_SIZE=1` 실행의 전체 사용자 흐름 p95는 약 91초였다. 각 사용자가 이미지 5장을 순차 업로드했기 때문에 한 장의 업로드는 성공했지만 전체 완료시간이 길어졌다.

`UPLOAD_BATCH_SIZE=2` 실행은 전체 성공했지만 전체 사용자 흐름 p95가 약 98초였고, 전체 전송 처리량은 약 `51MB/s`였다. `UPLOAD_BATCH_SIZE=1`의 약 `55MB/s`보다 처리량이 늘지 않으면서 개별 업로드 시간만 두 배 이상 증가했다.

현재 결과는 동시 PUT 수가 실패 여부에 직접 영향을 주며, 단일 부하 발생기 또는 해당 네트워크 경로의 업로드 처리량이 포화됐다는 강한 근거다. NCP Object Storage 자체 한도와 정확히 분리하려면 여러 부하 발생기 또는 다른 네트워크 환경이 필요하다.

### 2. post-only

측정 전에 사용자별 staging object를 실제 NCP에 준비한다. 준비 시간은 측정에 포함되지 않는다.

```bash
IMAGE_FILE='/tmp/k6-test-image.jpg' \
USER_COUNT='100' \
IMAGE_COUNT='5' \
bash scripts/k6/prepare-post-image-staging.sh
```

준비된 manifest를 사용해 Post API만 동시에 호출한다.

```bash
k6 run \
  -e BASE_URL='http://localhost:8080' \
  -e BOARD_ID='2' \
  -e ACCESS_TOKENS="$(cat /tmp/k6-post-image-access-tokens)" \
  -e STAGING_MANIFEST='/tmp/k6-post-image-staging.json' \
  -e VUS='100' \
  scripts/k6/post-image-post-only-test.js
```

`post-only` 결과에는 Presign과 클라이언트 NCP PUT가 포함되지 않는다. 다만 Post 이미지 처리는 `@Async`이므로 `post_only_create_duration`은 비동기 Copy/DB 작업 완료시간이 아니라 API 접수시간과 executor 포화에 따른 역압력만 보여준다.

| 결과 | 해석 |
| --- | --- |
| post-only가 빠르고 안정적임 | 주 병목은 클라이언트 → NCP PUT 구간 |
| post-only API 응답도 크게 느려짐 | 애플리케이션 thread, `imageExecutor`, DB 또는 NCP Copy 병목 후보 |
| API는 빠르지만 operation 처리가 오래 밀림 | 비동기 `imageExecutor`, NCP Copy, DB/Outbox 처리량 병목 후보 |

두 테스트가 생성한 staging/final object와 Post 데이터는 자동 삭제되지 않는다.

#### 100 VU 측정 결과

미리 준비한 staging key 5개씩으로 사용자 100명이 Post API를 동시에 호출했다.

| 항목 | 결과 |
| --- | ---: |
| Post 생성 성공 | 18/100 |
| Post 생성 실패 | 82/100 |
| 실패 응답 | HTTP 500 |
| 실패 응답시간 | 대부분 약 30.15초 |
| 테스트 직후 Post row | 18개 |
| 테스트 직후 `ImageOperation` | 54개 |

실패 응답시간이 HikariCP 기본 connection timeout인 30초와 일치한다. 로컬 설정은 Hikari pool 크기를 별도로 지정하지 않아 기본값 10을 사용한다.

코드상 `PostImageServiceImpl.savePostImages()`는 `imageExecutor`에서 실행되지만, 내부 Copy도 같은 `imageExecutor`에 다시 제출한 뒤 `join()`으로 기다린다. `imageExecutor`가 포화되면 `CallerRunsPolicy`가 HTTP 요청 thread에서 이미지 작업을 직접 실행할 수 있다. 이 과정에서 Post transaction과 이미지 operation의 여러 `REQUIRES_NEW` transaction이 동시에 DB connection을 요구한다.

따라서 현재 가장 유력한 병목 흐름은 다음과 같다.

```text
동시 Post 요청
→ imageExecutor 포화
→ CallerRunsPolicy로 일부 요청 thread가 이미지 작업 실행
→ 같은 executor에 내부 Copy 제출 후 join 대기
→ Hikari connection pool 고갈
→ 약 30초 후 HTTP 500
```

직접 예외를 확정하려면 서버 로그에서 `Connection is not available, request timed out after 30000ms` 또는 동등한 Hikari 예외를 확인해야 한다.

#### Hikari pool 25 비교 측정

동일한 `100 VU × staging 이미지 5장` 조건에서 Hikari 설정만 다음과 같이 변경해 다시 측정했다.

```text
maximum-pool-size: 25
minimum-idle: 25
connection-timeout: 3000ms
```

| 항목 | 기본 Hikari 설정 | Hikari pool 25 |
| --- | ---: | ---: |
| Post 생성 성공 | 18/100 | 96/100 |
| Post 생성 실패 | 82/100 | 4/100 |
| 실패 응답시간 | 약 30.15초 | 약 3.18초 |
| 성공 요청 응답 p95 | 확인 불가 | 257.65ms |

Post row도 기존 18개에서 114개로 증가해 이번 실행의 성공 96건과 일치했다. PostgreSQL idle connection은 26개로 확인돼 Hikari `minimum-idle=25` 설정 적용도 확인됐다.

성공률과 실패시간 변화는 DB connection pool 고갈이 HTTP 500의 직접 원인이라는 강한 근거다. 그러나 테스트 종료 10초 후에도 operation과 Copy step의 `PENDING/PROCESSING` 수가 변하지 않았다. 따라서 Hikari pool 확장은 HTTP 실패를 완화하지만, 동일 `imageExecutor` 중첩 사용으로 인한 비동기 작업 정체는 해결하지 못한다.

이후 Post/Poll 내부 Copy의 동일 `imageExecutor` 재제출과 `join()`을 제거했다. 바깥 `@Async`는 유지하고 이미지들은 현재 비동기 작업에서 순차 Copy한다. 서버 재시작과 새 staging 준비 후 동일 `100 VU post-only`를 재실행해 executor 정체 제거 효과를 측정해야 한다.
