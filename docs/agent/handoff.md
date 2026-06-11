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
```

실패 시 의미:

- Copy 실패: Image row를 저장하지 않고 staging object를 유지한다.
- Copy 성공 후 DB flush 실패: 생성된 final object에 compensation 삭제를 예약한다.
- 상위 채팅방 생성 transaction rollback: 생성된 final object에 compensation 삭제를 예약한다.
- DB 성공: staging object 삭제를 공통 Outbox/Consumer가 처리한다.
- 사용자 삭제 transaction rollback: DB 삭제와 folder cleanup Outbox가 함께 rollback된다.
- 채팅방 삭제 transaction rollback: DB 삭제와 folder cleanup Outbox가 함께 rollback된다.

`ProfileImageServiceImplTest`, `ImageOperationRecoveryServiceTest`, 이미지 관련 대상 테스트를 통과했다.
`./scripts/agent-check.sh`는 156개 중 18개가 기존 `pgroonga` extension 생성 권한 문제로 실패했으며, 이번 이미지 변경으로 확인된 실패는 없다.

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

1. `uploadUserProfileImage(MultipartFile)` 직접 `putObject` 흐름과 호출 transaction을 확인한다.
2. `putObject` 성공 후 DB 저장 실패 시 final object compensation 방식을 설계한다.
3. 직접 업로드 성공 후 moderation event와 operation 완료 경계를 확인한다.
4. 구현 전 기존 API와 admin AI user 업로드 호출에 미치는 영향을 정리한다.

### 채팅방 프로필 생성 목표

```text
staging Copy
→ Image DB 저장
→ 성공 시 staging cleanup Outbox
→ DB 실패 시 final object compensation
```

기존 API 응답과 key 생성 규칙은 유지해야 한다.

### 이후 확장

- Post/Poll 이미지 생성·수정·삭제
- 그 외 image owner 흐름
- Outbox 발행 지연 개선
- Copy retry + `REGISTER_IMAGE_DB` 연속 파이프라인
- moderation event Outbox
- 실제 RabbitMQ 환경 검증
- 운영 조회/replay

## 다음 Task에서 가장 먼저 해야 할 일

다음 Codex는 새 구현을 시작하기 전에 아래를 먼저 수행해야 한다.

1. `git status --short`와 `git log -1 --oneline`으로 현재 상태를 확인한다.
2. `uploadUserProfileImage(MultipartFile)`와 호출 서비스의 transaction 경계를 확인한다.
3. 직접 업로드 보상 정책을 구현 전에 짧게 보고한다.
4. 구현 후 이미지 대상 테스트와 `git diff --check`를 실행한다.

새 구현의 첫 대상은 `uploadUserProfileImage(MultipartFile)` 직접 업로드 보상 처리다.

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

전체 테스트에서 `pgroonga` extension 생성 권한으로 실패하는 18개 테스트는 현재 환경 제한이다. 이미지 대상 테스트 결과와 분리해 보고해야 한다.

## 현재 판단이 필요한 사항

- 채팅방 삭제는 기존 folder 범위를 유지해 `DELETE_FOLDER`로 처리한다.
- Outbox 즉시 발행은 지연 개선 목적으로 추가할 수 있지만, polling relay는 유실 복구 fallback으로 유지해야 한다.
- 다중 서버 Outbox claim은 현재 단일 서버이므로 보류했다. 향후 같은 DB를 공유하는 다중 인스턴스가 생기면 다시 설계해야 한다.
- `COPY_STAGING_TO_FINAL`의 완전 비동기 retry는 `REGISTER_IMAGE_DB` 없이 단독 구현하면 안 된다.
- `uploadUserProfileImage(MultipartFile)`는 직접 `putObject` 후 DB 저장하는 별도 흐름이며 아직 compensation이 적용되지 않았다.
