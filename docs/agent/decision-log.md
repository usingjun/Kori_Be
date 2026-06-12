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
