# Notification Cleanup Spring Batch 전환 계획

## 문서 목적

`NotificationCleanupService`의 수동 `@Scheduled` 삭제 작업을 Spring Batch의 재시작 가능한
chunk Job으로 전환한다. 기존 오전 4시 실행과 7일 보존 정책은 유지하면서 transaction 경계,
paging 안정성, 실행 이력, restart, 다중 인스턴스 중복 실행 방지를 명시적으로 관리하는 것이
목표다.

이 문서는 구현 전에 작성했어야 할 계획 문서의 누락을 보완한다. 아래 내용은 승인된 구현
기준과 현재 코드의 대응 관계를 함께 기록한다.

## 확정 전제

- cron은 `0 0 4 * * *`를 유지한다.
- cron zone을 별도로 지정하지 않고 JVM 기본 시간대를 사용한다.
- 알림 보존기간은 7일을 유지한다.
- 운영 애플리케이션 인스턴스들은 동일 PostgreSQL을 공유한다.
- 기존 batch 사이 `Thread.sleep(1000)`은 제거한다.
- chunk size는 기존 batch size와 같은 1,000건으로 시작한다.

## 1. 현재 구조와 문제점

### 기존 실행 흐름

```text
@Scheduled(cron = "0 0 4 * * *")
→ Instant.now() - 7일 cutoff 계산
→ 항상 0페이지에서 최대 1,000건 조회
→ 조회한 Notification entity를 deleteAllInBatch로 삭제
→ 1,000건을 모두 삭제했으면 Thread.sleep(1000)
→ 다시 0페이지 조회
→ 1,000건 미만이 조회될 때 종료
```

### 확인된 문제

- `scheduledDeleteOldNotifications()`가 같은 bean의 `deleteBatch()`를 직접 호출하므로
  `deleteBatch()`의 `@Transactional`은 Spring proxy를 통과하지 않는 self-invocation이다.
  따라서 의도한 서비스 transaction이 적용되지 않고 Repository 조회와 삭제 transaction이
  분리된다.
- `findByCreatedAtBefore(cutoff, PageRequest.of(0, 1000))`에는 정렬 조건이 없다. 삭제 후
  결과 집합이 당겨지는 점을 이용해 항상 0페이지를 읽지만 처리 순서가 결정적이지 않다.
- 일반적인 offset 증가 방식으로 단순 변경하면 앞 페이지 삭제 후 뒤 행의 offset이 당겨져
  일부 알림을 건너뛸 수 있다.
- `Notification` entity 전체를 조회한 뒤 삭제하므로 cleanup에 필요하지 않은 컬럼까지
  영속화한다.
- `Thread.sleep(1000)`은 full batch 사이 scheduler worker를 점유한다. 코드 주석과 최초
  커밋에는 실제 DB 부하 측정 근거가 없어 방어적 throttling으로만 추정된다.
- 예외를 catch하고 로그만 남기므로 실행 성공·실패, 처리량, 마지막 성공 위치가 영속화되지
  않는다.
- 중간 실패 시 다음 날 처음부터 다시 조회하며 명시적인 checkpoint/restart가 없다.
- 여러 애플리케이션 인스턴스에서 같은 cron이 실행되면 서로 독립적으로 삭제 작업을 시작할
  수 있다.
- Entity와 기존 Flyway migration에는 `created_at` 정리 조건을 지원하는 notification index가
  없다. 확인된 기존 index는 PK인 `notification_pkey(notification_id)`뿐이다.
- cleanup 전용 테스트가 없었다.
- README에는 Spring Batch가 표시되어 있었지만 기존 `build.gradle`에는 Spring Batch
  dependency가 없었다.

## 2. Spring Batch로 전환하는 이유

- `Job`과 `Step`으로 작업 정의와 실행 시도를 분리한다.
- `JobRepository`가 `JobInstance`, `JobExecution`, `StepExecution`을 PostgreSQL에 저장해
  성공·실패·처리량을 추적한다.
- chunk transaction으로 삭제와 checkpoint를 같은 commit 경계에서 관리한다.
- `ExecutionContext`에 Reader 상태를 저장해 마지막 성공 checkpoint부터 restart한다.
- identifying `JobParameter`로 같은 일자의 논리적 작업을 동일 `JobInstance`로 식별한다.
- 공유 PostgreSQL metadata의 unique constraint와 실행 상태를 이용해 다중 인스턴스의 중복
  실행을 차단한다.
