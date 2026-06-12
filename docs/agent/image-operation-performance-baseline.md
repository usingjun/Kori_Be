# Image Operation 성능 Baseline

## 측정 목적

현재 일반 Post/Poll 다중 이미지 구조가 이미지 수 증가에 따라 생성하는 DB statement와 transaction 수를 측정한다.
S3 Copy는 mock으로 대체하여 네트워크 지연을 제외하고 `ImageOperation` 추적 구조의 DB 비용만 확인한다.

## 실행 방법

기본 테스트에서는 benchmark가 skip된다. 직접 측정할 때만 다음 명령을 실행한다.

```bash
IMAGE_OPERATION_DB_BENCHMARK=true ./gradlew test \
  --tests 'core.global.entity.image.service.ImageOperationDbBenchmarkTest' \
  --rerun-tasks --no-parallel
```

결과 CSV는 다음 파일의 `<system-out>`에서 확인한다.

```text
build/test-results/test/TEST-core.global.entity.image.service.ImageOperationDbBenchmarkTest.xml
```

## 측정 범위

```text
이미지별 ImageOperation 생성
→ COPY_STAGING_TO_FINAL step 생성
→ operation PROCESSING
→ step PROCESSING
→ Copy 성공
→ step COMPLETED
→ staging DELETE_OBJECT step + Outbox 저장
```

실제 S3, RabbitMQ publish, Consumer 실행 시간은 포함하지 않는다.

## 1번 결과: 코드 기준 호출량 분석

현재 Post/Poll 다중 이미지 처리에서 staging 이미지 `N`장을 Copy하고 각 staging object의 cleanup을 예약하면 호출량은 다음과 같이 증가한다.

| 구분 | 이미지 1장 | 이미지 N장 | 설명 |
| --- | ---: | ---: | --- |
| S3 `CopyObject` | 1회 | N회 | 기존 구조와 동일하며 정상 성공 시 추가 `HeadObject`는 실행하지 않는다. |
| `ImageOperation` 생성 | 1개 | N개 | 현재는 요청 단위가 아니라 이미지 단위로 생성한다. |
| `COPY_STAGING_TO_FINAL` step 생성 | 1개 | N개 | 각 Copy 실행 상태를 추적한다. |
| operation `PROCESSING` 갱신 | 1회 | N회 | `REQUIRES_NEW` transaction으로 실행한다. |
| Copy step `PROCESSING` 갱신 | 1회 | N회 | `REQUIRES_NEW` transaction으로 실행한다. |
| Copy step `COMPLETED` 갱신 | 1회 | N회 | `REQUIRES_NEW` transaction으로 실행한다. |
| staging `DELETE_OBJECT` step | 1개 | N개 | Copy 성공 후 staging object 삭제용이다. |
| cleanup Outbox | 1개 | N개 | `DELETE_OBJECT` message 발행용이다. |

추가 제거 이미지가 `R`개이면 제거 이미지 cleanup을 위한 `DELETE_OBJECT` step과 Outbox가 각각 `R`개 더 생성된다.

코드 분석만으로 S3 호출 횟수는 확인할 수 있다. DB 비용은 JPA flush, dirty checking, transaction 처리에 따라 실제 SQL 수가 달라질 수 있어 2번 benchmark로 측정했다.

## 2번 결과: 실제 PostgreSQL DB Baseline

2026-06-11 사용자가 로컬 PostgreSQL `testdb`에서 직접 실행한 결과:

| 이미지 수 | 실행시간 | Prepared statements | Hibernate transactions |
| ---: | ---: | ---: | ---: |
| 1 | 73ms | 10 | 5 |
| 5 | 40ms | 50 | 25 |
| 10 | 60ms | 100 | 50 |
| 20 | 95ms | 200 | 100 |

실행시간은 JVM warm-up과 로컬 DB 상태에 영향을 받으므로 단일 실행값만으로 판단하지 않는다.
호출량은 이미지 1장당 statement 10개, transaction 5개로 선형 증가한다.

