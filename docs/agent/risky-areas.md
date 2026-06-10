# 위험 영역

## 작업 전 공통 주의

- 이 프로젝트는 인증, 결제, 실시간 채팅, 외부 스토리지, push, 크롤링, 검색 DB 함수가 한 애플리케이션 안에 함께 있다.
- 변경 전에는 관련 Controller, Service, Repository, Entity, migration, 테스트를 함께 확인해야 한다.
- 비즈니스 로직 변경 시 테스트 추가/수정이 필요하다.
- 검증 명령은 `./scripts/agent-check.sh`이다.

## 인증/인가/JWT

위험 근거:

- `SecurityConfig`, `JwtTokenFilter`, `JwtTokenProvider`, `JwtAuthenticationEntryPoint`, Redis token blacklist/refresh token이 요청 인증의 핵심이다.
- `SecurityConfig`는 공개/관리자/AI/일반 사용자 경로를 path list로 분리한다.
- 나머지 모든 요청은 `VISITOR`, `USER`, `ADMIN` 권한을 허용한다.
- 클라이언트 준비 이후 VISITOR와 USER 권한 분리를 다시 조정한다는 TODO가 있다.

주의할 점:

- `PermitAllPaths`, `AdminOnlyPaths`, `AIOnlyPaths` 수정은 전체 API 노출 범위를 바꿀 수 있다.
- `SecurityContextHolder`와 `@AuthenticationPrincipal` 사용이 혼재하므로 인증 주체 변경은 여러 도메인에 영향을 준다.
- token 로그, OAuth secret, JWT secret, Firebase/NCP/GCP key는 절대 로그나 문서에 값으로 남기면 안 된다.

## WebSocket/채팅

위험 근거:

- `/ws`, `/app`, `/topic` 기반 STOMP 구조다.
- `ChatMessageService`는 DB 저장, 번역, Redis/cache, 이벤트 발행, push 대상 구성, 이미지/S3 연동까지 넓은 책임을 가진다.
- WebSocket inbound/outbound executor pool과 queue 설정이 코드에 고정되어 있다.
- `ChatWebSocketController.deleteMessage`는 현재 로그만 남기는 것으로 보인다.
- 레거시 성능 테스트용 `/chat.sendMessageBad` 경로가 남아 있다.

주의할 점:

- 메시지 저장과 실시간 전송 순서를 바꾸면 중복 발송, 누락, 번역 캐시 불일치가 생길 수 있다.
- 읽음 처리와 unread count는 1:1/그룹 채팅 규칙이 다르므로 함께 검증해야 한다.
- WebSocket 보안은 HTTP Security와 별도 흐름이므로, HTTP API만 보고 권한을 판단하면 안 된다.

## 결제/IAP

위험 근거:

- Apple/Google purchase verification, webhook, entitlement, item grant, bonus grant가 연결되어 있다.
- 구매 검증은 외부 API 호출과 DB side effect를 함께 수행한다.
- Android 상품 미매핑 시 `UserErrorCode.INVALID_FOLLOW_STATUS`를 던지는 TODO가 있다.

주의할 점:

- 멱등성 기준은 iOS transactionId, Android purchaseToken이다. 변경 시 중복 지급 위험이 있다.
- entitlement와 consumable item 부여 로직을 분리 없이 수정하면 premium/boost/frame 지급이 꼬일 수 있다.
- webhook event 저장/처리 정책은 테스트 없이 변경하면 환불, 갱신, 만료 반영 누락 위험이 있다.

## 이미지/S3/CDN/삭제 보상

위험 근거:

- 이미지 서비스는 NCP Object Storage/S3 호환 API, CDN URL, presigned URL, staging key, thumbnail URL을 다룬다.
- 기본 이미지(`default/`)는 삭제 제외 처리된다.
- 삭제 실패를 `FailedImageCleanup`으로 기록하고 스케줄러가 재시도하는 흐름이 있다.
- 현재 작업트리에 이미지 삭제 실패 보상 관련 미커밋 변경이 존재한다.

주의할 점:

- URL과 key 변환 로직을 잘못 바꾸면 실제 스토리지 객체를 잘못 삭제할 수 있다.
- 폴더 삭제와 bulk delete는 외부 API 실패 부분 성공을 고려해야 한다.
- CDN URL 생성 규칙을 바꾸면 기존 클라이언트 이미지 표시가 깨질 수 있다.

## 검색/PGroonga/Flyway

위험 근거:

- 게시글/메인 콘텐츠 검색은 PGroonga 함수, score, phrase extraction, custom SQL, QueryDSL을 함께 사용한다.
- Flyway에 PGroonga baseline/index/function 관련 migration이 여러 개 있다.
- cursor는 score, timestamp, id를 조합한다.

주의할 점:

- 검색 정렬 조건이나 cursor payload를 바꾸면 페이지 중복/누락이 생길 수 있다.
- PGroonga 함수와 인덱스는 테스트 DB에도 준비되어야 한다.
- 단순 JPA 조회로 대체하면 성능과 검색 정확도가 크게 달라질 수 있다.

## 크롤링/외부 사이트

위험 근거:

- 여러 `@Scheduled` 크롤러가 Jsoup/HtmlUnit으로 외부 사이트 HTML/RSS를 파싱한다.
- 외부 사이트 DOM 변경에 취약하다.
- 크롤링 결과는 `CrawledData`와 관리자 승인/병합 흐름으로 이어진다.

주의할 점:

- selector나 URL 변경은 실제 사이트 응답 기준으로 검증해야 한다.
- 중복 수집 방지 로직을 변경하면 데이터 중복 또는 누락이 생길 수 있다.
- 외부 사이트 호출 timeout과 scheduler cron 변경은 운영 부하에 영향을 줄 수 있다.