- 수동 `while`, `Thread.sleep`, 누적 count, 예외 처리 코드를 Batch 실행 모델로 대체한다.
- 향후 운영에서 Job/Step 상태, read/write/rollback count를 표준 metadata로 조회할 수 있다.

## 3. 목표 구조

```text
NotificationCleanupScheduler
  @Scheduled(04:00, JVM default zone)
  → scheduledDate/cutoff JobParameter 생성
  → JobLauncher.run(notificationCleanupJob, parameters)

JobRepository (shared PostgreSQL)
  → JobInstance 식별 및 중복 실행 판단
  → JobExecution / StepExecution / ExecutionContext 저장

notificationCleanupJob
  → notificationCleanupStep
      → JdbcPagingItemReader<Long>
      → chunk size 1,000
      → NotificationCleanupItemWriter
      → chunk commit + checkpoint
```

Scheduler는 실행 신호와 parameter 생성만 담당한다. 조회·삭제 반복, transaction, checkpoint,
restart는 Spring Batch가 담당한다. Processor는 변환할 업무 데이터가 없으므로 두지 않는다.

## 4. 변경할 파일

| 파일 | 계획된 변경 |
| --- | --- |
| `build.gradle` | `spring-boot-starter-batch`, `spring-batch-test` 추가 |
| `src/main/resources/application.yml` | startup Job 자동 실행 비활성화, Batch schema 자동 생성 비활성화 |
| `core/domain/notification/service/NotificationCleanupService.java` | 수동 loop 기반 구현 제거 |
| `core/domain/notification/repository/NotificationRepository.java` | cleanup 전용 Slice 조회 제거, 기존 API용 메서드는 유지 |
| `core/domain/notification/batch/NotificationCleanupScheduler.java` | 오전 4시 Job 실행과 JobParameter 생성 |
| `core/domain/notification/batch/NotificationCleanupJobConfig.java` | Job, Step, Reader, Clock, parameter validation 구성 |
| `core/domain/notification/batch/NotificationCleanupItemWriter.java` | ID 목록 bulk delete Writer |
| `core/domain/notification/batch/NotificationCleanupJobExecutionListener.java` | JobExecution과 StepExecution 결과 로깅 |
| `V202608170100__create_spring_batch_metadata.sql` | Spring Batch 5.2 PostgreSQL metadata와 sequence 생성 |
| `V202608170200__add_notification_cleanup_index.sql` | notification cleanup 복합 index를 concurrent 생성 |
| `NotificationCleanupSchedulerTest.java` | cron, cutoff, identifying parameter, JobLauncher 호출 테스트 |
| `NotificationCleanupItemWriterTest.java` | bulk delete와 empty chunk 테스트 |
| `NotificationCleanupJobIntegrationTest.java` | PostgreSQL multi-chunk, restart, 중복 실행 통합 테스트 |
| `notification-cleanup-batch-test-schema.sql` | 통합 테스트 전용 격리 schema와 notification table |
| `docs/agent/progress.md` | 구현·검증 진행 상태 기록 |
| `docs/agent/decision-log.md` | 설계 결정과 대안·주의사항 기록 |
| `docs/agent/handoff.md` | 다음 세션을 위한 실행 구조와 운영 전제 기록 |

## 5. 변경하지 않을 범위

- 알림 생성, 조회, 읽음 처리 API와 응답 형식
- `NotificationEventListener`, `UserNotificationService`, push/FCM 발송 흐름
- 사용자 알림 설정과 device token 정리
- 다른 `@Scheduled` 작업과 공용 scheduler pool 설정
- 기존 오전 4시 cron 표현식
- 7일 retention 정책
- JVM 기본 시간대 정책
- notification entity 필드와 알림 타입 enum/check constraint
- 관리자 수동 Job 실행 API 또는 운영 dashboard
- multi-thread Step, partitioning, remote chunking
- 측정 근거 없는 별도 throttling 또는 chunk 사이 sleep
- secrets, Docker, CI/CD, 배포 설정

## 6. Job / Step / Reader / Writer 설계

### Job

- 이름: `notificationCleanupJob`
- 책임: 오래된 알림 cleanup 전체 실행 단위를 정의한다.
- 필수 parameter: `scheduledDate`, `cutoff`
- 구성: 단일 `notificationCleanupStep`
- Listener가 시작과 종료 시 `JobExecution` ID, status, 처리 item 수를 기록한다.

### Step

