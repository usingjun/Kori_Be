# Image Operation 신뢰성 개선 진행 상황

## 현재 기준

- 기준 브랜치: `feat/image-idempotency`
- 마지막 완료 커밋은 `git log -1 --oneline`으로 확인한다.
- 현재 구현 단계: 사용자·채팅방 프로필과 일반 Post/Poll 이미지 생성·수정의 공통 Operation/Outbox 적용 완료
- 작업 전 `AGENTS.md`, `docs/agent/project-context.md`, 인수인계 문서를 확인한다.
- 이 문서는 이미지 생성·수정·삭제 흐름을 공통 `ImageOperation` 구조로 점진 통합하는 작업의 진행 상태를 기록한다.

## 완료된 작업

### 1. 공통 Image Operation 기반

- `ImageOperation`으로 이미지 작업 단위의 상태를 추적한다.
- `ImageOperationStep`으로 작업 내부의 실행 단계를 추적한다.
- `ImageOperationPublishOutbox`로 PostgreSQL commit과 RabbitMQ publish 사이의 메시지 유실 가능성을 낮춘다.
- `ImageOperationConsumedMessage`로 동일 `messageId`의 중복 소비를 방지한다.
- `ImageOperationOutboxRelay`가 pending Outbox를 RabbitMQ에 발행한다.
- `ImageOperationRabbitPublisher`가 publisher confirm을 확인한 뒤 Outbox를 발행 완료 처리한다.
- `ImageOperationRabbitConsumer`가 공통 메시지를 소비하고 retry/DLQ 상태를 관리한다.

### 2. 삭제 및 보상 삭제 안정화

- `COMPENSATE_FINAL_OBJECT`, `DELETE_OBJECT`, `DELETE_FOLDER` step을 공통 Consumer에서 처리한다.
- Copy 성공 후 DB 반영 실패 시 생성된 final object를 `COMPENSATE_FINAL_OBJECT`로 삭제한다.
- 정상 처리 성공 후 old/staging object 삭제를 `DELETE_OBJECT + Outbox`로 예약한다.
- 삭제 step 처리 중 서버가 종료되어 `PROCESSING`에 멈춘 경우 timeout 복구 후 retry/DLQ로 전환한다.
- 보상 삭제 성공 시 operation을 `COMPENSATED`로 구분한다.
- `default/` key는 삭제하지 않는다.

### 3. 기존 cleanup 경로 통합

- 일반 삭제 실패가 기존 `ImageCleanupOperation` 직접 발행 경로 대신 공통 구조를 사용하도록 변경했다.
- 현재 공통 경로:

```text
FailedImageCleanup 저장
→ ImageOperation(CLEANUP_ONLY)
→ ImageOperationStep(DELETE_OBJECT 또는 DELETE_FOLDER)
→ ImageOperationPublishOutbox
→ RabbitMQ
→ ImageOperationRabbitConsumer
→ retry 또는 DLQ
```

- `FailedImageCleanup`과 `FailedImageCleanupScheduler`는 RabbitMQ/Outbox 장애 시 fallback으로 유지한다.
- 기존 Phase 1 전용 entity/table/class는 호환성과 점진 전환을 위해 아직 제거하지 않았다.

### 4. 사용자 프로필 이미지 수정

- staging object Copy를 `COPY_STAGING_TO_FINAL` step으로 추적한다.
- Copy 성공 후 DB 반영 실패 시 final object 보상 삭제를 예약한다.
- DB 반영 성공 후 old/staging object 삭제를 Outbox로 예약한다.
- API 응답, CDN URL, object key 규칙은 유지한다.

### 5. 채팅방 프로필 이미지 수정

- 기존 object를 새 이미지 반영 전에 삭제하지 않는다.
- staging Copy를 `UPDATE_CHAT_ROOM_PROFILE_IMAGE` operation으로 추적한다.
- Copy 성공 후 DB 반영 실패 시 final object 보상 삭제를 예약한다.
- DB 반영 성공 후 old/staging object 삭제를 Outbox로 예약한다.

