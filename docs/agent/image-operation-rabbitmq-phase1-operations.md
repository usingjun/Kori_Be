# Image Operation RabbitMQ Phase 1 운영 메모

## 적용 범위

Phase 1은 기존 `FailedImageCleanup` 흐름에서 발생한 `DELETE_OBJECT`, `DELETE_FOLDER` 실패만 RabbitMQ로 비동기 재처리한다.
기존 `FailedImageCleanupScheduler`는 RabbitMQ 장애, publish 실패, consumer 중단 시에도 동작하는 fallback으로 유지한다.

## 활성화에 필요한 외부 설정

애플리케이션 설정 파일에는 실제 연결값을 추가하지 않았다. RabbitMQ를 사용하는 환경에서 다음 값을 외부 설정으로 제공해야 한다.

| 설정 | 설명 |
|---|---|
| `image.cleanup.rabbit.enabled=true` | Phase 1 RabbitMQ topology와 consumer 활성화 |
| `spring.rabbitmq.host` | RabbitMQ host |
| `spring.rabbitmq.port` | RabbitMQ port |
| `spring.rabbitmq.username` | RabbitMQ username |
| `spring.rabbitmq.password` | RabbitMQ password |
| `spring.rabbitmq.virtual-host` | RabbitMQ virtual host |

`image.cleanup.rabbit.enabled`의 기본값은 `false`다. 비활성화 상태에서는 기존 DB 기반 fallback만 동작한다.

## RabbitMQ topology

| 구분 | 이름 |
|---|---|
| main exchange | `image.operation.exchange` |
| main queue | `image.operation.queue` |
| retry exchange | `image.operation.retry.exchange` |
| retry queues | `image.operation.retry.1m.queue`, `image.operation.retry.5m.queue`, `image.operation.retry.15m.queue`, `image.operation.retry.1h.queue` |
| DLX | `image.operation.dlx` |
| DLQ | `image.operation.dlq` |

retry queue는 TTL 만료 후 `image.operation.exchange`로 메시지를 반환한다. 최대 시도 횟수를 넘긴 작업은 PostgreSQL의 `image_cleanup_operation` 상태를 `DLQ`로 변경하고 `image.operation.dlq`에 격리한다.

## 운영 확인 지점

- `image_cleanup_operation`: operation별 현재 상태, 시도 횟수, 마지막 오류
- `image_cleanup_audit_log`: 상태 변경 이력
- `image_cleanup_consumed_message`: `messageId` 기준 중복 소비 방지 기록
- `failed_image_cleanup`: 기존 fallback의 재처리 상태
- `image.operation.dlq`: 자동 재시도 한도를 넘은 메시지

## 현재 제한

- RabbitMQ publish와 PostgreSQL 상태 저장은 하나의 트랜잭션이 아니다.
- publish 실패 시 기존 `FailedImageCleanupScheduler`가 최종 fallback을 담당한다.
- DLQ replay 도구와 관리 UI는 Phase 1 범위에 포함하지 않는다.
- RabbitMQ broker 생성, 계정/권한, TLS, monitoring 설정은 외부 인프라 작업이며 이 변경에 포함하지 않는다.
