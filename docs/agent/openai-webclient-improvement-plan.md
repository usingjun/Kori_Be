# OpenAI 호출 WebClient 전환 검토

## 결론

현재 `RestTemplate` 사용처 중 WebClient 전환 가치가 가장 분명한 곳은
`OpenAiClientImpl`의 OpenAI Responses API 호출이다.

이 전환의 목적은 단일 요청의 응답시간을 줄이는 것이 아니다. OpenAI 응답을 기다리거나
재시도 대기 중인 시간 동안 애플리케이션 스레드를 점유하지 않게 하여, 동시에 처리할 수
있는 AI 요청 수와 장애 시 복원력을 높이는 것이 목적이다.

단순히 `RestTemplate` 호출을 `WebClient`로 바꾼 뒤 `.block()`을 호출하면 기존과 같이
스레드를 점유하므로 이 개선 효과를 얻을 수 없다. `AiClient`부터 후속 메시지 처리까지
비동기 반환 타입을 유지해야 한다.

## 현재 실행 흐름

```text
AI 응답 작업 실행
→ AiChatUserService.processAiResponse()
→ AiClient.generateResponse()
→ RestTemplate로 OpenAI 요청
→ 최대 60초 동안 호출 스레드 대기
→ 실패 시 Thread.sleep(1초/2초) 후 최대 3회 재시도
→ 응답 파싱
→ AI 메시지 전송 예약
```

관련 코드:

- `core.domain.aiuser.client.OpenAiClientImpl`
- `core.domain.aiuser.client.AiClient`
- `core.domain.aiuser.service.AiChatUserService`
- `core.domain.aiuser.service.AiGroupChatRevivalService`
- `core.domain.aiuser.service.AiChatCoordinatorService`
- `core.global.config.RestTemplateConfig`
- `core.global.config.AsyncConfig`

## 문제사항

### 1. 네트워크 대기 중에도 스레드를 계속 점유한다

`OpenAiClientImpl`의 read timeout은 60초다. OpenAI 응답을 기다리는 동안 CPU 작업은 거의
없지만 호출 스레드는 다른 작업을 처리할 수 없다.

AI 응답 작업 일부는 `ThreadPoolTaskScheduler`에서 실행된다. 스케줄러 pool 크기는 10이므로
느린 OpenAI 호출이 누적되면 AI 응답 예약뿐 아니라 같은 scheduler를 사용하는 다른 예약
작업도 늦어질 수 있다.

### 2. 재시도 대기에도 스레드를 점유한다

현재 재시도 사이에 `Thread.sleep()`을 사용한다.

```text
1차 실패 → 1초 sleep → 2차 실패 → 2초 sleep → 3차 시도
```

이 시간에는 네트워크 요청조차 수행하지 않지만 스레드는 반환되지 않는다.

### 3. 동시 요청 처리량이 executor 크기에 직접 제한된다

blocking 호출에서는 동시에 대기할 수 있는 OpenAI 요청 수를 늘리려면 worker thread 수도
늘려야 한다. thread를 늘리면 메모리 사용, context switching, queue 적체가 함께 증가한다.

예를 들어 10개 worker가 각각 30초 동안 OpenAI 응답을 기다리면, 그동안 같은 executor가
처리할 수 있는 추가 작업은 queue에서 기다려야 한다.

### 4. 실패 시 최악의 점유 시간이 길다

현재 설정 기준으로 요청 하나가 read timeout까지 기다린 뒤 재시도에 들어갈 수 있다.
최악의 경우 한 작업이 여러 번의 긴 네트워크 대기와 sleep을 거치며 worker를 오래 점유한다.

### 5. 전체 응답을 받은 뒤에만 처리할 수 있다

현재는 OpenAI 응답 전체를 `Map`으로 받은 뒤 텍스트를 추출한다. 향후 streaming 응답을
도입하려면 현재 blocking client와 동기 반환 계약을 다시 변경해야 한다.

## WebClient 적용 시 개선되는 이유

### 동기와 비동기의 차이를 넘어선 핵심

WebClient의 이점은 메서드 이름이나 반환 타입 자체가 아니라 **대기 중인 요청과 애플리케이션
worker thread를 분리하는 실행 모델**에 있다.

RestTemplate 기반 blocking 호출:

```text
worker thread A
→ OpenAI 요청 전송
→ 응답이 올 때까지 thread A 대기
→ 응답 처리
```

WebClient 기반 non-blocking 호출:

```text
worker thread A
→ OpenAI 요청 등록
→ thread A 즉시 반환

네트워크 응답 도착
→ event-loop가 응답 감지
→ 사용 가능한 thread에서 후속 처리 실행
```