### 6. 사용자 프로필 이미지 생성·삭제

- `saveUserProfileImage()`의 staging Copy를 `CREATE_USER_PROFILE_IMAGE` operation으로 추적한다.
- Copy 성공 후 DB 반영 실패 시 final object 보상 삭제를 예약한다.
- DB 반영 성공 후 old/staging object 삭제를 Outbox로 예약한다.
- `deleteUserProfileImage()`의 DB row 삭제와 `DELETE_FOLDER + Outbox`를 같은 transaction에서 처리한다.
- RabbitMQ 비활성화 시에도 사용자 folder 삭제는 transaction commit 이후에만 실행한다.

### 7. 채팅방 프로필 이미지 생성

- staging Copy를 `CREATE_CHAT_ROOM_PROFILE_IMAGE` operation으로 추적한다.
- Copy 성공 후 DB 반영 실패 또는 상위 transaction rollback 시 final object 보상 삭제를 예약한다.
- DB 반영 성공 후 staging object 삭제를 Outbox로 예약한다.
- 이미 final key인 요청은 기존처럼 Copy 없이 저장한다.

### 8. 채팅방 프로필 이미지 삭제

- `deleteChatRoomProfileImage()`의 DB row 삭제와 `DELETE_FOLDER + Outbox`를 같은 transaction에서 처리한다.
- 실제 `chatRoom/{chatRoomId}/` folder 삭제는 transaction commit 후 Consumer가 수행한다.
- RabbitMQ 비활성화 fallback도 commit 이후에만 folder를 삭제한다.
- 상위 `ChatRoomService.leaveRoom()` transaction이 rollback되면 DB 삭제와 Outbox가 함께 rollback된다.

### 9. 검증 완료

- 이미지 관련 대상 테스트가 통과했다.
- 최신 `./scripts/agent-check.sh`는 187개 중 18개가 실패했고, 4개는 skip됐다.
- 전체 테스트 실패는 기존 환경 제한인 `ERROR: permission denied to create extension "pgroonga"` 때문이다.
- 이번 이미지 변경으로 확인된 테스트 실패는 없다.

### 10. 사용자 프로필 직접 MultipartFile 업로드

- `uploadUserProfileImage(MultipartFile)`의 직접 `putObject`를 `UPLOAD_USER_PROFILE_IMAGE / UPLOAD_OBJECT`로 추적한다.
- `putObject` 성공 응답의 HTTP 성공 여부와 ETag를 확인한 뒤 DB를 반영한다.
- `putObject` 결과 불명확, DB 실패, 상위 transaction rollback 시 새 object 보상 삭제를 예약한다.
- 기존 이미지 교체 성공 시 기존 object 하나만 `DELETE_OBJECT + Outbox`로 삭제한다.
- `UserAdminService.updateAiUser()`에서 기존 folder 선삭제를 제거해 새 이미지까지 삭제되는 위험을 막는다.
- RabbitMQ 비활성화 시에는 직접 bulk delete와 기존 `FailedImageCleanup` fallback을 사용해 보상한다.

### 11. 일반 Post/Poll 이미지 생성·수정

- `ImageOperationBatchService`가 다중 이미지 Copy operation 추적, rollback compensation, 성공 후 cleanup 예약을 공통 처리한다.
- 일반 Post 생성은 각 staging Copy를 `CREATE_POST_IMAGES`로 추적한다.
- 일반 Post 수정은 각 staging Copy를 `UPDATE_POST_IMAGES`로 추적하고, 제거 이미지와 staging 원본을 `DELETE_OBJECT + Outbox`로 삭제한다.
- Poll upsert는 각 staging Copy를 `UPDATE_POLL_IMAGES`로 추적하고, 제거 이미지와 staging 원본을 `DELETE_OBJECT + Outbox`로 삭제한다.
- Copy 결과가 불명확하게 실패한 경우 해당 destination object도 compensation 대상으로 기록한다.
- 현재 Poll 이미지는 기존 코드 동작대로 `ImageType.POST`를 사용하고 object folder는 `vote/{id}/`를 유지한다.

