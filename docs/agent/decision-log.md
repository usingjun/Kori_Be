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

최종 적용 대상은 이미지 생성·수정·삭제 전체다. 현재는 사용자·채팅방 프로필, 사용자 프로필 직접 MultipartFile 업로드, 일반 Post presigned 이미지 생성·수정, Poll upsert까지 진행했다. 관리자 Post 직접 업로드와 크롤러 외부 URL 업로드는 후속 단계다.

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

## 11. 채팅방 프로필 생성과 수정은 복구 구조를 공유하되 operation type을 분리한다

### 결정

- 생성과 수정 모두 동기 Copy, DB flush, cleanup Outbox, rollback compensation 구조를 사용한다.
- 생성은 `CREATE_CHAT_ROOM_PROFILE_IMAGE`, 수정은 `UPDATE_CHAT_ROOM_PROFILE_IMAGE`로 추적한다.

### 이유

실행 단계와 복구 방식은 같지만 장애 의미가 다르다. 생성 실패는 아직 이미지가 없는 상태를 유지해야 하고, 수정 실패는 기존 이미지를 유지해야 한다. operation type을 분리하면 운영 조회와 장애 분석에서 두 상황을 구분할 수 있다.

### 채택하지 않은 대안

- 생성과 수정을 모두 `UPDATE_CHAT_ROOM_PROFILE_IMAGE`로 기록
- 생성 전용 복구 서비스를 별도로 구현

### 변경 시 주의

- 생성 성공 후 cleanup 대상은 staging object뿐이다.
- 수정 성공 후 cleanup 대상은 old object와 staging object다.
- 생성이 포함된 상위 `ChatRoomService.createGroupChatRoom()` transaction이 rollback되면 final object compensation이 필요하다.

## 12. 사용자 프로필 생성과 수정도 복구 구조를 공유하되 operation type을 분리한다

### 결정

- `saveUserProfileImage()`는 기존 upsert 동작을 유지하면서 `CREATE_USER_PROFILE_IMAGE`로 추적한다.
- `updateUserProfileImage()`는 `UPDATE_USER_PROFILE_IMAGE`로 추적한다.
- 두 흐름 모두 Copy 성공 후 DB 실패 보상과 성공 후 old/staging cleanup Outbox를 사용한다.

### 이유

`saveUserProfileImage()`는 실제 코드상 기존 이미지가 있으면 갱신하는 upsert다. 기존 동작을 바꾸지 않으면서 운영상 초기 설정과 명시적 수정을 구분하기 위해 operation type만 분리한다.

### 채택하지 않은 대안

- `saveUserProfileImage()`에서 기존 이미지가 있으면 오류 처리
- 생성과 수정을 모두 `UPDATE_USER_PROFILE_IMAGE`로 기록

### 변경 시 주의

- `saveUserProfileImage()`의 기존 upsert 동작과 moderation event 발행을 유지해야 한다.
- `uploadUserProfileImage(MultipartFile)`는 staging Copy가 아닌 직접 `putObject` 흐름이므로 별도 보상 설계가 필요하다.

## 13. 프로필 삭제는 DB 삭제 후 동기 folder 삭제 대신 Outbox를 사용한다

### 결정

- 프로필 `Image` row 삭제와 `DELETE_FOLDER + Outbox` 저장을 같은 transaction에서 처리한다.
- 실제 folder 삭제는 commit 이후 Consumer가 수행한다.
- RabbitMQ 비활성화 시 직접 삭제 fallback도 commit 이후에만 실행한다.

### 이유

기존처럼 transaction 안에서 S3 folder를 먼저 삭제하면 이후 DB transaction rollback 시 DB row는 복원되지만 object는 이미 사라지는 정합성 문제가 발생한다.

### 채택하지 않은 대안

- DB row 삭제 직후 S3 folder 동기 삭제
- folder 삭제 실패를 로그만 남기고 무시

### 변경 시 주의

- folder 삭제 범위는 기존 `users/{userId}/`, `chatRoom/{chatRoomId}/` 규칙을 유지해야 한다.
- 소유자 삭제 transaction에서 호출되더라도 Outbox가 같은 transaction에 참여해야 한다.
- 채팅방 삭제는 `ChatRoomService.leaveRoom()`의 상위 transaction에 참여하므로 실제 folder 삭제를 commit 전에 실행하면 안 된다.

## 14. 직접 MultipartFile 업로드 실패는 DB 등록 재시도 대신 object 보상 삭제로 복구한다

### 결정

- 직접 `putObject`는 동기 실행하고 `UPLOAD_USER_PROFILE_IMAGE / UPLOAD_OBJECT`로 상태를 추적한다.
- `putObject` 결과 불명확, DB 반영 실패, 상위 transaction rollback 시 새 object를 `COMPENSATE_FINAL_OBJECT`로 삭제한다.
- 실패한 요청의 Image DB 등록을 나중에 단독 재시도하지 않는다.