OpenAI 응답이 느려져도 대기 요청마다 애플리케이션 worker thread 하나가 묶이지 않는다.
따라서 같은 thread 수로 더 많은 동시 대기 요청을 관리할 수 있다.

### 재시도 대기에서도 thread를 반환한다

Reactor의 `retryWhen`과 backoff를 사용하면 재시도 대기 시간을 scheduler에 등록하고 worker
thread를 반환할 수 있다. 현재의 `Thread.sleep()`처럼 대기 시간 동안 thread를 점유하지
않는다.

### 느린 외부 API가 내부 executor를 소진시키는 범위를 줄인다

OpenAI 지연이나 일시 장애가 발생해도 AI 호출 대기가 scheduler worker 전체를 점유하는
상황을 줄일 수 있다. 이는 단일 호출 속도 개선이 아니라 부하 상황에서 queue 적체와 연쇄
지연을 완화하는 개선이다.

### streaming 확장 기반을 제공한다

WebClient는 streaming 응답을 `Flux`로 처리할 수 있다. 당장 streaming을 적용하지 않더라도,
비동기 호출 계약을 먼저 만들면 이후 전체 응답 완료 전 토큰 처리나 점진 전송으로 확장하기
쉬워진다.

## 개선되지 않는 부분

WebClient를 적용해도 다음은 자동으로 개선되지 않는다.

- OpenAI 자체 응답 생성 시간
- 모델 처리 속도
- API 비용
- 잘못된 retry 조건이나 과도한 retry 횟수
- 응답 파싱 정확성
- downstream DB/JPA의 blocking 처리

또한 `.block()`, `toFuture().get()`, `join()`으로 즉시 기다리면 호출 스레드가 다시
점유되므로 핵심 이점이 사라진다.

## 변경 계획

OpenAI 연동은 외부 API 고위험 영역이므로 한 번에 전체 AI 흐름을 바꾸지 않고 단계적으로
진행한다.

### 1단계: 기준 측정과 동작 고정

- 현재 동시 AI 호출 수, 성공/실패 수, OpenAI 호출시간을 측정한다.
- `OpenAiClientImpl`의 응답 파싱, 빈 응답, timeout, retry 대상 상태 코드를 테스트로 고정한다.
- 현재 최대 시도 횟수와 timeout 의미를 명시한다.
- retry 가능한 오류와 즉시 실패해야 하는 4xx 오류를 구분한다.

### 2단계: OpenAI 전용 WebClient 구성

- `spring-boot-starter-webflux`를 추가하되 서버는 Spring MVC를 유지한다.
- OpenAI 전용 `WebClient` bean을 만든다.
- base URL, Authorization, content type, connect/read/response timeout을 한 곳에서 설정한다.
- connection pool 크기와 pending acquire 제한을 명시해 무제한 동시 호출을 막는다.
- API key와 요청 전문은 로그에 남기지 않는다.

### 3단계: AiClient 비동기 계약 변경

예상 계약:

```java
Mono<String> generateResponse(List<Map<String, Object>> messages);
```

- `OpenAiClientImpl`은 `Mono<String>`을 반환한다.
- retry는 retry 가능한 네트워크 오류, timeout, 제한된 5xx/429에만 적용한다.
- exponential backoff 동안 thread를 점유하지 않는다.
- 기존처럼 최종 실패 시 AI 응답을 보내지 않거나 fallback을 사용하는 동작을 유지한다.

### 4단계: 후속 AI 흐름을 비동기 체인으로 연결

- `AiChatUserService.processAiResponse()`가 OpenAI 응답 이후의 검증과 메시지 예약을
  `map`/`flatMap` 후속 처리로 연결한다.
- `AiChatCoordinatorService`의 thinking 상태는 비동기 작업 완료 시점에 해제한다.
- `AiGroupChatRevivalService`는 fallback 메시지 선택을 `onErrorResume` 또는 빈 응답 처리와
  연결한다.
- JPA 접근은 OpenAI 네트워크 대기 전에 끝내고, 응답 후 필요한 DB 작업은 명시적인
  transaction 경계에서 수행한다.
- 호출부에서 `.block()`, `.get()`, `.join()`을 사용하지 않는다.

### 5단계: 동시성 제한과 관측

- OpenAI 요청 동시 실행 수를 제한한다.
- queue 대기시간, active 요청 수, timeout, retry, 최종 실패를 metric으로 기록한다.
- 429 응답 증가 시 무작정 thread나 동시성을 늘리지 않는다.
- cancellation 시 불필요한 후속 메시지 전송이 실행되지 않는지 확인한다.

## 변경 후 목표 흐름