### 12. 다중 이미지 DB 성능 Baseline

- `ImageOperationDbBenchmarkTest`를 추가해 명시적 실행 시에만 실제 PostgreSQL DB 비용을 측정한다.
- 개선 전 구조는 이미지 1장당 prepared statement 10개, Hibernate transaction 5개가 증가한다.
- `PostImageServiceDbBenchmarkTest`를 추가해 `PostImageServiceImpl.savePostImages()`의 Image 저장, Operation/Outbox, 외부 transaction을 포함한 DB 비용을 측정한다.
- Post 이미지 서비스 benchmark 최초 측정에서 20장은 prepared statement 221개, Hibernate transaction 101개가 발생했다.
- `PostImageObjectStorageBenchmarkTest`를 추가해 실제 NCP Object Storage 병렬 Copy와 PostgreSQL 저장을 함께 측정할 수 있도록 했다.
- 실제 Object Storage benchmark는 명시적으로 활성화할 때만 실행하며 benchmark object와 DB row를 사후 정리한다.
- `8 MiB` JPEG 20장 Copy 동시성 비교에서 동시성 10은 중앙값 311ms로 안정적이었고, 동시성 20은 5회 중 4회가 1초 이상 걸렸다.
- 운영 executor 전체 크기를 변경하기 전에 요청 단위 Copy 동시성 제한과 독립 재측정을 검토한다.
- `scripts/k6/post-image-load-test.js`를 추가해 실제 Presigned URL 발급, NCP 이미지 업로드, Post 생성 API의 다중 사용자 부하를 측정할 수 있도록 했다.
- Post 도배 제한 때문에 k6 VU마다 별도 Access Token을 사용하고 사용자당 iteration은 최대 3회로 제한한다.
- 실제 `9.6MB` JPEG 5장을 사용하는 `1 VU × 1 iteration` k6 baseline이 성공했다.
- 단일 사용자 기준 전체 흐름은 2.72초, 이미지 한 장 NCP 업로드 평균은 1.33초, Post 생성 API 응답은 18.78ms였다.
- `5 VU × 이미지 5장`은 100% 성공했고 전체 사용자 흐름 p95는 5.61초였다.
- `50 VU × 이미지 5장`은 100% 성공했지만 전체 사용자 흐름 p95 49.1초, NCP 업로드 p95 46.05초로 크게 지연됐다.
- `100 VU × 이미지 5장`은 독립 재실행을 포함해 두 번 모두 NCP Presigned PUT 대부분이 약 60초 후 timeout 됐으며 Post API는 호출되지 않았다.
- 현재 로컬 환경과 NCP bucket 기준으로 동시 PUT 250개는 느리지만 성공하고, 동시 PUT 500개는 안정적으로 처리하지 못하는 경계를 확인했다.
- 측정 방법과 baseline 결과는 `docs/agent/image-operation-performance-baseline.md`에 기록했다.
- Presign/NCP PUT만 측정하는 `post-image-upload-only-test.js`와 미리 준비된 staging key로 Post API만 측정하는 `post-image-post-only-test.js`를 추가했다.
- `prepare-post-image-staging.sh`가 post-only 측정용 사용자별 고유 staging object와 manifest를 준비한다.
- 병목 분리 테스트의 실제 실행 결과는 아직 측정하지 않았다.
- upload-only `100 VU × 이미지 5장`에서 `UPLOAD_BATCH_SIZE=5`는 NCP PUT 497/500개가 timeout 됐고, `UPLOAD_BATCH_SIZE=1`은 500/500개가 성공했다.
- `UPLOAD_BATCH_SIZE=2`도 500/500개가 성공했지만 업로드 p95는 44.79초로 `UPLOAD_BATCH_SIZE=1`의 20.74초보다 크게 악화됐다.
- 동시성을 높여도 전체 처리량이 약 51~55MB/s에서 증가하지 않아 단일 부하 발생기 또는 현재 네트워크 경로의 업로드 처리량 포화가 강한 병목 후보다.
- post-only 측정은 남아 있다.
- post-only `100 VU × 이미지 5장`에서 Post 생성은 18/100건 성공했고 82건은 약 30.15초 후 HTTP 500으로 실패했다.
- 동일 `imageExecutor`의 외부 `@Async` 작업과 내부 `CompletableFuture` Copy 중첩, `CallerRunsPolicy`, Hikari 기본 pool 10개, 다수 `REQUIRES_NEW` transaction이 결합된 DB connection pool 고갈이 가장 유력한 병목 후보다.
- 직접 Hikari timeout 예외는 서버 로그에서 추가 확인해야 한다.
- Hikari pool 25, connection timeout 3초로 동일 post-only 테스트를 재실행하자 성공률이 `18% → 96%`로 증가하고 실패시간이 약 `30초 → 3초`로 변경됐다.
- 설정 변경에 따른 결과 차이로 DB connection pool 고갈이 HTTP 500의 직접 원인이라는 강한 근거를 확보했다.
- 테스트 종료 10초 후에도 operation과 Copy step의 `PENDING/PROCESSING` 상태가 변하지 않아 동일 `imageExecutor` 중첩 사용에 따른 비동기 작업 정체가 남아 있다.
- Post/Poll 이미지 처리에서 내부 `CompletableFuture.supplyAsync(..., imageExecutor)`와 `join()`을 제거했다.
- 바깥 `@Async("imageExecutor")`는 유지하고, 하나의 비동기 작업 thread가 요청 안의 이미지를 순차 Copy하도록 변경했다.
- 중첩 executor 제거 후 Post/Poll 안정성 대상 테스트와 compile이 통과했다.
- 중첩 executor 제거 후 DB benchmark는 이미지 5장 기준 56 statements, 26 transactions로 기존과 동일했다. 이번 변경은 executor 정체 제거이며 DB 접근량 개선은 다음 단계다.
- 실제 PostgreSQL 검증 중 assigned UUID entity의 `@Version` 수동 초기화가 신규 저장을 방해하는 문제를 발견해 제거했다.