### 이유

DB 등록만 나중에 재시도하면 원래 AI User 생성/수정 transaction이 rollback됐거나 사용자가 더 최신 이미지를 설정한 상태에서 오래된 이미지가 다시 연결될 수 있다. 실패한 요청의 새 object를 제거하는 방식이 현재 동기 API에서는 더 안전하다.

### 채택하지 않은 대안

- `putObject` 성공 후 DB 저장만 비동기 재시도
- 직접 업로드 전체를 RabbitMQ message로 전달

### 변경 시 주의

- MultipartFile bytes를 Outbox나 RabbitMQ message에 저장하지 않는다.
- DB 등록 재시도가 필요해지면 owner 존재 여부, 최신 요청 여부, Image row 멱등성, moderation event를 포함한 전체 파이프라인으로 설계해야 한다.
- RabbitMQ 비활성화 시 compensation Outbox를 기다리지 않고 직접 bulk delete를 실행하며, 삭제 실패는 기존 `FailedImageCleanup` fallback에 맡긴다.
- 직접 업로드 중 서버가 종료되면 bytes를 재구성할 수 없으므로 `UPLOAD_OBJECT`를 자동 재시도해서는 안 된다.

## 15. Copy 완료 상태 기록 실패도 Copy 실패와 동일하게 종결한다

### 결정

- NCP Copy가 모두 성공해도 `completeCopies()` transaction이 실패하면 해당 요청의 전체 Copy plan을 `FAILED`로 종결한다.
- 완료 상태 기록 실패 시 모든 target object에 compensation 삭제를 예약한다.
- 실패 상태 기록 transaction 자체가 실패해도 compensation 예약은 계속 시도한다.

### 이유

Copy 전에 operation/step을 `PROCESSING`으로 저장하므로, Copy 성공 후 완료 상태 기록만 실패하면 작업 thread는 종료됐는데 DB에는 계속 실행 중으로 남는다. 또한 상위 코드가 `TrackedCopy` 결과를 받지 못해 final object와 Image DB 사이의 정합성도 깨질 수 있다.

### 채택하지 않은 대안

- 완료 상태 기록이 실패해도 로그만 남기고 종료
- `completeCopies()`만 무조건 재시도
- 완료 여부가 불명확한 Copy를 성공으로 간주

### 변경 시 주의

- Copy 상태 기록 실패 시 `REGISTER_IMAGE_DB`가 실행되지 않으므로 Copy만 재시도하면 안 된다.
- target object 삭제는 멱등성을 활용할 수 있지만 staging source object는 삭제하지 않는다.
- 오래된 `COPY_STAGING_TO_FINAL / PROCESSING` 상태의 자동 복구는 별도 timeout 정책으로 구현해야 한다.

## 16. Post/Poll presigned 이미지 작업의 동시 실행 수를 제한한다

### 결정

- Post/Poll presigned 이미지 생성·수정은 전용 `postImageExecutor`를 사용한다.
- 동시에 최대 10개 요청만 실행하고, 추가 요청은 최대 200개까지 queue에서 대기시킨다.
- queue 초과 시 `CallerRunsPolicy`로 작업 유실 대신 호출 thread에 역압력을 건다.
- 인터페이스에 남아 있던 불필요한 `@Transactional`을 제거해 NCP Copy 구간을 DB transaction 밖에서 실행한다.

### 이유

50 VU 측정에서 Post API는 모두 성공했지만 `Could not open JPA EntityManager for transaction`으로 90개 Copy step이 실패했다. 공용 `imageExecutor`는 최대 150개 worker를 허용하며 Hikari pool 25개보다 많은 DB transaction을 동시에 시작할 수 있었다. 또한 인터페이스의 잔여 `@Transactional`은 Copy 전체가 transaction으로 감싸질 위험이 있었다.

### 채택하지 않은 대안

- Hikari pool과 DB connection 수만 확대
- 공용 `imageExecutor`의 전체 크기 축소
- 실패한 Copy step을 상태 확인 없이 자동 재시도

### 변경 시 주의

- 고정 동시성 10은 현재 로컬 측정과 목표 서버 `2코어 / 8GB`를 고려한 시작값이며 운영 측정 후 조정해야 한다.
- queue는 메모리 기반이므로 서버 종료 시 대기 작업이 유실될 수 있다. 장기적으로 durable 작업 Outbox를 검토해야 한다.
- queue 초과 시 `CallerRunsPolicy` 때문에 Post API 응답시간이 증가할 수 있지만 작업 유실보다 안전한 선택이다.