### 2번 결과 해석

- 현재 구조의 확실한 비용은 이미지 1장당 prepared statement 10개와 Hibernate transaction 5개다.
- 이미지 20장이면 prepared statement 200개와 Hibernate transaction 100개가 발생한다.
- 1장 실행시간이 5장보다 긴 것은 첫 측정의 JVM/JPA warm-up 영향으로 보인다.
- 현재 단일 실행시간만으로 이미지 수별 지연 증가율이나 개선 필요 임계점을 확정할 수 없다.

## 3번 결과: 6회 반복 실행시간 측정

2026-06-11 동일 benchmark를 6회 다시 실행한 결과:

| 이미지 수 | 측정값 | 평균 | 중앙값 | 최소 | 최대 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | 67, 76, 65, 63, 62, 67ms | 66.7ms | 66ms | 62ms | 76ms |
| 5 | 38, 39, 38, 45, 40, 38ms | 39.7ms | 38.5ms | 38ms | 45ms |
| 10 | 54, 60, 58, 101, 57, 59ms | 64.8ms | 58.5ms | 54ms | 101ms |
| 20 | 90, 99, 90, 158, 90, 94ms | 103.5ms | 92ms | 90ms | 158ms |

모든 실행에서 prepared statement와 Hibernate transaction 수는 동일했다.

| 이미지 수 | Prepared statements | Hibernate transactions |
| ---: | ---: | ---: |
| 1 | 10 | 5 |
| 5 | 50 | 25 |
| 10 | 100 | 50 |
| 20 | 200 | 100 |

### 3번 결과 해석

- prepared statement와 transaction 수는 흔들리지 않고 이미지 수에 정확히 비례한다.
- 대표값으로 중앙값을 보면 10장은 약 59ms, 20장은 약 92ms다.
- 한 번의 실행에서 10장은 101ms, 20장은 158ms까지 증가했다. 로컬 DB 또는 JVM 일시 지연으로 보이지만 현재 자료만으로 원인을 확정할 수 없다.
- benchmark가 항상 1장부터 측정하므로 1장 결과에는 JPA와 DB 초기 실행 비용이 섞인다. 따라서 1장과 5장의 시간 역전은 구조가 5장에서 더 빠르다는 의미가 아니다.
- 현재 결과는 DB operation 추적 비용이 선형 증가하고 일시적인 지연 변동이 존재한다는 근거다.
- S3와 RabbitMQ가 mock 또는 측정 범위 밖이므로 실제 Post/Poll API 응답시간을 의미하지 않는다.

## 다음 분석 단계

### Post 이미지 서비스 DB benchmark

`PostImageServiceDbBenchmarkTest`는 `PostImageServiceImpl.savePostImages()` 전체 비즈니스 로직의 DB 비용을 측정한다.

```text
Post 이미지 존재 여부 조회
→ 이미지별 Operation/Step 생성
→ S3 Copy 성공 모의
→ Image row 저장 및 flush
→ moderation event 발행 모의
→ staging DELETE_OBJECT step + Outbox 저장
→ 외부 transaction commit
```

실행 방법:

```bash
POST_IMAGE_DB_BENCHMARK=true ./gradlew test \
  --tests 'core.global.entity.image.service.impl.PostImageServiceDbBenchmarkTest' \
  --rerun-tasks --no-parallel
```

결과 확인:

```bash
grep -E 'imageCount|^[0-9]+,[0-9]+,[0-9]+,[0-9]+' \
  build/test-results/test/TEST-core.global.entity.image.service.impl.PostImageServiceDbBenchmarkTest.xml
```

2026-06-11 최초 검증 결과:

| 이미지 수 | 실행시간 | Prepared statements | Hibernate transactions |
| ---: | ---: | ---: | ---: |
| 1 | 107ms | 12 | 6 |
| 5 | 46ms | 56 | 26 |
| 10 | 66ms | 111 | 51 |
| 20 | 101ms | 221 | 101 |

이 테스트는 실제 PostgreSQL에 `Image`, `ImageOperation`, `ImageOperationStep`, Outbox를 저장한다. 실제 S3 Copy, RabbitMQ publish, HTTP/API 계층, 실제 병렬 executor 비용은 포함하지 않는다.

2026-06-11 사용자 직접 5회 반복 측정 결과:

| 이미지 수 | 측정값 | 평균 | 중앙값 | 최소 | 최대 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | 97, 100, 99, 103, 97ms | 99.2ms | 99ms | 97ms | 103ms |
| 5 | 42, 42, 43, 43, 41ms | 42.2ms | 42ms | 41ms | 43ms |
| 10 | 59, 63, 68, 60, 65ms | 63ms | 63ms | 59ms | 68ms |
| 20 | 95, 105, 198, 109, 109ms | 123.2ms | 109ms | 95ms | 198ms |

반복 측정에서도 prepared statement와 Hibernate transaction 수는 항상 동일했다.

```text
Prepared statements = 11 × 이미지 수 + 1
Hibernate transactions = 5 × 이미지 수 + 1
```

`ImageOperationDbBenchmarkTest`와 비교하면 Post 이미지 DB 저장을 포함하면서 prepared statement는 이미지당 1개와 고정 1개가 추가되고, 외부 Post transaction 1개가 추가된다.

`20장=198ms`는 다른 네 번보다 크게 튄 outlier다. 현재 자료만으로 정확한 원인은 확정할 수 없으며, 대표값은 평균보다 중앙값 `109ms`로 보는 것이 적절하다.

### 우선순위 1: 실제 Post/Poll API 응답시간 측정

실제 Object Storage Copy를 포함한 Post/Poll 생성·수정 API의 응답시간을 측정한다. 현재 DB benchmark만으로는 네트워크 Copy와 기존 병렬 처리의 영향을 판단할 수 없다.

#### 실제 Object Storage Copy 포함 서비스 benchmark

`PostImageObjectStorageBenchmarkTest`는 실제 NCP Object Storage `CopyObject`와 PostgreSQL 저장을 함께 측정한다.

측정 전 각 `8 MiB` 크기의 benchmark staging object를 생성하고, 측정 종료 후 staging/final object와 DB row를 정리한다. 실제 object는 benchmark 전용 `temp/codex-post-benchmark/` prefix와 `posts/-3/` 경로를 사용한다.

benchmark object는 실제로 열 수 있는 JPEG를 생성한 뒤 trailing byte를 채워 정확히 `8 MiB`로 맞춘다. Object Storage Copy 성능에서는 이미지 해상도보다 object byte 크기가 핵심이므로 7~9MB 사진을 대표하는 크기로 사용한다.

측정 범위:

```text
Post 이미지 존재 여부 조회
→ 이미지별 Operation/Step 생성
→ 실제 NCP Object Storage CopyObject 병렬 실행
→ Image row 저장 및 flush
→ cleanup Step + Outbox 저장
→ transaction commit
```

측정에서 제외되는 범위:

```text
benchmark staging object 사전 생성
benchmark object 사후 삭제
RabbitMQ publish 및 Consumer 처리
HTTP Controller와 인증 처리
```

필요 환경변수:

```text
NCP_ACCESS_KEY
NCP_SECRET_KEY
NCP_BUCKET_NAME
```

로그를 숨기고 결과만 확인하는 실행 명령:

```bash
POST_IMAGE_OBJECT_STORAGE_BENCHMARK=true ./gradlew test \
  --tests 'core.global.entity.image.service.impl.PostImageObjectStorageBenchmarkTest' \
  --rerun-tasks --no-parallel \
  > /tmp/post-image-object-storage-benchmark.log 2>&1 \
&& grep -E 'imageCount|^[0-9]+,[0-9]+,[0-9]+,[0-9]+' \
  build/test-results/test/TEST-core.global.entity.image.service.impl.PostImageObjectStorageBenchmarkTest.xml
```

