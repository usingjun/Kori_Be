# Image Operation 신뢰성 개선 진행 상황

## 현재 기준

- 기준 브랜치: `feat/image-idempotency`
- 마지막 완료 커밋은 `git log -1 --oneline`으로 확인한다.
- 현재 구현 단계: 사용자·채팅방 프로필 생성·수정·삭제 및 직접 MultipartFile 업로드 안정화 완료
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
- `./scripts/agent-check.sh`는 164개 중 18개가 실패했다.
- 전체 테스트 실패는 기존 환경 제한인 `ERROR: permission denied to create extension "pgroonga"` 때문이다.
- 이번 이미지 변경으로 확인된 테스트 실패는 없다.

### 10. 사용자 프로필 직접 MultipartFile 업로드

- `uploadUserProfileImage(MultipartFile)`의 직접 `putObject`를 `UPLOAD_USER_PROFILE_IMAGE / UPLOAD_OBJECT`로 추적한다.
- `putObject` 성공 응답의 HTTP 성공 여부와 ETag를 확인한 뒤 DB를 반영한다.
- `putObject` 결과 불명확, DB 실패, 상위 transaction rollback 시 새 object 보상 삭제를 예약한다.
- 기존 이미지 교체 성공 시 기존 object 하나만 `DELETE_OBJECT + Outbox`로 삭제한다.
- `UserAdminService.updateAiUser()`에서 기존 folder 선삭제를 제거해 새 이미지까지 삭제되는 위험을 막는다.
- RabbitMQ 비활성화 시에는 직접 bulk delete와 기존 `FailedImageCleanup` fallback을 사용해 보상한다.

## 현재 진행 중인 작업

- 없음. 다음 구현 대상은 Outbox 발행 지연 개선이다.

## 남은 작업

### 가까운 범위

- Outbox 발행 지연 개선: transaction commit 직후 즉시 발행을 시도하고 현재 polling relay는 fallback으로 유지
- 직접 업로드 처리 중 서버 종료로 `UPLOAD_OBJECT`가 `PROCESSING`에 남는 경우의 안전한 복구 정책 검토

### 다음 확장 범위

- `PostImageServiceImpl`의 생성·수정·삭제 cleanup을 공통 구조로 전환
- Poll 이미지 생성·수정·삭제 cleanup을 공통 구조로 전환
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

1. Outbox 발행 지연을 줄이되 polling relay를 fallback으로 유지한다.
2. 이후 Post/Poll의 생성·수정·삭제 흐름으로 확장한다.

생성·수정·삭제 모두 적용 대상이다. 다만 한 번에 전체 흐름을 변경하지 않고, 각 흐름별 실패 시나리오와 테스트를 확인하면서 점진 적용한다.