## 15. 다중 Post/Poll Copy는 이미지별 operation으로 추적한다

### 결정

- 일반 Post/Poll의 staging Copy는 이미지마다 독립된 `ImageOperation`과 `COPY_STAGING_TO_FINAL` step으로 추적한다.
- 다중 Copy 중 일부만 성공한 뒤 다른 Copy나 DB 저장이 실패하면 성공한 final object만 각각 compensation 처리한다.
- 정상 성공 후 staging/removed object 삭제는 `DELETE_OBJECT + Outbox`로 처리한다.

### 이유

다중 이미지 요청은 일부 Copy만 성공할 수 있다. 하나의 operation에 모든 Copy를 묶으면 특정 이미지의 실패와 보상 대상을 구분하기 어렵고, 독립 retry/DLQ 상태도 표현하기 어렵다.

### 채택하지 않은 대안

- Post/Poll 요청 전체를 하나의 Copy step으로 추적
- Copy 성공 직후 staging object 동기 삭제
- DB rollback 여부와 무관하게 removed object를 먼저 삭제

### 변경 시 주의

- 현재 Poll 이미지는 기존 구조상 `ImageType.POST`를 공유하고 folder만 `vote/{id}/`를 사용한다. 별도 `ImageType.POLL`로 변경하려면 조회·삭제·migration 영향을 별도로 검토해야 한다.
- 관리자 `MultipartFile` Post 업로드와 크롤러 외부 URL 업로드는 staging Copy 흐름이 아니므로 별도 직접 업로드 보상 설계가 필요하다.
- Outbox 발행 지연 개선은 실제 polling 처리 시간 측정 후 결정하며, 적용하더라도 polling relay를 fallback으로 유지한다.

## 16. assigned UUID entity의 신규 판별을 위해 `@Version`을 수동 초기화하지 않는다

### 결정

`ImageOperation`과 `ImageOperationStep` 생성 시 `version = 0L`을 직접 설정하지 않는다. 신규 entity의 version은 Hibernate가 persist 과정에서 초기화하게 한다.

### 이유

Spring Data JPA는 assigned ID entity에서 nullable `@Version`을 신규 여부 판별에 사용한다. 생성 시 version을 `0L`로 설정하면 신규 entity를 기존 entity로 판단해 `merge/update`를 시도하고, 실제 PostgreSQL에서 `ObjectOptimisticLockingFailureException`이 발생한다.

### 변경 시 주의

- DB의 version column과 optimistic locking 동작은 유지한다.
- assigned UUID와 `@Version`을 함께 쓰는 신규 entity는 persist 전에 version을 직접 채우지 않는다.
- Mockito repository 테스트만으로는 신규 판별 문제가 드러나지 않으므로 실제 PostgreSQL 통합 검증을 유지한다.

## 15. AI 사용자 프로필 교체 시 folder를 선삭제하지 않는다

### 결정

- `UserAdminService.updateAiUser()`는 기존 프로필 folder를 삭제한 뒤 업로드하지 않는다.
- 새 versioned key 업로드와 DB 반영이 성공한 후 기존 object 하나만 Outbox로 삭제한다.

### 이유

기존 `DELETE_FOLDER` Outbox가 commit 후 실행되면 같은 `users/{userId}/` folder에 새로 업로드된 이미지까지 삭제할 수 있다. object 단위 cleanup으로 교체해야 새 이미지가 보호된다.

### 채택하지 않은 대안

- 기존 folder 삭제 완료 후 새 이미지 업로드
- 새 이미지를 별도 임시 folder에 업로드한 후 이동

### 변경 시 주의

- 사용자 탈퇴나 명시적인 전체 프로필 삭제에서는 기존 `DELETE_FOLDER`를 유지한다.
- 프로필 교체에서는 `DELETE_OBJECT`만 사용해야 한다.

## 17. Post/Poll 이미지 처리의 DB connection 증폭을 구조적으로 개선한다

### 결정

- Hikari pool 크기만 늘리는 방식은 최종 해결책으로 사용하지 않는다.
- Post/Poll 다중 이미지 흐름에서 동일 `imageExecutor`를 외부 `@Async` 작업과 내부 Copy에 중첩 사용하지 않는다.
- 이미지별 `ImageOperation`과 여러 `REQUIRES_NEW` 상태 갱신을 요청 단위 operation과 batch transaction으로 축소하는 방향을 우선 검토한다.
- 2코어·8GB 서버를 기준으로 제한된 동시성을 사용한다.

### 이유

`100 VU × 이미지 5장` post-only 측정에서 기본 Hikari pool 10개는 18건만 성공하고 82건이 약 30초 후 실패했다. Hikari pool 25개와 connection timeout 3초를 적용하자 96건이 성공했지만 4건은 약 3초 후 실패했고, 테스트 종료 10초 후에도 operation/step의 `PENDING/PROCESSING` 상태가 유지됐다.