실패 로그 확인:

```bash
tail -n 100 /tmp/post-image-object-storage-benchmark.log
```

이 테스트는 실제 외부 Object Storage를 호출하므로 명시적으로 활성화할 때만 실행한다. 테스트 실패나 프로세스 강제 종료 시 정리 코드가 끝까지 실행되지 않을 가능성은 남아 있으므로 benchmark prefix 잔존 여부를 별도로 확인할 수 있어야 한다.

#### 실제 NCP object 존재 직접 확인

`NcpObjectStorageConnectionVerificationTest`는 다음 고정 key를 사용한다.

```text
temp/codex-post-benchmark/manual-verification-8mb.jpg
```

업로드 후 자동 삭제하지 않고 남기는 명령:

```bash
NCP_OBJECT_STORAGE_VERIFY_ACTION=UPLOAD ./gradlew test \
  --tests 'core.global.entity.image.service.impl.NcpObjectStorageConnectionVerificationTest' \
  --rerun-tasks --no-parallel \
  > /tmp/ncp-object-storage-verification.log 2>&1 \
&& grep 'verificationAction=' \
  build/test-results/test/TEST-core.global.entity.image.service.impl.NcpObjectStorageConnectionVerificationTest.xml
```

업로드 후 NCP Object Storage Console에서 해당 key를 직접 확인한다.

확인 완료 후 같은 object를 삭제하는 명령:

```bash
NCP_OBJECT_STORAGE_VERIFY_ACTION=DELETE ./gradlew test \
  --tests 'core.global.entity.image.service.impl.NcpObjectStorageConnectionVerificationTest' \
  --rerun-tasks --no-parallel \
  > /tmp/ncp-object-storage-verification.log 2>&1 \
&& grep 'verificationAction=' \
  build/test-results/test/TEST-core.global.entity.image.service.impl.NcpObjectStorageConnectionVerificationTest.xml
```

이 테스트는 `8 MiB` 크기의 고정된 verification key 하나만 생성하거나 삭제한다. 실제 benchmark object의 자동 cleanup 동작은 변경하지 않는다.

2026-06-11 사용자 직접 5회 반복 측정 결과:

| 이미지 수 | 측정값 | 평균 | 중앙값 | 최소 | 최대 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | 298, 272, 344, 191, 187ms | 258.4ms | 272ms | 187ms | 344ms |
| 5 | 118, 126, 98, 107, 99ms | 109.6ms | 107ms | 98ms | 126ms |
| 10 | 137, 119, 143, 109, 108ms | 123.2ms | 119ms | 108ms | 143ms |
| 20 | 1166, 164, 1294, 198, 1134ms | 791.2ms | 1134ms | 164ms | 1294ms |

위 결과는 각 staging object가 `3-byte`였던 초기 연결 및 병렬 Copy 검증 결과다. 실제 7~9MB 사진 크기 성능으로 해석하지 않는다. 이후 benchmark object 크기를 `8 MiB`로 변경했으므로 새 결과를 별도로 측정해야 한다.

2026-06-11 `8 MiB` JPEG 기준 사용자 직접 5회 반복 측정 결과:

| 이미지 수 | 측정값 | 평균 | 중앙값 | 최소 | 최대 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 1 | 344, 236, 265, 220, 215ms | 256ms | 236ms | 215ms | 344ms |
| 5 | 156, 155, 149, 162, 162ms | 156.8ms | 156ms | 149ms | 162ms |
| 10 | 226, 203, 181, 188, 324ms | 224.4ms | 203ms | 181ms | 324ms |
| 20 | 1231, 306, 1197, 300, 280ms | 662.8ms | 306ms | 280ms | 1231ms |