### 13. Post/Poll Copy 상태 저장 Batch 처리

- 이미지별 `ImageOperation`과 `COPY_STAGING_TO_FINAL` step 추적 의미는 유지했다.
- `ImageOperationBatchTransactionService`가 한 요청의 operation/step 준비 상태를 한 transaction에서 `saveAll`한다.
- NCP Copy는 한 비동기 worker에서 순차 실행하고, 전체 성공 결과는 한 transaction에서 step 완료 상태로 반영한다.
- 중간 Copy 실패 시 이전 성공 step, 실패 step, 미실행 step 상태를 한 transaction에서 정리한다.
- Copy 실패 상태 기록 DB transaction이 실패해도, 성공했거나 성공 여부가 불명확한 destination object의 compensation은 계속 예약한다.
- 변경 후 `PostImageServiceDbBenchmarkTest`에서 이미지 5장은 `56 statements / 26 transactions`에서 `32 statements / 8 transactions`로 감소했다.
- 이미지 20장은 `221 statements / 101 transactions`에서 `122 statements / 23 transactions`로 감소했다.
- 바깥 Post/Poll `@Transactional`은 아직 NCP Copy 동안 유지되므로 DB connection 장기 점유 위험은 남아 있다.

### 14. Post/Poll NCP Copy와 DB Transaction 경계 분리