현재 Post 이미지 5장은 약 56개 prepared statement와 26개 transaction을 발생시킨다. 동시 Post 100개에서는 약 5,600개 statement와 2,600개 transaction 요청으로 증폭된다. 작은 서버에서 pool만 늘리면 DB 경쟁과 context switching을 증가시킬 수 있다.

### 채택하지 않은 대안

- Hikari pool만 계속 확대
- `imageExecutor` thread 수만 확대
- `CallerRunsPolicy`를 유지한 채 HTTP timeout만 증가
- 안정성 추적을 제거해 DB 비용을 줄이기

### 변경 시 주의

- operation/step 추적, compensation, retry/DLQ, idempotency는 유지해야 한다.
- 요청 단위 operation으로 변경하더라도 이미지별 실패 및 보상 대상은 step 단위로 구분해야 한다.
- transaction을 합칠 때 일부 Copy 성공 후 DB rollback 시 compensation 대상이 누락되지 않아야 한다.
- 개선 전후 동일한 DB benchmark와 `100 VU post-only` 테스트로 검증해야 한다.

## 18. Post/Poll 내부 Copy는 동일 imageExecutor에 다시 제출하지 않는다

### 결정

`PostImageServiceImpl`과 `MainContentImageServiceImpl`의 바깥 `@Async("imageExecutor")`는 유지한다. 내부 이미지 Copy는 `CompletableFuture.supplyAsync(..., imageExecutor)`로 다시 제출하지 않고 현재 비동기 작업 thread에서 순차 실행한다.

### 이유

기존 구조는 `imageExecutor` thread가 같은 executor에 내부 Copy를 제출한 뒤 `join()`으로 기다렸다. 부하가 커지면 실행 가능한 thread가 대기 상태가 되고 내부 작업이 실행되지 않는 thread starvation 위험이 있었다. 2코어 서버에서 이미지 최대 5장의 내부 병렬성보다 작업 정체 방지가 더 중요하다.

### 트레이드오프

- 단일 Post/Poll의 이미지 Copy 완료시간은 병렬 Copy보다 길어질 수 있다.
- Post API는 바깥 `@Async`를 유지하므로 Copy 완료를 기다리지 않는다.
- DB statement와 transaction 수는 이번 변경으로 줄지 않는다.

### 변경 시 주의

- 내부 병렬 Copy를 다시 도입하려면 바깥 orchestration executor와 분리된 제한된 executor 또는 RabbitMQ Consumer 동시성을 사용해야 한다.
- 중첩 제거 효과와 DB 병목을 구분하기 위해 동일 `100 VU post-only` 테스트를 먼저 재실행한다.

## 19. Post/Poll 이미지별 추적은 유지하고 상태 저장 transaction을 batch 처리한다

### 결정

- 이미지별 `ImageOperation`과 `COPY_STAGING_TO_FINAL` step은 유지한다.
- 한 요청의 operation/step 준비 상태와 Copy 완료 결과는 각각 요청 단위 `REQUIRES_NEW` transaction에서 batch 저장한다.
- NCP Copy는 batch DB transaction 밖에서 순차 실행한다.
- Copy 실패 시 이전 성공, 현재 실패, 이후 미실행 step 상태를 한 transaction에서 정리한다.

### 이유

이미지별 독립 추적과 compensation 대상을 유지하면서도 이미지마다 반복되던 operation 생성, PROCESSING, COMPLETED transaction을 줄이기 위해서다. `PostImageServiceDbBenchmarkTest`에서 5장 기준 transaction이 `26 → 8`, 20장 기준 `101 → 23`으로 감소했다.

### 채택하지 않은 대안

- 안정성 추적을 제거하고 Image row만 저장
- Post/Poll 요청 전체를 하나의 step으로 축약
- Hikari pool 크기만 확대

### 변경 시 주의

- Copy 실패 상태 기록이 실패해도 destination object compensation은 실행해야 한다.
- 미실행 Copy destination은 compensation하지 않는다.
- 현재 바깥 Post/Poll `@Transactional`은 Copy 동안 유지되므로 DB connection 장기 점유 문제는 별도 개선해야 한다.

## 20. Post/Poll NCP Copy는 DB transaction 밖에서 실행한다

### 결정

- Post/Poll 이미지 orchestration 메서드 전체를 감싸던 `@Transactional`을 제거한다.
- Copy 전 현재 Image 상태는 짧은 read-only transaction에서 snapshot으로 조회한다.
- NCP Copy는 transaction 없이 실행한다.
- Copy 성공 후 제거·재정렬·Image 저장·cleanup Outbox 저장은 하나의 짧은 쓰기 transaction에서 처리한다.
- 최종 쓰기 전 현재 Image 집합이 snapshot과 다르면 저장을 거부하고 Copy 결과를 compensation한다.