## 알림/푸시

위험 근거:

- in-app notification, user notification setting, Firebase push, device token cleanup, notification metrics가 연결되어 있다.
- 이벤트 기반 알림 생성과 push 전송이 섞여 있다.
- 오래된 알림 정리 스케줄러가 있다.

주의할 점:

- 알림 타입 enum 변경은 DB check constraint, migration, 클라이언트 표시 로직과 함께 봐야 한다.
- 기기 토큰 중복 정리와 Firebase 실패 응답 처리는 push 도달률에 직접 영향을 준다.
- 알림 설정 초기화 로직을 바꾸면 기존 유저의 설정이 덮일 수 있다.

## 관리자 화면/운영 기능

위험 근거:

- `core.domain.admin`은 실제 데이터 삭제, 신고 처리, 유저 삭제, AI 유저 생성, 크롤링 승인, 이미지 리뷰, push 발송을 수행한다.
- Thymeleaf 화면과 REST성 endpoint가 섞여 있다.
- 일부 OAuth debug endpoint와 health check endpoint가 있다.

주의할 점:

- 관리자 컨트롤러는 화면 redirect와 서비스 side effect가 함께 있으므로 API 스타일 변경이 화면 동작을 깨뜨릴 수 있다.
- `@PreAuthorize`와 SecurityConfig path rule이 함께 적용되므로 둘 다 확인해야 한다.
- 운영 debug endpoint 노출 여부는 프로파일/권한과 함께 확인해야 한다.

## 외부 API와 회복성 설정

위험 근거:

- TranslationService는 resilience4j circuit breaker fallback을 사용한다.
- 메일 발송은 retry를 사용한다.
- Firebase, NCP S3, Google/Apple OAuth, Google Maps, Perspective, Sightengine, OpenAI, IAP 외부 API가 있다.
- `project-context.md`에서 timeout/retry/circuit breaker 정책 임의 변경 금지가 명시되어 있다.

주의할 점:

- timeout/retry/circuit breaker 변경은 장애 전파와 지연 시간을 바꾼다.
- fallback이 원문 반환 또는 빈 응답으로 동작하는 경우가 있어 사용자 경험이 달라질 수 있다.
- 외부 API 키/secret은 환경변수 기반이며 값 자체를 저장소나 로그에 남기면 안 된다.

## 스케줄러/메트릭/Presence

위험 근거:

- 사용자 활동, lastSeenAt, active users, dwell time, cohort, peak hour, inactive user, onboarding share 등 다수 메트릭이 있다.
- `PresenceActivityFilter`는 SecurityFilterChain에 포함되어 있고 스케줄러도 포함한다.
- Micrometer/Prometheus endpoint가 설정되어 있다.

주의할 점:

- presence 기준을 바꾸면 온라인 상태 API와 메트릭이 함께 달라진다.
- 고빈도 scheduler나 filter 로직은 Redis/DB 부하를 만들 수 있다.
- metric 이름/tag 변경은 대시보드와 alert를 깨뜨릴 수 있다.

## DB 마이그레이션

위험 근거:

- Flyway migration 파일이 많고, 일부 파일명에 날짜/번호 형식이 혼재한다.
- DB constraint, enum-like check constraint, PGroonga function/index, IAP table, notification type, chat unique index 등이 migration으로 관리된다.

주의할 점:

- 기존 migration 수정은 이미 적용된 환경에서 위험하다. 새 변경은 새 migration으로 추가해야 한다.
- Entity 변경과 migration이 어긋나면 런타임 오류가 발생한다.
- check constraint가 있는 enum 추가는 Java enum만 바꿔서는 부족할 수 있다.

## 테스트 커버리지

위험 근거:

- 운영 Java 소스 605개에 비해 테스트 Java 소스는 14개다.
- 핵심 도메인 일부에는 Mockito 단위 테스트와 DataJpaTest가 있지만, 모든 도메인이 촘촘히 덮여 있지는 않다.
- JaCoCo 최소 기준은 build.gradle에서 5%로 설정되어 있다.

주의할 점:

- 결제, 인증, WebSocket, 검색, 이미지 삭제, 관리자 삭제류 변경은 기존 테스트만으로 회귀를 충분히 잡기 어렵다.
- 비즈니스 로직 변경 시 주변 패턴에 맞춘 단위 테스트 또는 repository test를 추가해야 한다.

## 현재 작업트리 상태 관련 위험

확인 시점의 `git status --short`에는 이미지 삭제 실패 보상 관련 신규/수정 파일, `GlobalExceptionHandler`, `S3ImageStorageClient`, `application-dev.yml`, 테스트 파일, `AGENTS.md`, `docs/`, `scripts/`의 변경이 보였다.

주의할 점:

- 이 변경들이 사용자 작업인지 이전 작업인지 확정할 수 없으므로 임의로 되돌리면 안 된다.
- 문서 외 작업을 할 때는 같은 파일을 건드리기 전에 최신 내용을 다시 읽어야 한다.
- `application-dev.yml`은 배포/환경 설정 범주이므로 명시 요청 없이 수정하지 않는다.

## 불명확한 부분

- 실제 운영 인프라, secret 관리 방식, Prometheus/Grafana dashboard, 배포 파이프라인 전체는 저장소만으로 확정할 수 없다.
- Elasticsearch 템플릿/스크립트는 존재하지만 Java 코드에서 직접 연동하는 부분은 확인되지 않았다.
- 일부 TODO/DEBUG/레거시 경로가 실제 운영에서 허용되는지 여부는 배포 설정과 운영 절차를 확인해야 한다.
