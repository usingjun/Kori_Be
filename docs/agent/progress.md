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
- 최신 `./scripts/agent-check.sh`는 191개 중 18개가 실패했고, 4개는 skip됐다.
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
- upload-only와 post-only 병목 분리 테스트를 완료했다.
- upload-only `100 VU × 이미지 5장`에서 `UPLOAD_BATCH_SIZE=5`는 NCP PUT 497/500개가 timeout 됐고, `UPLOAD_BATCH_SIZE=1`은 500/500개가 성공했다.
- `UPLOAD_BATCH_SIZE=2`도 500/500개가 성공했지만 업로드 p95는 44.79초로 `UPLOAD_BATCH_SIZE=1`의 20.74초보다 크게 악화됐다.
- 동시성을 높여도 전체 처리량이 약 51~55MB/s에서 증가하지 않아 단일 부하 발생기 또는 현재 네트워크 경로의 업로드 처리량 포화가 강한 병목 후보다.
- post-only 측정을 완료했고, 최종 구조에서 `50 VU`, `100 VU` 모두 정상 완료를 확인했다.
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

### 15. Post/Poll 이미지 비동기 작업의 After Commit 제출

- `ImageService`와 `ImageServiceImpl`의 `savePostImages()`, `updatePostImages()`, `upsertPollImages()`에서 불필요한 `@Transactional`을 제거했다.
- 상위 Post/Poll transaction이 활성화된 경우 실제 `@Async` 이미지 작업은 `afterCommit()`에서만 제출한다.
- 상위 transaction이 rollback되면 이미지 Copy와 DB 저장 작업을 시작하지 않는다.
- transaction 없이 직접 호출되는 경우에는 기존처럼 즉시 비동기 작업을 제출한다.
- 예약 후 호출자가 원본 List를 변경해도 작업 내용이 바뀌지 않도록 전달 목록을 복사한다.
- 현재 방식은 메모리 기반 `afterCommit` callback이므로 commit 직후 서버가 종료되면 이미지 작업 요청이 유실될 수 있다.

### 16. Post 생성 이미지 전체 재시도 파이프라인

- RabbitMQ 활성화 시 Post 생성 transaction 안에서 `ImageOperation`, `ImageOperationPayload`, 최초 step, Outbox를 함께 저장한다.
- staging 이미지는 `COPY_STAGING_TO_FINAL → REGISTER_IMAGE_DB → DELETE_STAGING` 순서로 처리한다.
- Copy와 Image DB 등록 실패는 기존 retry queue와 DLQ 흐름을 사용한다.
- Copy와 DB 등록 재시도 한도를 소진하면 final object 보상 삭제를 예약한다.
- 각 단계 완료와 다음 단계 step/Outbox 저장은 같은 짧은 transaction에서 처리한다.
- NCP Copy는 DB transaction 밖에서 실행한다.
- `REGISTER_IMAGE_DB`는 동일 `ImageType + relatedId + finalUrl`이 이미 존재하면 성공으로 간주한다.
- Post가 삭제된 뒤 늦게 도착한 DB 등록 요청은 실패 처리한다.
- Consumer 실행 중 서버 종료로 Copy 또는 DB 등록 step이 오래 `PROCESSING`에 남으면 timeout scheduler가 retry queue로 복구한다.
- RabbitMQ 비활성화 시에는 기존 `afterCommit + postImageExecutor` 경로를 유지한다.
- 기존 `temp/` Post 생성 요청만 전체 재시도 Copy 파이프라인을 사용한다. 신규 UUID final key 요청은 Post transaction에서 Image DB를 직접 반영한다.
- 이미지 전체 회귀 테스트는 통과했다.
- 최신 `./scripts/agent-check.sh`는 223개 중 18개가 실패했고 4개는 skip됐다. 실패 원인은 기존 환경 제한인 `ERROR: permission denied to create extension "pgroonga"`다.

### 17. Post UUID final key 직접 업로드

- `ImageType.POST` Presigned URL은 `posts/objects/{uuid}.{ext}` key를 발급한다.
- 새 final key를 사용하는 Post 생성은 Post transaction 안에서 Image DB를 함께 저장한다.
- 새 final key를 사용하는 Post 수정은 같은 transaction에서 새 Image 등록, 제거 Image 삭제, 순서 변경을 적용한다.
- 제거된 기존 object는 기존 `DELETE_OBJECT + Outbox + RabbitMQ retry/DLQ`로 삭제한다.
- 신규 Post 흐름에서는 `COPY_STAGING_TO_FINAL`, `REGISTER_IMAGE_DB`, `DELETE_STAGING`, Copy 보상 삭제가 필요하지 않다.
- 전환 기간 동안 기존 `temp/` key는 기존 Copy 재시도 파이프라인으로 처리한다.

### 18. `image_upload_session` 기반 미등록 UUID object 정리