```text
AI 응답 필요 여부와 context를 짧은 DB transaction에서 준비
→ OpenAI WebClient 요청 등록
→ scheduler/worker thread 반환
→ 응답 도착 또는 non-blocking retry
→ 응답 검증
→ AI 메시지 전송 예약
→ 완료 시 thinking 상태 해제
```

## 검증 기준

### 기능 회귀

- 정상 응답에서 기존과 같은 AI 메시지를 생성하고 예약한다.
- 빈 응답, 예상하지 못한 응답 구조, timeout, 4xx, 429, 5xx 동작을 검증한다.
- 최대 retry 횟수와 backoff가 의도대로 적용된다.
- 최종 실패 시 기존 fallback/skip 동작을 유지한다.
- thinking 상태가 성공, 실패, cancellation 모두에서 해제된다.

### 성능 검증

동일한 제한된 thread 수에서 blocking 구현과 non-blocking 구현을 비교한다.

- 동시 OpenAI 요청 수
- executor active thread 수
- executor queue 크기와 대기시간
- 요청 성공률
- p50/p95/p99 전체 처리시간
- timeout과 retry 횟수

WebClient 적용 성공 기준은 단일 요청 latency 감소가 아니라, 느린 OpenAI 응답 상황에서도
executor queue와 thread 사용량이 안정적이고 추가 AI 작업이 과도하게 지연되지 않는 것이다.

## 적용하지 않을 범위

- 전체 Spring MVC 애플리케이션을 WebFlux 서버로 전환하지 않는다.
- JPA를 reactive persistence로 변경하지 않는다.
- 다른 `RestTemplate` 사용처를 함께 일괄 변경하지 않는다.
- OpenAI 모델, prompt, API 응답 형식, timeout/retry 정책은 검증 없이 변경하지 않는다.

## 구현 결과

### 적용한 변경

- `spring-boot-starter-webflux`와 Reactor test dependency를 추가했다.
- OpenAI 전용 `WebClient`, connection pool, connect/response timeout을 구성했다.
- OpenAI connection은 최대 10개로 제한하고 pending 요청은 최대 100개까지 허용했다.
- 기존 `openaiRestTemplate` bean을 제거했다.
- `AiClient.generateResponse()` 반환 타입을 `String`에서 `Mono<String>`으로 변경했다.
- `Thread.sleep()` 기반 재시도를 Reactor `Retry.backoff()`로 변경했다.
- 최대 시도 횟수 3회와 1초/2초 backoff는 유지했다.
- 429, 5xx, 네트워크 오류, timeout, 응답 구조 오류만 재시도한다.
- 일반 4xx는 같은 잘못된 요청을 반복하지 않고 즉시 종료한다.
- `AiChatUserService`, `AiChatCoordinatorService`, `AiGroupChatRevivalService`를 비동기 체인으로 연결했다.
- 빠른 AI 응답의 thinking 상태는 scheduler runnable 종료가 아니라 실제 publisher 종료 시점에 해제한다.
- Revival 후속 메시지 DB 처리는 Reactor event-loop를 막지 않도록 `boundedElastic`에서 실행한다.

### 변경 전후

```text
변경 전:
scheduler worker
→ RestTemplate 요청
→ 최대 60초 blocking
→ 실패 시 Thread.sleep(1초/2초)
→ 응답 처리
→ worker 반환

변경 후:
scheduler worker
→ WebClient 요청 subscribe
→ worker 즉시 반환
→ event-loop가 응답 감지 또는 non-blocking backoff
→ 응답 검증 및 메시지 예약
→ 실제 완료 시 thinking 상태 해제
```

### 보수적으로 유지한 경계

- 전체 서버는 Spring MVC를 유지한다.
- JPA와 기존 transaction 구조를 reactive persistence로 변경하지 않았다.
- OpenAI 요청 전 context 조회는 기존처럼 짧은 JPA transaction에서 완료한다.
- 기존 최대 3회 시도와 connect 5초/response 60초 timeout을 유지한다.
- OpenAI 최대 동시 connection은 기존 scheduler pool 크기와 같은 10개로 제한했다.
- 전체 AI 응답 생성 로직과 메시지 필터링 규칙은 변경하지 않았다.

### 테스트 결과

- OpenAI 정상 응답 텍스트 추출
- 5xx 발생 후 non-blocking backoff 재시도 및 3번째 성공
- 일반 4xx 비재시도
- 비동기 AI 호출 완료 전 thinking 상태 유지 및 완료 후 해제

대상 테스트:

```bash
./gradlew test \
  --tests 'core.domain.aiuser.client.OpenAiClientImplTest' \
  --tests 'core.domain.aiuser.service.AiChatCoordinatorServiceTest'
```

