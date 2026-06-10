# Image Operation 설계 결정 기록

## 1. RabbitMQ와 PostgreSQL의 책임을 분리한다

### 결정

- RabbitMQ는 비동기 전달, retry routing, DLQ 격리를 담당한다.
- PostgreSQL은 operation/step 상태, Outbox, 중복 소비 기록의 기준 저장소다.
- S3/NCP Object Storage는 실제 image object만 관리한다.

### 이유

RabbitMQ message는 전달 도구이며 장기 상태 추적에 적합하지 않다. 장애 복구와 감사가 가능하려면 현재 작업 상태를 PostgreSQL에서 조회할 수 있어야 한다.

### 채택하지 않은 대안

- RabbitMQ message만으로 작업 상태 관리
- 기존 `FailedImageCleanup` table만으로 모든 이미지 작업 관리

### 변경 시 주의

Consumer 성공 여부는 RabbitMQ ack 여부만으로 판단하지 말고, PostgreSQL의 step 상태와 함께 판단해야 한다.

## 2. DB 변경과 RabbitMQ 발행 사이에 Outbox를 사용한다

### 결정

domain transaction 안에서 필요한 `ImageOperationStep`과 `ImageOperationPublishOutbox`를 함께 저장한다. RabbitMQ 발행은 commit 이후 `ImageOperationOutboxRelay`가 수행한다.

### 이유

PostgreSQL commit과 RabbitMQ publish는 단일 transaction이 아니다. DB에는 변경됐지만 message가 발행되지 않는 유실 구간을 줄이기 위해 Outbox가 필요하다.

### 채택하지 않은 대안

- domain service에서 RabbitMQ에 직접 발행
- RabbitMQ publish 실패를 로그만 남기고 종료
- XA/distributed transaction으로 PostgreSQL과 RabbitMQ를 묶기

### 변경 시 주의

- Outbox row와 해당 step은 반드시 같은 transaction에서 저장해야 한다.
- 발행 속도를 높이더라도 polling relay를 제거하지 말고 fallback으로 유지해야 한다.
- 현재는 단일 서버이므로 다중 서버 claim은 미구현이다. 다중 인스턴스가 같은 DB를 공유하면 중복 발행 가능성이 생긴다.

## 3. delivery는 at-least-once로 보고 Consumer를 idempotent하게 만든다

### 결정

- `messageId`를 `ImageOperationConsumedMessage`에 기록한다.
- 이미 완료되거나 DLQ 상태인 step은 다시 실행하지 않는다.
- 삭제 작업은 동일 key를 여러 번 삭제해도 깨지지 않는 멱등성을 활용한다.

### 이유

RabbitMQ redelivery, publisher retry, timeout 복구 때문에 동일 작업이 여러 번 전달될 수 있다. exactly-once 전달을 가정하면 장애 시 정합성이 깨진다.

### 채택하지 않은 대안

- RabbitMQ가 한 번만 전달한다고 가정
- Consumer 메모리에서만 중복 여부 추적

### 변경 시 주의

새 step type을 추가할 때는 해당 작업의 멱등성 기준을 먼저 정의해야 한다. 특히 `REGISTER_IMAGE_DB`와 moderation event는 단순 삭제보다 중복 실행 위험이 크다.

## 4. S3와 PostgreSQL은 eventual consistency와 compensation으로 관리한다

### 결정

- Copy는 DB transaction과 분리된 외부 side effect로 취급한다.
- Copy 성공 후 DB 반영 실패 시 `COMPENSATE_FINAL_OBJECT`를 생성해 final object를 삭제한다.
- DB 반영 성공 후 old/staging 삭제는 `DELETE_OBJECT + Outbox`로 처리한다.

### 이유

S3/NCP Object Storage와 PostgreSQL은 하나의 transaction으로 묶을 수 없다. 부분 성공을 없애는 대신, 부분 성공을 추적하고 복구하는 구조가 필요하다.

### 채택하지 않은 대안

- DB 실패 시 동기적으로 final object를 즉시 삭제하고 끝내기
- DB commit 후 메모리 `afterCommit` callback만으로 cleanup 실행
- 기존 object를 먼저 삭제한 후 새 이미지 Copy

### 변경 시 주의

- 기존 object를 새 이미지 DB 반영 전에 삭제하면 안 된다.
- compensation 생성 실패도 로그와 fallback 없이 무시하면 안 된다.
- `default/` key는 절대 삭제 대상에 포함하면 안 된다.

## 5. Copy 정상 실행과 Copy 재시도의 검증 비용을 다르게 본다

### 결정

- 최초 Copy 정상 실행은 SDK 성공 응답과 result ETag를 기준으로 성공 처리한다.
- timeout 등 결과가 불명확한 재시도에서는 destination object 확인이 필요하다.
- destination key는 operation 생성 시 고정해 재시도 시 orphan object 증가를 막는다.

### 이유