### 이유

NCP Copy는 외부 I/O이므로 실행시간 동안 DB connection을 점유할 필요가 없다. 기존 구조는 Copy 지연이 Hikari connection 점유 시간으로 이어져 동시 요청에서 pool 고갈 가능성을 높였다.

### 채택하지 않은 대안

- Hikari pool만 확대
- transaction 안에서 NCP Copy 유지
- snapshot 검증 없이 조회와 쓰기 transaction을 단순 분리

### 변경 시 주의

- transaction 수 자체는 짧은 조회 transaction 때문에 직전 구조보다 1개 증가한다. 목표는 transaction 개수 최소화가 아니라 외부 I/O 동안 connection 점유 제거다.
- 같은 owner의 이미지 요청이 Copy 중간에 변경되면 stale snapshot 오류와 compensation이 발생할 수 있다.
- snapshot 검증을 제거하면 동시 수정 시 이미지 순서와 제거 대상 정합성이 깨질 수 있다.

## 21. Post/Poll 이미지 비동기 작업은 상위 Transaction Commit 이후 제출한다

### 결정

- `ImageServiceImpl`의 Post/Poll 비동기 위임 메서드에는 `@Transactional`을 두지 않는다.
- 호출자 transaction synchronization이 활성화되어 있으면 `afterCommit()`에서 `PostImageServiceImpl` 또는 `MainContentImageServiceImpl`의 `@Async` 메서드를 호출한다.
- 호출자 transaction이 없으면 기존처럼 즉시 호출한다.

### 이유

기존 구조는 Post/Poll transaction 안에서 `@Async` 작업을 즉시 제출해, 이미지 작업이 먼저 완료된 후 상위 transaction이 rollback될 수 있었다. commit 이후 제출하면 rollback된 Post/Poll에 이미지가 연결되거나 기존 이미지가 변경되는 문제를 막을 수 있다.

### 채택하지 않은 대안

- 상위 transaction 안에서 `@Async` 즉시 제출 유지
- 이미지 작업 전체를 상위 transaction에서 동기 실행
- 현재 단계에서 Post/Poll Copy·DB 등록 전체 RabbitMQ 파이프라인 구현

### 변경 시 주의

- `afterCommit()`은 실행 시점만 보장하며 durable queue가 아니다.
- DB commit 직후 callback 실행 전 서버가 종료되면 이미지 작업 요청이 유실될 수 있다.
- 완전한 전달 보장이 필요하면 Post/Poll transaction 안에서 작업 요청 Outbox를 저장하고 Consumer가 처리하는 구조가 필요하다.

## 22. Post 생성 이미지는 전체 단계 재시도 파이프라인으로 처리한다

### 결정

- RabbitMQ 활성화 시 Post 생성 transaction 안에서 최초 이미지 step과 Outbox를 저장한다.
- staging 이미지는 `COPY_STAGING_TO_FINAL → REGISTER_IMAGE_DB → DELETE_STAGING` 순서로 처리한다.
- Copy만 단독 재시도하지 않고, DB 등록과 staging 삭제까지 이어지는 단계형 파이프라인으로 처리한다.
- 각 단계 완료와 다음 단계 Outbox 저장은 같은 transaction으로 묶는다.
- 외부 NCP Copy는 transaction 밖에서 실행한다.

### 이유

기존 구조는 Post API 성공 후 비동기 Copy 또는 Image DB 등록이 실패하면 사용자가 이미지를 다시 등록해야 했다. 또한 `afterCommit` 메모리 callback과 executor queue는 서버 종료 시 작업 요청이 유실될 수 있었다.

전체 단계 재시도를 적용하면 일시적인 Copy 장애, DB 등록 장애, staging 삭제 장애를 각각 해당 단계부터 복구할 수 있다. Post 저장과 최초 Outbox가 함께 commit되므로 Post만 생성되고 이미지 작업 요청이 사라지는 문제도 줄어든다.

### 채택하지 않은 대안

- Copy 실패 시 final object 보상 삭제만 수행하고 사용자가 다시 등록
- Copy step만 자동 재시도하고 Image DB 등록은 재시도하지 않음
- 전체 Copy와 DB 등록을 하나의 긴 transaction에서 실행

### 변경 시 주의

- 현재 적용 범위는 Post 생성뿐이다. Post 수정은 제거 이미지, 순서 변경, 최신 요청 덮어쓰기 방지를 함께 설계해야 한다.
- 동일 message 재전달 시 Image DB 중복 등록을 방지해야 한다.
- Copy/Register retry 한도 소진 시 생성됐을 수 있는 final object의 보상 삭제가 필요하다.
- RabbitMQ 비활성화 시 기존 직접 처리 fallback을 유지한다.