최신 `./scripts/agent-check.sh`는 254개 중 18개 실패, 5개 skip이다. 18개 실패는 기존
테스트 DB의 `ERROR: permission denied to create extension "pgroonga"` 문제이며, 이번
OpenAI WebClient 변경으로 확인된 실패는 없다. 추가된 성능 benchmark는 기본 검증에서
skip되고 명시적으로 활성화할 때만 실행된다.

## 남은 검증

- 실제 OpenAI 환경에서 timeout, 429, connection pool 대기 동작 확인
- 부하 상황에서 scheduler active thread와 queue 변화 측정
- OpenAI 호출시간, retry, 최종 실패 metric 추가 검토
- 실제 호출량에 따라 connection/pending 제한 조정

## RestTemplate/WebClient 전후 성능 비교

### 측정 목적

WebClient 전환은 OpenAI 자체 응답시간을 줄이기 위한 변경이 아니다. 동일한 외부 API 지연
상황에서 scheduler worker 점유가 실제로 줄어드는지 확인한다.

### 측정 방법

`OpenAiHttpClientConcurrencyBenchmarkTest`가 로컬 HTTP 서버를 시작하고 모든 요청에 750ms
지연을 적용한다.

```text
scheduler worker: 10개
동시 HTTP 요청: 10개
외부 서버 응답 지연: 750ms
WebClient connection 제한: 10개
측정 반복: warm-up 후 5회
```

10개 HTTP 요청이 서버에 도착한 직후 같은 scheduler에 빈 probe 작업을 제출하고 다음을
측정한다.

- `totalMs`: 10개 외부 요청 전체 완료시간
- `schedulerProbeDelayMs`: 외부 요청 처리 중 scheduler가 새 작업을 실행할 수 있을 때까지의 시간
- `followUpP50DelayMs`: 외부 요청 처리 중 추가 제출된 빠른 작업 50개의 p50 실행 지연
- `followUpP95DelayMs`: 외부 요청 처리 중 추가 제출된 빠른 작업 50개의 p95 실행 지연
- `followUpMaxDelayMs`: 외부 요청 처리 중 추가 제출된 빠른 작업 50개의 최대 실행 지연

실행 명령:

```bash
./gradlew test \
  --tests 'core.domain.aiuser.client.OpenAiHttpClientConcurrencyBenchmarkTest' \
  -DOPENAI_WEBCLIENT_BENCHMARK=true \
  --rerun-tasks
```

### 측정 결과

2026-06-14 로컬 환경 측정:

| 방식 | 전체 완료시간 중앙값 | scheduler probe 지연 중앙값 | follow-up p50 | follow-up p95 | follow-up max |
| --- | ---: | ---: | ---: | ---: | ---: |
| RestTemplate | 755ms | 751ms | 751ms | 751ms | 751ms |
| WebClient | 759ms | 0ms | 0ms | 0ms | 0ms |

5회 측정 상세:

| 회차 | RestTemplate 전체/probe/p95 | WebClient 전체/probe/p95 |
| ---: | ---: | ---: |
| 1 | 755ms / 753ms / 753ms | 761ms / 0ms / 0ms |
| 2 | 755ms / 751ms / 751ms | 759ms / 0ms / 0ms |
| 3 | 754ms / 752ms / 752ms | 760ms / 0ms / 0ms |
| 4 | 758ms / 750ms / 750ms | 758ms / 0ms / 0ms |
| 5 | 757ms / 750ms / 750ms | 757ms / 0ms / 0ms |

### 결과 해석

- 두 방식의 외부 요청 전체 완료시간은 거의 같다.
- WebClient가 외부 서버 응답속도를 높이지 않는다는 예상과 일치한다.
- RestTemplate는 10개 worker가 모두 HTTP 응답을 기다려 probe 작업도 약 750ms 기다렸다.
- WebClient는 요청 등록 후 worker를 반환해 외부 요청 처리 중에도 probe 작업이 즉시 실행됐다.
- 느린 외부 요청 뒤에 추가 제출된 빠른 작업 50개도 RestTemplate에서는 p95 기준 약 751ms
  밀렸고, WebClient에서는 즉시 실행됐다.
- 따라서 이번 변경의 확인된 효과는 단일 요청 latency 감소가 아니라 scheduler 고갈, queue
  적체, 후속 작업 연쇄 지연 완화다.

### 측정 한계

- 로컬 지연 서버 기반이므로 실제 OpenAI 네트워크, 429, 장애율은 반영하지 않는다.
- WebClient event-loop CPU 사용량과 실제 운영 메모리 사용량은 측정하지 않았다.
- 실제 OpenAI 호출량에서 connection 제한 10개와 pending 100개가 적절한지는 별도 측정이 필요하다.