모든 반복 실행에서 prepared statement와 Hibernate transaction 수는 `3-byte` 측정과 동일했다.

### `8 MiB` 실제 Object Storage 측정 해석

- NCP `CopyObject`는 애플리케이션이 object를 다운로드한 뒤 다시 업로드하지 않는 server-side Copy다. 따라서 `8 MiB` 크기가 네트워크 업로드 시간처럼 요청 애플리케이션에 직접 전부 반영되지는 않는다.
- `5장`은 중앙값 156ms, `10장`은 중앙값 203ms로 비교적 안정적이었다.
- `20장`은 정상 구간에서 280~306ms였지만, 5회 중 2회는 1.1초를 넘었다.
- `8 MiB` 기준에서도 동시 Copy 20개의 tail latency 문제 후보가 반복 확인됐다.
- 20장 평균 662.8ms는 두 번의 지연값 영향을 크게 받으므로 일반적인 처리시간은 중앙값 306ms, 지연 발생 시 약 1.2초로 구분해서 해석하는 것이 적절하다.
- 실제 사용자 업로드 시간은 presigned URL로 클라이언트가 staging object를 올리는 구간이며, 이 benchmark에는 포함되지 않는다.
- 현재 결과만으로 20장 지연 원인이 NCP 내부 처리 제한, connection pool, 네트워크 변동, executor 경쟁 중 무엇인지 확정할 수 없다.

모든 반복 실행에서 DB 수치는 동일했다.

```text
Prepared statements = 14 × 이미지 수 + 1
Hibernate transactions = 4 × 이미지 수 + 1
```

### 실제 Object Storage 측정 해석

- 5장과 10장은 병렬 Copy 효과로 각각 중앙값 107ms, 119ms로 안정적이었다.
- 20장은 5회 중 3회가 1초를 넘었고, 최소 164ms와 최대 1294ms 사이의 변동 폭이 매우 크다.
- 현재 결과는 20개 동시 Copy 구간에서 tail latency가 발생할 가능성을 보여준다.
- 20장 지연 원인이 NCP Object Storage 제한, 네트워크 변동, HTTP connection 대기, 로컬 executor 경쟁 중 무엇인지는 현재 측정만으로 확정할 수 없다.
- 1장 결과는 매 실행의 첫 외부 Copy이므로 연결 준비와 warm-up 비용이 포함되어 5장보다 느리다.
- 실제 Copy benchmark는 병렬 executor thread에서 DB operation 상태를 갱신한다. 따라서 단일 thread로 실행한 DB-only benchmark와 prepared statement 및 transaction 수가 다르며, 두 수치를 직접 비교해 Operation 비용 증감으로 단정하면 안 된다.

### 다음 성능 분석

다음 단계에서는 동시 Copy 개수를 제한하면서 20장 benchmark를 비교한다.

```text
동시성 5
동시성 10
동시성 20
```

각 조건에서 20장 처리시간을 여러 번 측정하면, 현재 1초 이상 지연이 과도한 동시 요청 때문에 발생하는지 확인할 수 있다. 원인 확인 전 운영 `imageExecutor` 설정이나 NCP 연동 정책을 변경하지 않는다.

#### 20장 Copy 동시성 비교 실행 방법

`PostImageObjectStorageBenchmarkTest.measureTwentyImagesByCopyConcurrency()`는 `8 MiB` JPEG 20장을 대상으로 benchmark 내부 executor 동시성만 `5`, `10`, `20`으로 변경하여 측정한다.

운영 `imageExecutor` 설정은 변경하지 않는다.

로그를 숨기고 결과만 확인하는 실행 명령:

```bash
POST_IMAGE_OBJECT_STORAGE_BENCHMARK=true \
POST_IMAGE_COPY_CONCURRENCY_BENCHMARK=true \
./gradlew test \
  --tests 'core.global.entity.image.service.impl.PostImageObjectStorageBenchmarkTest.measureTwentyImagesByCopyConcurrency' \
  --rerun-tasks --no-parallel \
  > /tmp/post-image-copy-concurrency-benchmark.log 2>&1 \
&& grep -E 'concurrency,imageCount|^[0-9]+,[0-9]+,[0-9]+,[0-9]+,[0-9]+' \
  build/test-results/test/TEST-core.global.entity.image.service.impl.PostImageObjectStorageBenchmarkTest.xml
```

실패 로그 확인:

```bash
tail -n 100 /tmp/post-image-copy-concurrency-benchmark.log
```

한 번 실행하면 동시성 `5`, `10`, `20` 결과가 모두 출력된다. 동일 명령을 5회 실행하여 각 동시성의 중앙값과 1초 이상 지연 발생 횟수를 비교한다.

2026-06-11 `8 MiB` JPEG 20장 기준 사용자 직접 5회 반복 측정 결과:

| Copy 동시성 | 측정값 | 평균 | 중앙값 | 최소 | 최대 | 1초 이상 |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 5 | 625, 631, 631, 641, 590ms | 623.6ms | 631ms | 590ms | 641ms | 0/5 |
| 10 | 460, 305, 309, 318, 311ms | 340.6ms | 311ms | 305ms | 460ms | 0/5 |
| 20 | 1182, 312, 1177, 1161, 1210ms | 1008.4ms | 1177ms | 312ms | 1210ms | 4/5 |

모든 조건에서 prepared statement 281개와 Hibernate transaction 81개로 동일했다. 차이는 Copy 동시성에 따른 외부 Object Storage 처리시간에서 발생했다.

### Copy 동시성 비교 결론

- 동시성 `5`는 안정적이지만 20장을 다섯 번에 나눠 처리하므로 중앙값 631ms로 느리다.
- 동시성 `10`은 중앙값 311ms이며 1초 이상 지연이 한 번도 없어 현재 측정에서 가장 빠르고 안정적이다.
- 동시성 `20`은 5회 중 4회가 1초 이상 걸려 tail latency가 명확히 반복됐다.
- 현재 로컬 환경과 NCP bucket 기준으로는 Post/Poll 한 요청의 Copy 동시성을 `10` 이하로 제한하는 것이 유력한 개선 후보다.
- 다만 테스트가 매번 `5 → 10 → 20` 순서로 실행됐으므로 측정 순서 영향 가능성이 있다. 운영 설정 변경 전 순서를 바꾸거나 각 동시성을 독립 실행하여 결과를 재확인해야 한다.
- 운영 `imageExecutor` 전체 pool 크기와 요청당 Copy 동시성은 별개다. 전체 pool을 단순히 `10`으로 줄이면 여러 사용자 요청 처리량까지 제한할 수 있으므로, 요청 단위 동시성 제한 방식을 검토해야 한다.

### 우선순위 2: Outbox 처리 지연 측정

domain transaction commit 시점부터 RabbitMQ publish 및 cleanup Consumer 완료까지 걸리는 시간을 측정한다. 현재 polling 주기가 사용자에게 허용 가능한 cleanup 지연인지 판단한다.

### k6 병목 분리 테스트

통합 k6 결과의 병목을 구간별로 확인하기 위해 다음 스크립트를 추가했다.

| 스크립트 | 측정 구간 | 제외 구간 |
| --- | --- | --- |
| `post-image-upload-only-test.js` | Presign, 실제 NCP PUT | Post API, 서버 Copy/DB/Outbox |
| `post-image-post-only-test.js` | Post API 접수, executor 포화에 따른 역압력 | Presign, 클라이언트 NCP PUT |

`upload-only`는 `UPLOAD_BATCH_SIZE=5/2/1`을 비교해 사용자당 동시 PUT 수를 줄였을 때 timeout이 개선되는지 확인한다.