- Post용 Presigned URL 발급 시 `image_upload_session`을 `ISSUED` 상태로 저장한다.
- Post/Image 저장 transaction에서 session row를 잠그고 소유권을 검증한 뒤 `REGISTERED`로 함께 commit한다.
- 만료 배치는 Object Storage 전체를 조회하지 않고 `(status, expires_at)` index로 만료된 `ISSUED` session 최대 200건만 조회한다.
- Poll 등 다른 흐름에서 이미 `Image.url`로 사용 중인 key는 `REGISTERED`로 정리한다.
- Poll 이미지 저장 transaction도 존재하는 session row를 잠그고 `REGISTERED`로 전환하며, session이 없는 기존 staging Copy 결과는 허용한다.
- 미사용 session은 `DELETE_PENDING + CLEANUP_ONLY + DELETE_OBJECT + Outbox`를 같은 transaction에서 저장한다.
- Consumer 삭제 성공 시 `DELETED`, retry 한도 초과 시 `DELETE_FAILED`로 갱신한다.
- 실제 삭제 직전 `Image.url` 재확인도 유지한다.

### 19. Post 등록 시 직접 업로드 검증 및 session 유지보수

- 별도 업로드 완료 API를 두지 않고 Post 등록 요청 자체를 업로드 완료 신호로 사용한다.
- 신규 UUID final key 등록 전 `HeadObject`로 실제 object 존재를 확인한다.
- `HeadObject` 검증은 `Propagation.NOT_SUPPORTED`로 상위 Post transaction을 잠시 중단한 상태에서 수행한다.
- 검증 성공 후 Post transaction을 재개하고 session 소유권 확인과 `ISSUED → CLAIMED → REGISTERED` 전이를 수행한다.
- 만료 정리는 `ISSUED`를 대상으로 수행한다.
- `DELETE_FAILED` session은 기본 1시간 대기 후 새로운 `DELETE_OBJECT + Outbox`로 재처리한다.
- 이전 삭제 step이 `DLQ`인 경우 새 cleanup operation을 생성하고, 활성 step이 있으면 중복 생성하지 않는다.
- `DELETED` session은 기본 30일, `REGISTERED` session은 기본 90일 보존 후 DB에서 삭제한다.
- terminal session 삭제 배치는 RabbitMQ 활성화 여부와 무관하게 실행된다.
- 현재 운영 중인 서비스가 아니므로 session 없는 기존 Presigned URL을 임시 허용하는 단계적 활성화 기능은 구현하지 않았다.
- k6 흐름은 기존처럼 PUT 성공 후 바로 Post 등록을 호출한다.
- 최신 `./scripts/agent-check.sh`는 229개 중 18개 실패, 4개 skip이며, 실패 원인은 모두 기존 `pgroonga` extension 생성 권한 문제다.

## 현재 진행 중인 작업

- 신규 Post/Poll 이미지는 UUID final key 직접 업로드 구조로 전환했다.
- Post 등록 시 `HeadObject` 검증 후 `ISSUED → CLAIMED → REGISTERED` session 상태 추적을 구현했다.
- 미등록 object 정리, `DELETE_FAILED` 재처리, terminal session 정리 배치를 구현했다.
- 이미지 전체 회귀 테스트가 통과했다.
- 실제 NCP 환경에서 Post 등록 시 `HeadObject`, 만료 정리, 삭제 실패 재처리 검증은 남아 있다.
- staging Copy 구조에서 UUID final key 직접 업로드 구조로 전환한 전후 비교는 `docs/agent/post-image-flow-before-after.md`에 기록한다.

### 최초 구조와 최종 구조 성능 비교

DB benchmark의 최초 구조와 최종 구조 비교:

| 이미지 수 | 최초 statements / transactions | 최종 statements / transactions |
| ---: | ---: | ---: |
| 1 | `12 / 6` | `9 / 5` |
| 5 | `56 / 26` | `33 / 9` |
| 10 | `111 / 51` | `63 / 14` |
| 20 | `221 / 101` | `123 / 24` |

- 이미지 5장 기준 statements 약 41%, transactions 약 65% 감소
- 이미지 20장 기준 statements 약 44%, transactions 약 76% 감소

동일 `100 VU × 이미지 5장 post-only` 최초 구조와 최종 구조 비교:

| 항목 | 최초 구조 | 최종 구조 |
| --- | ---: | ---: |
| API 성공 | 18/100 | 100/100 |
| API 실패 | 82/100 | 0/100 |
| 최종 평균 / p95 | 비교 불가 | `120.60ms / 132.44ms` |
| Image DB 반영 | 정상 완료 불가 | 500/500 |
| 남은 `PROCESSING` | 발생 | 0 |

- 최종 `50 VU × 이미지 5장`도 API 50/50, Image 250/250, step/operation 250/250가 모두 완료됐다.
- 최종 구조의 실제 NCP Copy 자체 속도는 동일 Object Storage benchmark로 아직 재측정하지 않았다.

## 남은 작업

### 가까운 범위