## 23. 전체 재시도 설계는 특정 저사양 서버 제약을 기준으로 축소하지 않는다

### 결정

- 전체 재시도 파이프라인은 일반적인 안정적 서비스 환경을 기준으로 설계한다.
- 기존 `postImageExecutor` worker 10개는 직접 처리 fallback의 부하 테스트 시작값으로만 유지한다.
- 현재 단계에서 다중 서버, MSA, 자동 증설 구조는 설계 범위에 포함하지 않는다.

### 이유

이번 작업의 핵심은 특정 서버 사양에 맞춘 처리량 제한이 아니라, 작업 유실 방지, 단계별 재시도, 멱등성, 짧은 transaction 경계다. 특정 저사양 환경을 전제로 설계를 축소하면 안정성 요구사항을 충분히 충족하지 못할 수 있다.

### 변경 시 주의

- 동시성을 무제한으로 설정한다는 의미는 아니다.
- 실제 Consumer 동시성 값은 운영 환경 측정으로 결정하되, 현재 worker 10개를 아키텍처의 고정 한도로 가정하지 않는다.

## 24. Post 이미지는 UUID final key로 직접 업로드한다

### 결정

- `ImageType.POST` Presigned URL은 `temp/` 대신 `posts/objects/{uuid}.{ext}` final key를 발급한다.
- Post 생성·수정 transaction에서 Post와 Image DB를 함께 반영한다.
- Post 수정 성공 후 제거된 기존 object 삭제만 `DELETE_OBJECT + Outbox + RabbitMQ`로 처리한다.
- 기존 `temp/` key 요청은 전환 기간 동안 기존 Copy 파이프라인 fallback을 유지한다.
- Post에 등록되지 않은 UUID object는 별도 정리 배치의 대상으로 둔다.

### 이유

Post ID 기반 final key를 만들기 위해 staging object를 Copy하면서 Copy retry, DB 등록 retry, staging 삭제, 보상 삭제, timeout 복구가 필요해졌다. UUID final key는 Post 생성 전에도 발급할 수 있으므로 Copy 자체를 제거할 수 있다.

Post와 Image DB를 같은 transaction에서 반영하면 사용자에게 필요한 정합성은 DB transaction으로 보장하고, RabbitMQ는 기존 object 삭제처럼 실패해도 사용자 데이터가 깨지지 않는 후처리에 집중할 수 있다.

### 채택하지 않은 대안

- Post ID 기반 object key를 유지하기 위한 전체 Copy 재시도 파이프라인
- Image DB 등록과 Post 수정을 RabbitMQ Consumer에서 수행
- Post transaction 안에서 NCP Copy 실행

### 변경 시 주의

- `ImageType.POST`는 Poll에서도 공유하므로 새 Presigned key 규칙은 Poll 업로드에도 적용된다.
- Post transaction 실패 시 업로드된 final object가 남을 수 있으므로 미등록 object 정리 배치가 필요하다.
- UUID key 구조에서는 Post folder 전체 삭제보다 DB에 등록된 object 단위 삭제가 안전하다.
- 기존 앱의 `temp/` key 지원을 제거하기 전 사용 비율과 migration 완료 여부를 확인해야 한다.

## 25. 미등록 Post UUID object는 `image_upload_session`으로 추적한다

### 결정

- Post용 Presigned URL 발급 시 `image_upload_session`을 `ISSUED`로 저장한다.
- Post/Image 저장 transaction에서 `ISSUED` session row를 잠그고 요청 사용자 소유권을 검증한 뒤 `REGISTERED`로 전환한다.
- 만료 배치는 `(status, expires_at)` index로 만료된 `ISSUED` session만 조회한다.
- 이미 `Image.url`에서 사용 중인 session은 `REGISTERED`, 미사용 session은 `DELETE_PENDING`으로 전환한다.
- Poll 저장 transaction도 존재하는 session을 잠그고 `REGISTERED`로 전환한다. session이 없는 기존 staging Copy 결과는 호환을 위해 허용한다.
- `DELETE_PENDING + CLEANUP_ONLY + DELETE_OBJECT + Outbox`는 같은 transaction에서 저장한다.
- Consumer 삭제 성공 시 `DELETED`, retry 한도 초과 시 `DELETE_FAILED`로 전환한다.

### 이유

UUID final key 직접 업로드에서는 Post 등록 이전에 object가 생성되므로 미완료 업로드를 별도로 추적해야 한다. session row lock을 사용하면 Post 등록과 만료 정리가 동시에 같은 key의 상태를 확정하지 못하며, Object Storage 전체 순회도 제거할 수 있다.