`post-only`는 `prepare-post-image-staging.sh`로 사용자별 staging key를 미리 준비한 후 실행한다. Post 처리는 `@Async`이므로 API 응답시간만으로 Copy/DB 완료시간을 판단하지 않으며, 실행 후 operation/step 상태 적체를 함께 확인해야 한다.

post-only 분리 테스트의 실제 측정값은 아직 없다.

2026-06-12 upload-only 측정 결과:

| VU | 이미지 수 | `UPLOAD_BATCH_SIZE` | NCP PUT 결과 | 업로드 p95 | 전체 흐름 p95 |
| ---: | ---: | ---: | --- | ---: | ---: |
| 100 | 5 | 5 | 3/500 성공, 497/500 timeout | 실패값으로 왜곡 | 약 61초 |
| 100 | 5 | 2 | 500/500 성공 | 44.79초 | 약 98초 |
| 100 | 5 | 1 | 500/500 성공 | 20.74초 | 약 91초 |

동시 PUT 약 500개는 대부분 timeout 됐지만 약 100개와 200개는 모두 성공했다. 따라서 동시 PUT 수가 현재 실패 여부에 직접 영향을 준다. `UPLOAD_BATCH_SIZE=1/2`의 마지막 k6 `ERRO`는 HTTP 실패가 아니라 `upload_only_object_upload_duration p95 < 10초` threshold 초과다.

`UPLOAD_BATCH_SIZE=1`의 전체 처리량은 약 `55MB/s`, `UPLOAD_BATCH_SIZE=2`는 약 `51MB/s`였다. 동시성을 두 배로 높여도 처리량은 증가하지 않고 이미지 한 장 업로드 p95가 `20.74초`에서 `44.79초`로 증가했다. 단일 부하 발생기 또는 현재 네트워크 경로의 업로드 처리량 포화가 강한 병목 후보다.

2026-06-12 post-only `100 VU × 이미지 5장` 측정에서는 Post 생성 18/100건만 성공하고 82건이 약 30.15초 후 HTTP 500으로 실패했다.

실패시간은 HikariCP 기본 connection timeout 30초와 일치한다. 현재 로컬 설정은 별도 Hikari pool 크기가 없어 기본값 10을 사용한다. `PostImageServiceImpl.savePostImages()`와 내부 `CompletableFuture` Copy가 같은 `imageExecutor`를 중첩 사용하고, 포화 시 `CallerRunsPolicy`가 요청 thread에 작업을 넘긴다. 여러 `REQUIRES_NEW` transaction까지 동시에 connection을 요구하므로 DB connection pool 고갈이 가장 유력한 직접 실패 원인이다.

서버 로그의 실제 Hikari timeout 예외는 아직 확인하지 못했으므로 직접 예외 종류는 확정하지 않는다.

Hikari pool을 `10 → 25`, connection timeout을 `30초 → 3초`로 변경한 동일 조건 재측정에서는 Post 성공이 `18/100 → 96/100`으로 증가했고, 실패 4건은 약 3.18초 후 반환됐다. 성공 요청 p95는 257.65ms였다.

설정 변경에 따라 성공률이 크게 개선되고 실패시간이 connection timeout과 함께 변경됐으므로 DB connection pool 고갈이 HTTP 500의 직접 원인이라는 강한 근거가 확보됐다.

다만 테스트 종료 10초 후에도 다음 상태가 유지됐다.

```text
ImageOperation PENDING: 10
ImageOperation PROCESSING: 27
COPY_STAGING_TO_FINAL PENDING: 19
```

Hikari pool 확장은 증상을 완화하지만 동일 `imageExecutor`를 외부 `@Async` 작업과 내부 Copy에 중첩 사용하는 구조적 정체는 해결하지 못한다.

### 중첩 imageExecutor 제거 후 DB benchmark