- `PostImageServiceImpl.savePostImages()`, `updatePostImages()`, `MainContentImageServiceImpl.upsertPollImages()`의 메서드 전체 `@Transactional`을 제거했다.
- `ImagePersistenceTransactionService`가 Copy 전 짧은 조회 transaction과 Copy 후 최종 쓰기 transaction을 담당한다.
- NCP Copy는 두 transaction 사이에서 실행되어 DB connection을 점유하지 않는다.
- 최종 쓰기 transaction은 제거 이미지 DB 반영, 생존 이미지 순서 변경, 새 Image 저장, moderation event, cleanup Outbox 저장을 함께 처리한다.
- Copy 후 최종 저장 전에 현재 이미지 집합이 조회 snapshot과 달라졌으면 stale snapshot으로 판단하고 저장하지 않으며 final object를 compensation한다.
- 변경 후 DB benchmark는 이미지 5장 `33 statements / 9 transactions`, 20장 `123 statements / 24 transactions`다.
- 직전 batch 구조보다 조회 transaction과 snapshot 검증 query가 각각 추가됐지만, 긴 Copy 구간을 감싸던 transaction은 제거됐다.

## 현재 진행 중인 작업

- Post/Poll의 NCP Copy와 Image DB 저장 transaction 경계를 분리했다.
- 다음 작업은 동일 `100 VU post-only` 테스트로 connection pool 고갈 개선 여부를 재측정하는 것이다.

## 남은 작업

### 가까운 범위

- 동일 조건의 `100 VU post-only` 재측정
- transaction 경계 분리 후 동일 조건의 `100 VU post-only` 재측정
- 요청 단위 `ImageOperation` 1개와 이미지별 `ImageOperationStep` 구조 검토
- 2코어·8GB 환경을 고려한 제한된 Consumer/Copy 동시성 설계
- 현재 polling 기반 Outbox 처리 지연 측정
- 측정 결과에 따른 Outbox 발행 지연 개선 여부 결정
- 직접 업로드 처리 중 서버 종료로 `UPLOAD_OBJECT`가 `PROCESSING`에 남는 경우의 안전한 복구 정책 검토
- Post/Poll 요청당 Copy 동시성 제한 적용 여부 결정
- 클라이언트 이미지 업로드 동시성 제한 또는 분산 부하 환경의 추가 검증 여부 결정

### 다음 확장 범위

- 관리자 Post `MultipartFile` 직접 업로드와 크롤러 외부 URL 업로드의 operation/compensation 적용
- 그 외 이미지 소유자 흐름을 동일 원칙으로 점진 적용
- Copy retry와 `REGISTER_IMAGE_DB`를 하나의 연속 파이프라인으로 설계
- moderation event의 Outbox 적용 여부 결정

### 장기 범위

- Phase 1 전용 `ImageCleanupOperation`, `ImageCleanupAuditLog`, `ImageCleanupConsumedMessage`, 전용 Publisher/Consumer 제거 여부 결정
- RabbitMQ 실제 운영 환경에서 publisher confirm, retry TTL, DLQ routing, broker 장애 복구 검증
- DLQ 조회와 replay 도구
- operation/audit 조회 및 metrics
- 다중 서버 도입 시 Outbox 동시 claim과 `FOR UPDATE SKIP LOCKED` 적용

## 다음 우선순위 작업

1. 서버를 재시작하고 동일 `100 VU post-only` 테스트로 중첩 executor 제거, batch 상태 저장, transaction 경계 분리 효과를 함께 측정한다.
2. NCP Copy 전용 제한 병렬 처리 또는 `S3AsyncClient` 적용 여부를 검토한다.
3. 요청 단위 `ImageOperation` 1개와 이미지별 `ImageOperationStep` 구조의 추가 축소 효과를 검토한다.
4. 2코어·8GB 서버 기준 Consumer/Copy 동시성 및 Hikari pool 크기를 측정 결과로 결정한다.

생성·수정·삭제 모두 적용 대상이다. 다만 한 번에 전체 흐름을 변경하지 않고, 각 흐름별 실패 시나리오와 테스트를 확인하면서 점진 적용한다.