모든 Copy 이후 `headObject`를 실행하면 이미지마다 불필요한 네트워크 호출이 추가된다. 반면 timeout 후 무조건 Copy를 다시 실행하면 결과가 불명확한 작업을 안전하게 판별하기 어렵다.

### 채택하지 않은 대안

- 모든 Copy 뒤에 항상 `headObject`
- timeout 후 destination 확인 없이 항상 Copy 재실행

### 변경 시 주의

현재 비동기 Copy retry 전체 파이프라인은 아직 구현하지 않았다. `COPY_STAGING_TO_FINAL`만 단독 비동기 재시도하면 Copy 성공 후 DB 등록이 누락될 수 있으므로 `REGISTER_IMAGE_DB`와 함께 설계해야 한다.

## 6. 삭제 작업부터 공통 구조로 통합한다

### 결정

`COMPENSATE_FINAL_OBJECT`, `DELETE_OBJECT`, `DELETE_FOLDER`를 먼저 공통 `ImageOperation` 구조로 통합한다.

### 이유

삭제는 비교적 멱등성이 높아 retry/DLQ/timeout 복구를 점진 적용하기 안전하다. Copy와 DB 등록은 부분 성공 및 중복 생성 위험이 더 크다.

### 채택하지 않은 대안

- 모든 이미지 생성·수정·삭제를 한 번에 완전 비동기화
- 기존 cleanup 구조를 즉시 제거

### 변경 시 주의

`FailedImageCleanup`과 `FailedImageCleanupScheduler`는 신규 구조가 충분히 검증될 때까지 fallback으로 유지한다.

## 7. 프로필 이미지 흐름은 당분간 동기 응답을 유지한다

### 결정

사용자 및 채팅방 프로필 이미지 변경 API는 Copy와 DB 반영 결과를 현재 요청 안에서 확인한다. cleanup만 Outbox로 비동기화한다.

### 이유

프로필 이미지는 단일 이미지이며, API 호출자가 즉시 변경 결과를 기대한다. 전체 비동기화의 복잡도 대비 현재 이점이 크지 않다.

### 채택하지 않은 대안

- 요청 접수 후 즉시 성공을 반환하고 Copy/DB 등록 전체를 비동기 처리

### 변경 시 주의

기존 API response format, CDN URL, object key 동작을 유지해야 한다. Post/Poll 다중 이미지 흐름과 동일한 이유로 무조건 완전 비동기화하지 않는다.

## 8. operation과 step을 분리한다

### 결정

- `ImageOperation`: 사용자 관점의 전체 이미지 작업 상태
- `ImageOperationStep`: Copy, 일반 삭제, 보상 삭제, folder 삭제 등 실제 실행 단위

### 이유

하나의 이미지 변경에도 여러 side effect가 존재한다. 전체 작업 상태와 개별 재시도 단위를 분리해야 특정 step만 retry/DLQ 처리할 수 있다.

### 채택하지 않은 대안

- 모든 상태를 하나의 operation row에 저장
- 작업 종류별 별도 table을 계속 추가

### 변경 시 주의

operation 완료 여부는 관련 step 전체 상태로 판정해야 한다. 보상 삭제 성공은 정상 완료와 의미가 다르므로 `COMPENSATED`로 구분한다.

## 9. 기존 Phase 1 구조는 즉시 삭제하지 않는다

### 결정

새 cleanup 실패는 공통 `ImageOperation` 경로로 보내지만, 기존 Phase 1 entity/table/config와 `FailedImageCleanup` fallback은 유지한다.

### 이유

운영 RabbitMQ 검증과 데이터 이전이 끝나지 않은 상태에서 기존 경로를 제거하면 복구 수단을 잃을 수 있다.

### 채택하지 않은 대안

- 공통 구조 도입과 동시에 기존 table/class 제거

### 변경 시 주의

제거 전 실제 사용 여부, 남은 row, 운영 fallback 필요성, migration 전략을 별도로 확인해야 한다.

## 10. 생성·수정·삭제를 모두 적용하되 점진적으로 확장한다

### 결정

최종 적용 대상은 이미지 생성·수정·삭제 전체다. 현재는 사용자 프로필 수정과 채팅방 프로필 수정까지 진행했으며, 채팅방 생성·삭제와 Post/Poll은 후속 단계다.

### 이유

흐름마다 transaction 경계와 실패 의미가 다르다. 한 번에 변경하면 기존 URL/key/API 동작의 회귀 위험과 장애 분석 난도가 커진다.

### 채택하지 않은 대안

- 공통 추상화를 먼저 크게 만들고 모든 서비스를 동시에 전환

### 변경 시 주의

새 흐름 적용 전 반드시 다음 실패 시나리오를 테스트해야 한다.

- Copy 실패
- Copy 성공 후 DB 실패
- DB 성공 후 cleanup publish 실패
- Consumer 중복 실행
- Consumer 처리 중 종료 및 timeout 복구