Post/Poll 이미지 처리에서 내부 `CompletableFuture.supplyAsync(..., imageExecutor)`와 `join()`을 제거하고, 바깥 `@Async` 작업 안에서 이미지를 순차 Copy하도록 변경했다.

변경 후 `PostImageServiceDbBenchmarkTest` 결과:

| 이미지 수 | 실행시간 | Prepared statements | Hibernate transactions |
| ---: | ---: | ---: | ---: |
| 1 | 125ms | 12 | 6 |
| 5 | 57ms | 56 | 26 |
| 10 | 86ms | 111 | 51 |
| 20 | 133ms | 221 | 101 |

prepared statement와 transaction 수는 변경 전과 동일하다. 이번 변경은 같은 executor의 중첩 제출과 `join()` 대기를 제거한 것이며, DB transaction 증폭은 해결하지 않는다.

기존 Copy 동시성 `5/10/20` benchmark는 제거된 내부 병렬 Copy 구조를 측정한 역사적 결과다. 현재 구조의 개선 효과는 서버 재시작 후 `100 VU post-only`로 다시 측정해야 한다.

### operation/step 상태 저장 Batch 적용 후 DB benchmark

Post/Poll의 이미지별 operation 추적은 유지하면서 한 요청의 operation/step 준비 상태와 Copy 완료 상태를 각각 batch transaction으로 저장하도록 변경했다.

2026-06-12 `PostImageServiceDbBenchmarkTest` 결과:

| 이미지 수 | 실행시간 | Prepared statements | Hibernate transactions |
| ---: | ---: | ---: | ---: |
| 1 | 120ms | 8 | 4 |
| 5 | 27ms | 32 | 8 |
| 10 | 37ms | 62 | 13 |
| 20 | 62ms | 122 | 23 |

변경 전과 비교:

| 이미지 수 | Prepared statements | Hibernate transactions |
| ---: | ---: | ---: |
| 1 | `12 → 8` | `6 → 4` |
| 5 | `56 → 32` | `26 → 8` |
| 10 | `111 → 62` | `51 → 13` |
| 20 | `221 → 122` | `101 → 23` |

transaction 증가식은 `5 × 이미지 수 + 1`에서 대략 `이미지 수 + 3`으로 줄었다. 남아 있는 이미지 수 비례 transaction은 성공 후 staging cleanup step/Outbox 저장 경로의 영향이 크다.

이번 변경은 이미지별 상태 갱신 transaction 증폭을 줄였지만, Post/Poll 서비스 바깥 `@Transactional`은 여전히 NCP Copy 동안 유지된다. 따라서 Copy 대기 중 DB connection 장기 점유를 제거하려면 Copy orchestration과 Image DB 저장 transaction 경계를 추가로 분리해야 한다.

### 개선 판단 기준

- 실제 API 응답시간이 요구사항을 만족하면 현재 구조를 유지하고 관찰한다.
- DB 부하가 문제가 되면 요청당 `ImageOperation` 1개와 이미지별 step 구조를 검토한다.
- transaction 수가 병목이면 `REQUIRES_NEW` 상태 갱신 횟수를 줄이거나 batch 상태 갱신을 검토한다.
- Outbox 지연만 문제라면 polling fallback은 유지하면서 commit 직후 발행 신호를 추가하는 방안을 검토한다.
- 허용 가능한 API 응답시간과 cleanup 완료시간 기준은 저장소에서 확인되지 않아 사용자가 결정해야 한다.

## 비교 시 주의사항

- 개선 전후 동일한 PostgreSQL, 이미지 수, JVM 상태를 사용한다.
- 최소 5회 warm-up 후 여러 번 실행하여 평균과 p95를 비교한다.
- batch 구조 개선 후에도 같은 benchmark를 수정하지 않고 재실행한다.
- 실제 사용자 체감 성능은 별도로 RabbitMQ와 Object Storage를 포함해 측정해야 한다.