- 이름: `notificationCleanupStep`
- 유형: chunk-oriented Step
- 입출력 item 타입: `Long` notification ID
- chunk size: 1,000
- Reader가 읽은 ID를 변환 없이 Writer에 전달하므로 Processor는 사용하지 않는다.
- `JobRepository`와 `PlatformTransactionManager`를 명시적으로 연결한다.

### ItemReader

- 구현: `JdbcPagingItemReader<Long>`
- `@StepScope`로 생성해 실행 시점의 `cutoff` JobParameter를 주입한다.
- 조회 컬럼: `notification_id`, `created_at`
- 조건: `created_at < :cutoff`
- 정렬: `created_at ASC, notification_id ASC`
- page/fetch size: 1,000
- `saveState=true`로 Reader 상태를 `ExecutionContext`에 저장한다.
- Entity 전체가 아닌 삭제에 필요한 ID와 paging sort key만 읽는다.

### ItemWriter

- 구현: `NotificationCleanupItemWriter implements ItemWriter<Long>`
- 한 chunk의 ID를 `DELETE FROM notification WHERE notification_id IN (:notificationIds)`로
  bulk 삭제한다.
- 빈 chunk에는 쿼리를 실행하지 않는다.
- 실제 삭제 건수는 chunk별 로그에 남긴다.
- 동시 사용자 삭제로 실제 영향 row가 item 수보다 적더라도 이미 사라진 row이므로 실패로
  처리하지 않는다.

## 7. JobParameter와 cutoff 설계

### parameter

| 이름 | 값 | Identifying | 용도 |
| --- | --- | --- | --- |
| `scheduledDate` | JVM 기본 시간대의 실행 날짜 | Yes | 일일 논리 JobInstance 식별 |
| `cutoff` | 해당 날짜 오전 4시 Instant에서 168시간을 뺀 ISO-8601 문자열 | Yes | 삭제 경계 고정 및 JobInstance 식별 |

### cutoff 계산

```text
scheduledDate = LocalDate.now(Clock.systemDefaultZone())
scheduledAt = scheduledDate, 오전 04:00, JVM 기본 zone
cutoff = scheduledAt Instant - 168시간
→ Instant ISO-8601 문자열
```

- 기존 cron 시각과 retention을 유지한다.
- 기존 `Instant.now().minus(7일)`과 동일하게 DST 전환 여부와 무관한 정확한 168시간
  retention을 유지한다.
- 실제 scheduler 시작이 수초 지연되더라도 같은 날짜의 모든 인스턴스가 동일 parameter를
  생성하도록 cutoff를 예정 시각인 오전 4시에 고정한다.
- 두 parameter를 모두 identifying으로 지정해 실패 restart에서 cutoff가 바뀌는 것을 막는다.
- 조건은 `created_at < cutoff`다. cutoff와 정확히 같은 알림은 삭제하지 않고 다음 실행 대상이
  된다.
- 모든 운영 JVM의 기본 시간대가 동일하다는 전제가 필요하다.

## 8. chunk transaction 설계

- `StepBuilder.chunk(1000, transactionManager)`로 chunk transaction을 구성한다.
- 최대 1,000개 ID read, bulk delete, `StepExecution` 갱신, Reader `ExecutionContext`
  checkpoint 저장을 하나의 commit 경계로 처리한다.
- Writer가 실패하면 해당 chunk의 삭제와 checkpoint가 함께 rollback된다.
- 앞서 commit된 chunk는 유지된다.
- 실패를 skip하고 데이터를 지나가지 않는다. Step을 `FAILED`로 종료해 운영자가 같은
  JobParameter로 restart하도록 한다.
- 기존 self-invocation `@Transactional`은 제거하고 Step의 transaction manager가 경계를
  책임진다.
- `Thread.sleep(1000)`은 제거한다. chunk commit만으로 connection과 lock 보유 구간을
  제한하고, 추가 throttling은 운영 부하 측정 후 별도 결정한다.

## 9. paging 전략

### 채택 전략

- PostgreSQL keyset paging을 사용하는 `JdbcPagingItemReader`를 사용한다.
- 유일하고 결정적인 복합 정렬 키 `(created_at, notification_id)`를 사용한다.
- 동일 `created_at`이 여러 건이어도 PK인 `notification_id`가 tie-breaker가 된다.
- 첫 페이지 이후에는 마지막 sort key보다 큰 row를 조회한다.
- 동일한 컬럼 순서의 복합 index를 추가한다.

### offset paging을 사용하지 않는 이유

