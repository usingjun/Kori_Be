# Image Operation 신뢰성 개선 진행 상황

## 현재 기준

- 기준 브랜치: `feat/image-idempotency`
- 마지막 완료 커밋: `1e0753d0 feat: unify image cleanup with operation outbox`
- 현재 미커밋 작업: 채팅방 프로필 이미지 수정 흐름의 Copy 추적, 보상 삭제, cleanup Outbox 적용
- `AGENTS.md`는 현재 저장소와 상위 경로에서 확인되지 않았다.
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

### 5. 검증 완료

- 이미지 관련 대상 테스트 70개가 통과했다.
- 전체 테스트는 141개 중 18개가 실패했다.
- 전체 테스트 실패는 기존 환경 제한인 `ERROR: permission denied to create extension "pgroonga"` 때문이다.
- 이번 이미지 변경으로 확인된 테스트 실패는 없다.

## 현재 진행 중인 작업

### 채팅방 프로필 이미지 수정 안정화

현재 다음 두 파일이 미커밋 상태다.

- `src/main/java/core/global/entity/image/service/impl/ProfileImageServiceImpl.java`
- `src/test/java/core/global/entity/image/service/impl/ProfileImageServiceImplTest.java`

적용한 흐름:

```text
staging 검증
→ Copy operation/step 생성
→ Copy 실행
→ Image DB URL 변경 및 flush
→ old/staging DELETE_OBJECT step + Outbox 저장
```

실패 처리:

- Copy 실패: 기존 DB 이미지와 기존 object를 유지한다.
- Copy 성공 후 DB flush 실패: 새 final object의 보상 삭제를 예약한다.
- DB 성공: old/staging object를 직접 삭제하지 않고 Outbox로 비동기 삭제한다.

현재 구현과 테스트는 완료됐지만 아직 커밋하지 않았다.

## 남은 작업

### 가까운 범위

- 현재 채팅방 프로필 이미지 수정 변경사항 검토 후 커밋
- 채팅방 프로필 이미지 생성 흐름에 Copy 추적과 DB 실패 보상 적용
- 채팅방 프로필 이미지 삭제 흐름에 DB 변경과 `DELETE_FOLDER` 또는 필요한 삭제 step의 Outbox 저장 적용
- Outbox 발행 지연 개선: transaction commit 직후 즉시 발행을 시도하고 현재 polling relay는 fallback으로 유지

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

1. 현재 미커밋 채팅방 프로필 이미지 수정 변경사항을 다시 검토하고 커밋한다.
2. 채팅방 프로필 이미지 **생성**에 Copy 성공 후 DB 실패 보상을 적용한다.
3. 채팅방 프로필 이미지 **삭제**에 DB 변경과 삭제 Outbox를 같은 transaction으로 저장한다.
4. Outbox 발행 지연을 줄이되 polling relay를 fallback으로 유지한다.
5. 이후 Post/Poll의 생성·수정·삭제 흐름으로 확장한다.

생성·수정·삭제 모두 적용 대상이다. 다만 한 번에 전체 흐름을 변경하지 않고, 각 흐름별 실패 시나리오와 테스트를 확인하면서 점진 적용한다.
