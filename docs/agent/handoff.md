# Image Operation 신뢰성 개선 Handoff

## 프로젝트 개요

이 프로젝트는 Spring Boot 기반 backend이며, 이미지 파일은 S3/NCP Object Storage에 저장하고 image metadata와 작업 상태는 PostgreSQL에 저장한다.

클라이언트는 presigned URL을 통해 `temp/` staging key에 이미지를 업로드한다. domain service는 staging object를 final key로 Copy하고 `Image` row를 저장하거나 갱신한다.

이 작업의 목표는 S3/NCP와 PostgreSQL 사이의 부분 실패를 추적하고 복구할 수 있도록 이미지 생성·수정·삭제 흐름을 `ImageOperation + ImageOperationStep + Outbox + RabbitMQ` 구조로 점진 전환하는 것이다.

핵심 원칙:

- API response format, CDN URL, object key 규칙은 유지한다.
- S3/NCP와 PostgreSQL의 단일 transaction을 시도하지 않는다.
- 부분 실패는 idempotent retry와 compensation으로 복구한다.
- RabbitMQ는 전달/retry/DLQ, PostgreSQL은 상태 추적과 Outbox를 담당한다.
- 기존 `FailedImageCleanup` fallback은 제거하지 않는다.

## 먼저 읽을 문서

다음 Task는 아래 순서로 문서를 읽는 것이 좋다.

1. `docs/agent/handoff.md`
2. `docs/agent/progress.md`
3. `docs/agent/decision-log.md`
4. `docs/agent/image-operation-rabbitmq-design.md`
5. `docs/agent/image-operation-rabbitmq-phase1-operations.md`
6. `docs/agent/image-operation-state-machine-summary.md`

작업 전 저장소 루트의 `AGENTS.md`와 `docs/agent/project-context.md`를 먼저 확인한다.

## 현재 구현 상태

### 커밋 완료 상태

현재 브랜치는 `feat/image-idempotency`이며, 마지막 완료 커밋은 `git log -1 --oneline`으로 확인한다.

이 커밋까지 포함된 주요 기능:

- 공통 `ImageOperation`, `ImageOperationStep`
- `ImageOperationPublishOutbox`
- publisher confirm 기반 RabbitMQ 발행
- 공통 `ImageOperationRabbitConsumer`
- `ImageOperationConsumedMessage` 기반 중복 소비 방지
- `COMPENSATE_FINAL_OBJECT`, `DELETE_OBJECT`, `DELETE_FOLDER`
- retry queue, DLQ, timeout 복구
- 보상 성공 시 `COMPENSATED`
- 일반 cleanup 실패를 공통 `CLEANUP_ONLY` operation으로 통합
- `FailedImageCleanupScheduler` fallback 유지
- 사용자 프로필 수정 성공 후 old/staging cleanup Outbox 적용
- 채팅방 프로필 수정 성공 후 old/staging cleanup Outbox 적용
- 사용자 프로필 생성 성공 후 old/staging cleanup Outbox 적용
- 사용자 프로필 삭제의 `DELETE_FOLDER + Outbox` 적용
- 채팅방 프로필 생성 성공 후 staging cleanup Outbox 적용
- 채팅방 프로필 삭제의 `DELETE_FOLDER + Outbox` 적용
- 사용자 프로필 직접 MultipartFile 업로드의 operation 추적과 보상 삭제 적용
- AI 사용자 프로필 교체의 folder 선삭제 제거
- 일반 Post presigned 이미지 생성·수정의 공통 Operation/Outbox 적용
- Poll 이미지 upsert의 공통 Operation/Outbox 적용
- 다중 이미지 Copy의 이미지별 operation 추적과 부분 성공 compensation 적용
- Post/Poll 다중 Copy의 operation/step 준비 및 완료 상태 batch transaction 적용
- Post/Poll NCP Copy와 Image DB 저장 transaction 경계 분리
- Post/Poll 상위 transaction commit 이후 이미지 `@Async` 작업 제출
- 다중 이미지 DB 성능 baseline 측정용 `ImageOperationDbBenchmarkTest` 추가
- assigned UUID operation entity의 `@Version` 신규 판별 문제 수정

### 현재 완료 상태

사용자·채팅방 프로필 생성·수정·삭제에 다음 구조가 적용돼 있다.