### 채택하지 않은 대안

- Object Storage 전체 prefix pagination
- 배치에서 NCP object를 즉시 삭제
- `Image.url` 존재 여부만으로 정리 대상 판단
- Post folder 단위 삭제

### 변경 시 주의

- 실제 object 존재 여부는 Post 등록 요청 처리 중 `HeadObject`로 확인한다.
- `retention`은 실제 클라이언트가 업로드 후 Post를 제출할 수 있는 최대 허용 시간보다 길게 설정해야 한다.
- `ImageType.POST`는 Poll도 공유하므로 만료 처리 시 `Image.url` 사용 여부를 함께 확인해야 한다.
- 향후 다중 서버에서 만료 배치를 병렬 실행하면 `FOR UPDATE SKIP LOCKED` 적용을 검토해야 한다.
- terminal session은 상태별 보존기간 이후 정리하며 `DELETE_FAILED`는 자동 삭제하지 않는다.

## 26. 신규 UUID final key는 Post 등록 시 실제 object를 확인한다

### 결정

- Presigned URL 발급 시 session은 `ISSUED`로 시작한다.
- 별도 완료 API를 만들지 않는다.
- Post 등록 요청에서 final key별 `HeadObject`를 실행한다.
- `HeadObject`는 `Propagation.NOT_SUPPORTED`로 상위 Post transaction을 잠시 중단한 상태에서 실행한다.
- 검증 성공 후 Post transaction을 재개하고 `ISSUED` session을 claim한다.

### 이유

클라이언트는 NCP PUT 결과를 이미 알 수 있고, 성공 후 바로 Post 등록을 요청한다. 별도 완료 API는 같은 성공 사실을 다시 전달하는 중복 호출이다. Post 등록 요청에서 실제 object를 확인하면 추가 API 없이 존재하지 않는 object key의 Image DB 등록을 막을 수 있다.

### 채택하지 않은 대안

- 별도 업로드 완료 API와 `UPLOADED` 상태
- `ISSUED` 상태와 클라이언트 응답만 신뢰하고 실제 object 확인 생략
- NCP Object Storage 전체 목록을 주기적으로 조회해 업로드 여부 확인

현재 운영 중인 서비스가 아니므로 기존 Presigned URL 호환을 위한 단계적 배포 설정은 필요하지 않다.

### 변경 시 주의

- 이미지마다 `HeadObject` 1회가 추가되므로 Post 등록 응답시간에 영향을 준다.
- `HeadObject`를 DB transaction 안에서 실행하면 connection 장기 점유가 발생하므로 transaction 중단 경계를 유지해야 한다.
- `HeadObject` 성공 후 실제 DB 저장 전 object가 삭제되는 극히 짧은 경쟁 가능성은 Object Storage와 DB를 단일 transaction으로 묶을 수 없어 남는다.

## 27. 실패한 미등록 object 삭제는 새 cleanup operation으로 재처리한다

### 결정

`DELETE_FAILED` session은 기본 1시간 대기 후 `DELETE_PENDING`으로 전환하고 새 `DELETE_OBJECT + Outbox`를 생성한다. 이전 `DLQ` step은 이력으로 유지한다.

### 이유

DLQ step을 직접 되돌리면 기존 시도 횟수와 실패 이력이 섞인다. 새 operation을 생성하면 재처리 단위와 과거 실패 이력을 분리할 수 있다.

### 변경 시 주의

- 활성 상태인 동일 key 삭제 step이 있으면 새 step을 만들지 않는다.
- 실제 삭제 직전 `Image.url` 등록 여부를 다시 확인해야 한다.

## 28. terminal upload session은 상태별 보존기간 후 삭제한다

### 결정

- `DELETED`: 기본 30일 보존
- `REGISTERED`: 기본 90일 보존
- `DELETE_FAILED`: 자동 삭제하지 않음

### 이유

정상 종료 session을 영구 보존하면 table과 index가 계속 증가한다. 반면 `DELETE_FAILED`는 복구 대상이므로 삭제하면 안 된다.

### 변경 시 주의

terminal session 삭제 배치는 RabbitMQ와 무관하게 실행되어야 한다. 보존기간 변경은 장애 조사에 필요한 이력 기간과 DB 용량을 함께 고려한다.

## 29. OpenAI 호출만 WebClient 비동기 체인으로 전환한다

### 결정