- 실제 NCP 환경에서 `HeadObject → ISSUED → CLAIMED → REGISTERED` 흐름 검증
- 실제 object가 없는 final key의 Post 등록 거절 검증
- 만료된 `ISSUED` session의 삭제 Outbox와 `DELETE_FAILED` 재처리 검증
- terminal session 보존기간 및 정리 배치 검증
- Poll이 공유하는 `ImageType.POST` 직접 업로드 흐름 검증
- 현재 polling 기반 Outbox 처리 지연 측정
- 측정 결과에 따른 Outbox 발행 지연 개선 여부 결정
- 직접 업로드 처리 중 서버 종료로 `UPLOAD_OBJECT`가 `PROCESSING`에 남는 경우의 안전한 복구 정책 검토
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

1. UUID final key 직접 업로드와 Post/Image 동일 transaction을 실제 환경에서 검증한다.
2. 미등록 UUID object 정리 배치를 실제 NCP 환경에서 검증한다.
3. Poll이 공유하는 `ImageType.POST` final key 흐름을 검증한다.
4. 최종 구조의 실제 NCP Copy benchmark와 Outbox 발행 지연을 측정한다.
5. 결과에 따라 Outbox 즉시 발행 신호 적용 여부를 결정한다.

생성·수정·삭제 모두 적용 대상이다. 다만 한 번에 전체 흐름을 변경하지 않고, 각 흐름별 실패 시나리오와 테스트를 확인하면서 점진 적용한다.

## 현재 구조 개선 체크

| 항목 | 상태 | 현재 결과 |
| --- | --- | --- |
| Post/Poll 공통 Operation/Outbox 적용 | 완료 | Copy 추적, compensation, cleanup retry/DLQ 적용 |
| 동일 `imageExecutor` 중첩 제거 | 완료 | 바깥 `@Async` 유지, 내부 Copy 순차 실행 |
| operation/step 상태 Batch 저장 | 완료 | 5장 기준 transaction `26 → 8` |
| NCP Copy와 DB transaction 경계 분리 | 완료 | Copy 실행 중 활성 DB transaction 없음 |
| 상위 Post/Poll commit 이후 이미지 작업 제출 | 완료 | rollback 시 이미지 작업 미실행 |
| stale snapshot 검증 | 완료 | Copy 중 동시 수정 발생 시 최종 DB 반영 거부 및 compensation |
| 동일 `50/100 VU post-only` 재측정 | 완료 | 최종 검증에서 API 100% 성공, Image `250/250`, `500/500` 반영 |
| 신규 Copy `PROCESSING` 방치 방지 | 완료 | `completeCopies()` 실패 시 전체 plan 실패 기록 및 compensation |
| Post/Poll 이미지 동시 실행 제한 | 완료 | `postImageExecutor` 고정 worker 10개, queue 200개 |
| Post/Poll Copy transaction 잔여 제거 | 완료 | 인터페이스의 불필요한 `@Transactional` 제거 |
| 동시 실행 제한 후 `50/100 VU post-only` 검증 | 완료 | 각각 Image `250/250`, `500/500`, 실패 및 잔여 `PROCESSING` 0 |
| 기존 Copy `PROCESSING` 방치 복구 | 미완료 | 기존 315개에 대한 timeout 복구 필요 |
| Copy 제한 병렬 처리 | 검토 대기 | NCP `CopyAll` 미제공, 필요 시 동시성 5~10 검토 |
| 전체 비동기 작업 요청 durable Outbox | 검토 대기 | 현재 `afterCommit` callback 유실 가능성 존재 |
| 기존 temp Post 생성 fallback | 완료 | `COPY_STAGING_TO_FINAL → REGISTER_IMAGE_DB → DELETE_STAGING` |
| Copy/Register timeout 복구 | 완료 | 오래된 `PROCESSING`을 retry/DLQ로 전환 |
| Post UUID final key 직접 업로드 | 완료 | Copy 제거, Post/Image 동일 transaction, 기존 object cleanup Outbox |
| 미등록 UUID object 정리 배치 | 완료 | `image_upload_session` 만료 조회, Outbox 예약, 삭제 결과 추적 |
| 실제 업로드 완료 확인 | 완료 | Post 등록 시 transaction 밖에서 `HeadObject` 검증 |
| `DELETE_FAILED` 재처리 | 완료 | 기본 1시간 후 새 cleanup operation과 Outbox 생성 |
| terminal session 정리 | 완료 | `DELETED` 30일, `REGISTERED` 90일 후 삭제 |

## 구조 개선 커밋 기준

| 커밋 | 내용 |
| --- | --- |
| `7d5ab039` | Post/Poll 이미지 Operation/Outbox 및 compensation 안정화 |
| `66d92a68` | 동일 `imageExecutor` 중첩 비동기 제거 |
| `3421eeb6` | 이미지 operation/step 상태 Batch 저장 |
| `abbfbce5` | NCP Copy와 Image DB transaction 경계 분리 |
| `b8022509` | 상위 transaction commit 이후 이미지 비동기 작업 제출 |