```text
사용자/채팅방 생성:
CREATE_USER_PROFILE_IMAGE 또는 CREATE_CHAT_ROOM_PROFILE_IMAGE로 staging Copy 추적
→ Image row 저장 및 flush
→ staging 삭제를 Outbox로 예약

사용자 삭제:
Image row 삭제 + DELETE_FOLDER step + Outbox 저장
→ commit 후 Consumer가 folder 삭제

채팅방 삭제:
Image row 삭제 + DELETE_FOLDER step + Outbox 저장
→ commit 후 Consumer가 chatRoom/{chatRoomId}/ folder 삭제

직접 MultipartFile 업로드:
UPLOAD_OBJECT step 생성
→ putObject 동기 실행 및 성공 응답 확인
→ Image DB upsert
→ 성공 시 기존 object DELETE_OBJECT Outbox
→ 실패/rollback 시 새 object compensation

일반 Post/Poll 다중 이미지:
상위 Post/Poll transaction commit 성공
→ `afterCommit()`에서 이미지 `@Async` 작업 제출
각 staging image Copy를 독립 operation으로 추적하되 준비/완료 상태는 요청 단위 batch transaction으로 저장
→ NCP Copy는 DB transaction 없이 실행
→ 최종 snapshot 검증
→ DB 저장 성공 시 staging/removed object DELETE_OBJECT Outbox
→ 일부 Copy 또는 DB 저장 실패 시 성공한 final object만 compensation
```

실패 시 의미:

- Copy 실패: Image row를 저장하지 않고 staging object를 유지한다.
- Copy 성공 후 DB flush 실패: 생성된 final object에 compensation 삭제를 예약한다.
- 상위 채팅방 생성 transaction rollback: 생성된 final object에 compensation 삭제를 예약한다.
- DB 성공: staging object 삭제를 공통 Outbox/Consumer가 처리한다.
- 사용자 삭제 transaction rollback: DB 삭제와 folder cleanup Outbox가 함께 rollback된다.
- 채팅방 삭제 transaction rollback: DB 삭제와 folder cleanup Outbox가 함께 rollback된다.
- 직접 업로드 실패 또는 transaction rollback: 새 object compensation을 예약한다.
- RabbitMQ 비활성화 시 compensation은 직접 bulk delete로 실행하고 실패 기록은 기존 `FailedImageCleanup`이 담당한다.

`ProfileImageServiceImplTest`, `ImageOperationRecoveryServiceTest`, 이미지 관련 대상 테스트를 통과했다.
최신 `./scripts/agent-check.sh`는 191개 중 18개가 기존 `pgroonga` extension 생성 권한 문제로 실패했고, 4개는 skip됐다. 이번 이미지 변경으로 확인된 실패는 없다.

### 현재 데이터/메시지 흐름

```text
Domain Service
→ ImageOperation / ImageOperationStep 저장
→ ImageOperationPublishOutbox 저장
→ transaction commit
→ ImageOperationOutboxRelay polling
→ ImageOperationRabbitPublisher + publisher confirm
→ RabbitMQ
→ ImageOperationRabbitConsumer
→ S3/NCP delete 실행
→ step/operation 완료 또는 retry/DLQ
```

일반 삭제가 실패하면 `FailedImageCleanup`도 기록된다. 신규 공통 operation 경로가 주 처리 경로이며, 기존 scheduler는 fallback이다.

## 중요한 클래스 책임

| 클래스 | 현재 책임 |
| --- | --- |
| `ImageOperationService` | operation 생성과 상위 상태 관리 |
| `ImageOperationStepService` | Copy step 상태 관리 |
| `ImageOperationBatchService` | Post/Poll staging Copy 실행, 부분 실패 compensation, cleanup 연결 |
| `ImageOperationBatchTransactionService` | Post/Poll 다중 Copy의 operation/step 준비, 성공 결과, 실패 상태를 batch transaction으로 저장 |
| `ImagePersistenceTransactionService` | Copy 전 Image snapshot 조회와 Copy 후 DB 저장·cleanup Outbox의 짧은 transaction 관리 |
| `ImageServiceImpl` | Post/Poll transaction commit 이후 실제 비동기 이미지 작업 제출 |
| `ImageOperationRecoveryService` | compensation/delete/failure retry/DLQ/timeout 복구 orchestration |
| `ImageOperationOutboxService` | 발행할 Outbox 조회 및 발행 결과 기록 |
| `ImageOperationOutboxRelay` | pending Outbox를 주기적으로 RabbitMQ에 전달 |
| `ImageOperationRabbitPublisher` | destination/routing 결정과 publisher confirm 확인 |
| `ImageOperationRabbitConsumer` | 공통 delete step 소비와 결과 상태 반영 |
| `ImageObjectDeleteExecutor` | `default/`를 제외하고 object 삭제 수행 |
| `FailedImageCleanupService` | 기존 실패 기록/fallback 실행 및 공통 operation 연결 |
| `ImageCleanupOperationBridge` | 기존 cleanup 실패를 공통 `ImageOperation`으로 연결 |