cleanup은 읽은 row를 즉시 삭제한다. 첫 1,000건을 지운 뒤 page 1(offset 1,000)을 요청하면
기존 page 1 대상이 page 0으로 당겨져 건너뛸 수 있다. keyset paging은 삭제된 row 수와 무관하게
마지막 처리 키 이후를 조회하므로 누락을 막는다.

### index

```text
idx_notification_cleanup_created_at_id
ON notification (created_at, notification_id)
```

- 운영 table lock 영향을 줄이기 위해 `CREATE INDEX CONCURRENTLY`를 사용한다.
- 해당 migration은 `flyway:executeInTransaction=false`로 실행한다.

## 10. restart 및 중복 실행 방지

### Restart

- `JdbcPagingItemReader`가 마지막 복합 sort key를 Step `ExecutionContext`에 저장한다.
- chunk 삭제와 checkpoint가 함께 commit된다.
- 실패 후 같은 `scheduledDate`, `cutoff`로 Job을 실행하면 같은 `JobInstance`의 새로운
  `JobExecution`이 생성된다.
- restart된 `StepExecution`은 마지막 commit checkpoint 이후부터 읽는다.
- 이미 commit된 chunk는 다시 삭제하지 않고 실패한 chunk부터 처리한다.

### 중복 실행 방지

- `JobInstance = Job 이름 + identifying JobParameter`다.
- 같은 날짜의 애플리케이션 인스턴스들은 동일 `scheduledDate`, `cutoff`를 생성한다.
- 공유 PostgreSQL의 `BATCH_JOB_INSTANCE` unique constraint가 동일 논리 작업을 식별한다.
- 실행 중인 instance에는 `JobExecutionAlreadyRunningException`이 발생한다.
- 이미 완료된 instance에는 `JobInstanceAlreadyCompleteException`이 발생한다.
- Scheduler는 두 예외를 정상적인 중복 억제로 간주해 info 로그만 남긴다.
- 그 외 launch/restart/parameter 오류는 error 로그를 남긴다.
- 인스턴스들이 서로 다른 PostgreSQL을 사용하면 이 중복 방지는 성립하지 않는다.

## 11. metadata table 구성

Spring Batch 5.2의 공식 PostgreSQL schema를 새 Flyway migration으로 관리한다.

| Table | 용도 |
| --- | --- |
| `BATCH_JOB_INSTANCE` | Job 이름과 identifying parameter로 논리 JobInstance 저장 |
| `BATCH_JOB_EXECUTION` | 시작·종료·상태·exit 정보 등 개별 JobExecution 저장 |
| `BATCH_JOB_EXECUTION_PARAMS` | 각 JobExecution의 JobParameter 값과 identifying 여부 저장 |
| `BATCH_STEP_EXECUTION` | StepExecution 상태와 read/write/commit/rollback count 저장 |
| `BATCH_JOB_EXECUTION_CONTEXT` | Job 범위 ExecutionContext 저장 |
| `BATCH_STEP_EXECUTION_CONTEXT` | Reader checkpoint 등 Step 범위 ExecutionContext 저장 |

Sequence:

- `BATCH_JOB_SEQ`
- `BATCH_JOB_EXECUTION_SEQ`
- `BATCH_STEP_EXECUTION_SEQ`

운영 원칙:

- metadata schema는 Flyway만 생성한다.
- `spring.batch.jdbc.initialize-schema=never`로 Spring Boot 자동 생성을 막는다.
- `spring.batch.job.enabled=false`로 애플리케이션 startup 시 Job 자동 실행을 막는다.
- Flyway migration이 Job 최초 실행 전에 적용되어야 한다.
- metadata 보존·정리 정책은 이번 범위에 포함하지 않는다.

## 12. 테스트 전략

### Scheduler 단위 테스트

- cron이 `0 0 4 * * *`이고 zone이 비어 있어 JVM 기본값을 사용하는지 확인한다.
- 고정 Clock으로 `scheduledDate`와 cutoff 계산을 검증한다.
- 두 parameter가 identifying인지 확인한다.
- `JobLauncher`에 기대한 Job과 parameter가 전달되는지 확인한다.
- 이미 실행 중인 instance 예외를 Scheduler가 외부로 전파하지 않는지 확인한다.

### Writer 단위 테스트

- 한 chunk의 모든 ID가 단일 bulk delete parameter로 전달되는지 확인한다.
- empty chunk에서 delete query가 실행되지 않는지 확인한다.

### PostgreSQL 통합 테스트