- 전체 Spring MVC/JPA 구조는 유지하고 OpenAI Responses API 호출만 WebClient로 전환한다.
- `AiClient.generateResponse()`부터 AI 메시지 예약까지 `Mono` 체인을 유지한다.
- 호출부에서 `.block()`, `.get()`, `.join()`을 사용하지 않는다.
- OpenAI connection은 최대 10개, pending 요청은 최대 100개로 제한한다.
- 기존 최대 3회 시도와 1초/2초 backoff, connect 5초/response 60초 timeout은 유지한다.
- 일반 4xx는 재시도하지 않고 429, 5xx, 네트워크/timeout, 응답 구조 오류만 재시도한다.

### 이유

기존 OpenAI 호출은 최대 60초 네트워크 대기와 `Thread.sleep()` 재시도 동안 scheduler
worker를 점유했다. WebClient 요청 등록 후 worker를 반환하고 backoff도 non-blocking으로
처리하면, 느린 OpenAI 응답이 공용 scheduler 작업 전체를 지연시키는 범위를 줄일 수 있다.

전체 서버와 JPA를 reactive 구조로 바꾸는 것은 현재 문제 해결 범위를 크게 넘는다. OpenAI
호출과 응답 후 처리만 비동기 체인으로 연결하면 기존 transaction 경계를 유지하면서 가장
긴 외부 대기 구간의 thread 점유를 제거할 수 있다.

### 변경 시 주의

- WebClient를 사용해도 호출부에서 blocking wait를 하면 개선 효과가 사라진다.
- Reactor event-loop에서 JPA, 메시지 저장 등 blocking 작업을 실행하지 않는다.
- 동시 connection 제한을 높이면 OpenAI 429와 비용이 증가할 수 있으므로 측정 없이 늘리지 않는다.
- thinking 상태는 요청 등록 직후가 아니라 비동기 publisher 종료 시점에 해제해야 한다.
- 실제 OpenAI 장애와 부하 상황에서 pending queue, timeout, retry 동작을 추가 검증해야 한다.

## 30. 오래된 알림 삭제를 restart 가능한 Spring Batch Job으로 전환한다

### 결정

- 기존 오전 4시 cron과 JVM 기본 시간대를 유지한다.
- Scheduler는 JobParameter 생성과 `JobLauncher` 호출만 담당한다.
- Job은 단일 chunk Step으로 구성하고 chunk size는 기존 batch size와 같은 1,000으로 시작한다.
- Reader는 알림 entity가 아닌 ID와 정렬 키만 JDBC keyset paging으로 읽는다.
- 정렬 키는 `(created_at, notification_id)`이며 동일한 복합 인덱스를 추가한다.
- Writer는 chunk ID를 하나의 `DELETE ... IN (...)` 쿼리로 삭제한다.
- `scheduledDate`와 오전 4시 Instant 기준 168시간 전 `cutoff`를 모두 identifying
  JobParameter로 사용한다.
- Spring Batch metadata schema와 notification cleanup index는 Flyway가 관리한다.
- 인스턴스들이 동일 PostgreSQL을 공유한다는 전제에서 JobRepository로 중복 실행을 막는다.
- 기존 batch 사이 `Thread.sleep(1000)`은 근거가 확인되지 않아 제거한다.

지역 날짜에서 `minusDays(7)`을 먼저 적용하면 DST 전환이 있는 JVM 시간대에서 기존
`Instant.now().minus(7일)`과 한 시간 차이가 날 수 있다. 따라서 scheduledDate 오전 4시를
Instant로 변환한 후 정확히 168시간을 빼 기존 retention 의미를 유지한다.

### 이유

기존 `deleteBatch()`의 `@Transactional`은 같은 bean 내부 호출이어서 적용되지 않았고 조회와
삭제가 하나의 명시적 transaction 경계에 있지 않았다. 또한 실패 상태와 처리 위치가 로그에만
남아 다음 실행에서 처음부터 다시 시작했다. Spring Batch chunk transaction은 데이터 삭제와
StepExecution/ExecutionContext checkpoint를 함께 commit하므로 실패한 chunk만 rollback하고
마지막 성공 checkpoint부터 restart할 수 있다.

삭제 중 offset paging은 앞 페이지 삭제로 결과가 당겨지면서 항목을 건너뛸 수 있다. 복합
정렬 키 기반 keyset paging은 마지막으로 commit된 키 이후부터 읽으므로 삭제와 restart에 모두
안정적이다.

### 변경 시 주의

- 모든 운영 인스턴스의 JVM 기본 시간대가 같아야 동일 scheduledDate/cutoff가 생성된다.
- 운영 인스턴스가 서로 다른 PostgreSQL을 사용하면 JobRepository 기반 중복 방지는 적용되지 않는다.
- metadata migration이 적용되기 전에 애플리케이션이 오전 4시 Job을 시작하면 실행이 실패한다.
- chunk size나 별도 throttling은 실제 DB 부하를 측정한 뒤 변경한다.