## 남은 작업

### 즉시 이어갈 작업

1. UUID final key 직접 업로드와 Post/Image 동일 transaction을 실제 환경에서 검증한다.
2. 미등록 UUID object 정리 배치를 실제 NCP 환경에서 검증한다.
3. 기존 `temp/` fallback 제거 시점을 결정한다.

### 채팅방 프로필 생성 목표

```text
staging Copy
→ Image DB 저장
→ 성공 시 staging cleanup Outbox
→ DB 실패 시 final object compensation
```

기존 API 응답과 key 생성 규칙은 유지해야 한다.

### 이후 확장

- 관리자 Post `MultipartFile` 직접 업로드와 크롤러 외부 URL 업로드
- 그 외 image owner 흐름
- Outbox 발행 지연 개선
- Copy retry + `REGISTER_IMAGE_DB` 연속 파이프라인
- moderation event Outbox
- 실제 RabbitMQ 환경 검증
- 운영 조회/replay

## 다음 Task에서 가장 먼저 해야 할 일

다음 Codex는 새 구현을 시작하기 전에 아래를 먼저 수행해야 한다.

1. `git status --short`와 `git log -1 --oneline`으로 현재 상태를 확인한다.
2. `docs/agent/post-image-update-sequence.md`에서 현재 Post 수정 흐름과 transaction 경계를 확인한다.
3. 기존에 남은 오래된 `COPY_STAGING_TO_FINAL / PROCESSING` step의 안전한 복구 경계를 확인한다.
4. timeout 복구 시 Copy만 재시도하지 말고 target object compensation과 상태 종결을 보장한다.

UUID final key 직접 업로드, Post/Image 동일 transaction, 미등록 UUID object 정리 배치를 구현했다. 다음 작업의 첫 대상은 실제 NCP 환경 검증이다.

추가로 Post 등록 시 직접 업로드 확인과 session 유지보수를 구현했다.

```text
Presigned URL 발급
→ ISSUED
→ NCP PUT
→ Post 등록 요청
→ 상위 transaction을 잠시 중단하고 HeadObject 확인
→ Post/Image transaction에서 ISSUED → CLAIMED → REGISTERED
```

- 실제 object가 없는 신규 UUID final key는 Post 등록이 거절된다.
- `DELETE_FAILED`는 기본 1시간 후 새 cleanup operation으로 재처리한다.
- `DELETED`는 기본 30일, `REGISTERED`는 기본 90일 후 session row를 삭제한다.
- 현재 운영 중이 아니므로 session 없는 기존 Presigned URL의 임시 허용 기능은 구현하지 않았다.
- 최신 전체 검증은 229개 중 18개 실패, 4개 skip이며, 18개 실패는 모두 기존 `pgroonga` extension 생성 권한 문제다.

## 수정하면 안 되는 부분

- 기존 API response format
- 기존 image URL 및 CDN URL 형식
- 기존 object key 생성/변환 규칙
- `default/` key 삭제 제외 규칙
- 실제 S3/NCP Object Storage integration 방식
- `FailedImageCleanup` 및 `FailedImageCleanupScheduler` fallback
- secrets, 실제 env 값, Docker, CI/CD, deployment, infrastructure 설정
- 알려진 `pgroonga` 테스트 권한 문제

다음 두 untracked 문서는 사용자 작업일 수 있으므로 명시적 요청 없이 삭제하거나 덮어쓰지 않는다.

- `docs/agent/image-operation-rabbitmq-design.md`
- `docs/agent/image-operation-state-machine-summary.md`

## 구현 시 반드시 지킬 검증 기준

각 생성·수정·삭제 흐름을 적용할 때 최소 다음을 테스트한다.