- 운영 table과 격리된 `notification_batch_test` schema를 사용한다.
- 실제 Batch metadata migration과 cleanup index migration을 적용한다.
- 1,005건이 `1,000 + 5` multi-chunk로 모두 삭제되는지 확인한다.
- `created_at < cutoff`만 삭제되고 cutoff와 같은 시각 및 이후 row가 유지되는지 확인한다.
- 두 번째 chunk Writer 실패를 강제해 첫 chunk만 commit되고 실패 chunk가 rollback되는지 확인한다.
- 같은 JobParameter로 restart했을 때 남은 5건만 read/write하는지 확인한다.
- 완료된 동일 JobInstance 재실행이 `JobInstanceAlreadyCompleteException`으로 거부되는지 확인한다.
- 첫 실행을 진행 중인 상태에서 동일 parameter로 동시에 launch하면
  `JobExecutionAlreadyRunningException`으로 거부되는지 확인한다.
- cleanup 복합 index가 실제 생성됐는지 `pg_indexes`로 확인한다.
- 테스트 종료 후 전용 schema만 제거한다.

### 회귀 테스트

- 프로젝트 전체 `./scripts/agent-check.sh`를 실행한다.
- 기존 PGroonga extension 권한 실패와 신규 Batch 회귀를 분리해 보고한다.

## 13. 구현 순서

- [x] 기존 Scheduler, Repository, transaction, paging, index, Flyway, test 구조를 분석한다.
- [x] `build.gradle`에 Spring Batch runtime/test dependency를 추가하고 실제 관리 버전을 확인한다.
- [x] Spring Batch 5.2 공식 PostgreSQL metadata schema를 새 Flyway migration으로 추가한다.
- [x] notification cleanup 복합 index를 concurrent migration으로 추가한다.
- [x] startup Job 실행과 Batch schema 자동 생성을 비활성화한다.
- [x] 결정적 scheduledDate/cutoff를 만드는 오전 4시 Scheduler를 구현한다.
- [x] 단일 Job/Step과 Step-scoped JDBC keyset Reader를 구현한다.
- [x] chunk ID bulk delete Writer와 JobExecution listener를 구현한다.
- [x] 기존 수동 loop Service와 cleanup 전용 Repository 조회를 제거한다.
- [x] Scheduler와 Writer 단위 테스트를 추가한다.
- [x] PostgreSQL multi-chunk, rollback/restart, 중복 실행 통합 테스트를 추가한다.
- [x] target 테스트와 전체 agent check를 실행한다.
- [x] `progress.md`, `decision-log.md`, `handoff.md`에 결정과 결과를 기록한다.

## 14. 검증 방법

### 정적 검증

- `./gradlew compileJava`
- `git diff --check`
- 기존 `Thread.sleep`, `findByCreatedAtBefore`, cleanup self-invocation이 제거됐는지 검색
- 기존 오전 4시 cron이 하나만 남았는지 확인
- 기존 알림 API와 다른 Scheduler 파일에 변경이 없는지 diff 검토

### 대상 테스트

```text
./gradlew test --tests 'core.domain.notification.batch.*'
```

기대 결과:

- Scheduler 6개 통과
- Writer 2개 통과
- PostgreSQL 통합 4개 통과
- 총 12개 통과

### Migration 검증

- 격리 PostgreSQL schema에 Batch metadata migration을 실제 적용한다.
- 여섯 metadata table과 세 sequence 생성 여부를 확인한다.
- cleanup index migration을 실제 적용하고 `pg_indexes`에서 복합 index를 확인한다.
- cutoff query가 `(created_at, notification_id)` 순서로 동작하는지 multi-chunk 테스트로 확인한다.

### 전체 검증

```text
./scripts/agent-check.sh
```

현재 확인 결과:

- 전체 299개 중 18개 실패, 5개 skip
- 신규 Notification Batch 테스트 12개는 모두 통과
- 18개 실패는 기존 `permission denied to create extension "pgroonga"` 환경 문제
- test task 실패로 스크립트가 종료되어 JaCoCo report 단계는 실행되지 않음

### 배포 전 확인

- 운영 Flyway가 metadata migration과 concurrent index migration을 정상 적용하는지 확인한다.
- 모든 운영 인스턴스가 같은 PostgreSQL과 같은 JVM 기본 시간대를 사용하는지 확인한다.
- 오전 4시 첫 실행 후 `BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION` status와 count를 확인한다.
- 실제 삭제량과 DB lock/CPU/IO를 관측하고 chunk size 변경 필요 여부를 판단한다.
- 근거가 확인되기 전에는 sleep이나 별도 throttling을 다시 추가하지 않는다.