- 정상 성공 시 API/URL/key 동작 유지
- Copy 실패 시 기존 DB/object 유지
- Copy 성공 후 DB 실패 시 compensation 생성
- DB 성공 후 old/staging cleanup Outbox 생성
- 동일 message 중복 소비 시 side effect 중복으로 상태가 깨지지 않음
- `default/` key 미삭제
- retry 한도 초과 시 DLQ 전환
- `PROCESSING` timeout 복구

최신 전체 검증은 223개 중 18개 실패, 4개 skip이며, 18개 실패는 모두 `pgroonga` extension 생성 권한 환경 제한이다. 이미지 대상 테스트 결과와 분리해 보고해야 한다.

## 현재 판단이 필요한 사항

- 최종 구조의 `50 VU`, `100 VU post-only` API 요청은 모두 100% 성공했다.
- `50 VU`는 Post 50개, Image 250개, Copy step/operation 250개가 모두 완료됐다.
- `100 VU`는 Post 100개, Image 500개, Copy step/operation 500개가 모두 완료됐다.
- 두 최종 측정 모두 HTTP 실패, `FAILED`, 남은 `PROCESSING`이 0건이었다.
- 최초 `100 VU post-only`는 18/100건만 성공하고 82건이 약 30.15초 후 실패했다.
- DB benchmark 최초 대비 최종 구조는 이미지 5장 transaction이 `26 → 9`, 이미지 20장 transaction이 `101 → 24`로 감소했다.
- 기존 테스트 데이터에 남은 오래된 `PROCESSING` 상태는 별도 timeout 복구 대상이다.
- Post/Poll presigned 이미지 작업은 고정 worker 10개, queue 200개의 `postImageExecutor`를 사용한다.
- `PostImageService`와 `MainContentImageService` 인터페이스의 잔여 `@Transactional`을 제거해 NCP Copy 동안 DB connection이 유지되지 않도록 했다.
- 프로필 이미지와 관리자 `MultipartFile` Post 업로드는 기존 `imageExecutor`를 사용한다.
- 전용 실행기 적용 후 50 VU는 Post 50개와 Image 250개, 100 VU는 Post 100개와 Image 500개가 모두 정상 반영됐다.
- 두 재측정 모두 Copy step/operation이 전부 `COMPLETED`였고 HTTP 실패, `FAILED`, 남은 `PROCESSING`은 0건이었다.
- 채팅방 삭제는 기존 folder 범위를 유지해 `DELETE_FOLDER`로 처리한다.
- Outbox 즉시 발행은 지연 개선 목적으로 추가할 수 있지만, polling relay는 유실 복구 fallback으로 유지해야 한다.
- 다중 서버 Outbox claim은 현재 단일 서버이므로 보류했다. 향후 같은 DB를 공유하는 다중 인스턴스가 생기면 다시 설계해야 한다.
- `COPY_STAGING_TO_FINAL`의 완전 비동기 retry는 `REGISTER_IMAGE_DB` 없이 단독 구현하면 안 된다.
- 직접 업로드는 compensation이 적용됐지만 moderation event 발행 여부는 기존 동작을 유지하고 있으며 별도 확장 대상이다.
- 직접 업로드 중 서버 종료로 `UPLOAD_OBJECT`가 `PROCESSING`에 남는 경우 bytes를 재구성할 수 없어 자동 업로드 재시도는 불가능하다. 안전한 timeout 복구 정책은 별도 설계가 필요하다.
- 일반 Post/Poll의 presigned staging Copy 흐름은 공통 구조로 전환됐지만, 관리자 `MultipartFile` Post 업로드와 크롤러 외부 URL 업로드는 아직 기존 동기 S3 처리 흐름이다.
- 현재 Poll 이미지는 `ImageType.POST`를 공유한다. 이는 기존 동작을 유지한 것이며 별도 type으로 분리한 것이 아니다.
- 개선 전 다중 이미지 구조는 이미지 1장당 prepared statement 10개, Hibernate transaction 5개가 증가한다. 상세 측정 방법은 `docs/agent/image-operation-performance-baseline.md`를 확인한다.
- `100 VU post-only`에서 Hikari pool 10개는 18% 성공, pool 25개는 96% 성공했지만 비동기 operation 정체가 남았다. pool 확대만으로 해결하지 않는다.
- `postImageExecutor` worker 10개는 기존 직접 처리 fallback의 부하 테스트 시작값이며, 전체 재시도 파이프라인의 영구적인 처리량 제한으로 간주하지 않는다.
- Post/Poll 내부 Copy의 동일 `imageExecutor` 재제출과 `join()`은 제거됐다. 바깥 `@Async`는 유지하며 내부 Copy는 순차 실행한다.
- batch 상태 저장 적용 후 DB benchmark는 이미지 5장 기준 `32 statements / 8 transactions`, 20장 기준 `122 statements / 23 transactions`로 감소했다.
- Post/Poll NCP Copy는 DB transaction 밖에서 실행된다. Copy 전 snapshot 조회와 Copy 후 최종 쓰기 transaction만 DB connection을 사용한다.
- transaction 경계 분리 후 DB benchmark는 이미지 5장 `33 statements / 9 transactions`, 20장 `123 statements / 24 transactions`다. 직전보다 transaction이 1개 늘었지만 긴 Copy 구간의 connection 점유를 제거한 것이 핵심이다.
- Copy 도중 같은 owner 이미지가 변경되면 최종 snapshot 검증이 실패하고 새 final object를 compensation한다.
- Post/Poll 이미지 작업은 상위 transaction commit 이후에만 제출된다. rollback 시 작업을 시작하지 않는다.
- Poll의 `afterCommit` 제출은 아직 메모리 callback이므로 commit 직후 서버 종료 시 작업 요청이 유실될 수 있다. RabbitMQ 활성화된 Post 생성·수정은 최초 Outbox를 transaction 안에서 저장한다.
- 최종 구조의 실제 NCP Copy 자체 속도는 동일 Object Storage benchmark로 아직 재측정하지 않았다.
- RabbitMQ 활성화 시 Post 생성은 `ImageOperationPayload`와 최초 Outbox를 Post transaction 안에서 저장한다.
- Post 생성 staging 이미지는 `COPY_STAGING_TO_FINAL → REGISTER_IMAGE_DB → DELETE_STAGING` 전체 재시도 흐름을 사용한다.
- Copy/Register timeout은 retry queue로 복구하며, 재시도 한도 소진 시 final object 보상 삭제를 예약한다.
- 신규 Post 이미지는 `posts/objects/{uuid}.{ext}` final key에 직접 업로드한다.
- 신규 Post 생성·수정은 Post transaction에서 Image DB를 함께 반영한다.
- 신규 흐름에서는 Post Copy/Register/Delete-staging RabbitMQ 파이프라인을 사용하지 않는다.
- 기존 `temp/` key 요청은 전환 기간 동안 기존 Copy 파이프라인 fallback을 사용한다.
- Post 수정 후 제거된 기존 object 삭제는 공통 cleanup Outbox/RabbitMQ 흐름을 유지한다.
- Post용 Presigned URL 발급 시 `image_upload_session`이 `ISSUED`로 저장된다.
- Post/Image 등록 transaction은 session row lock, 소유권 검증, `REGISTERED` 전환을 함께 수행한다.
- 만료 배치는 Object Storage를 순회하지 않고 만료된 `ISSUED` session 최대 200건을 조회한다.
- 미사용 session은 `DELETE_PENDING + Outbox`, 삭제 성공은 `DELETED`, DLQ 전환은 `DELETE_FAILED`로 추적한다.
- `DELETED`는 기본 30일, `REGISTERED`는 기본 90일 후 삭제하며 `DELETE_FAILED`는 자동 삭제하지 않는다.
- 정리 scheduler는 `image.cleanup.rabbit.enabled=true`와 `image.unregistered-post-object-cleanup.enabled=true`가 모두 설정된 경우에만 실행된다.
- Poll은 아직 기존 직접 비동기 처리 경로다.

## 다음 Task 우선순위

1. 현재 미커밋 Post 등록 시 `HeadObject` 검증, `DELETE_FAILED` 재처리, terminal session 정리 변경을 검토하고 커밋한다.
2. 실제 NCP 환경에서 `HeadObject → ISSUED → CLAIMED → REGISTERED` 흐름을 검증한다.
3. 만료 Outbox, 삭제 실패 재처리, terminal session 삭제를 실제 환경에서 확인한다.
4. 기존 `temp/` key fallback 제거 시점을 결정한다.
