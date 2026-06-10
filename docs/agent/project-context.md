## Tech Stack

### Language / Framework
- Java 17
- Spring Boot 3.5.4
- Gradle
- Spring Dependency Management 1.1.7
- Spring Cloud 2025.0.0

### Spring Modules
- Spring Web MVC
- Spring Security
- Spring OAuth2 Client
- Spring OAuth2 Resource Server
- Spring Data JPA
- Spring Data Redis
- Spring WebSocket
- Spring Validation
- Spring Actuator
- Spring Mail
- Spring AOP
- Spring Retry
- Thymeleaf

### Database / Persistence
- PostgreSQL
- Spring Data JPA
- QueryDSL 5.1.0
- Flyway

### Auth / Security
- Spring Security
- OAuth2 Login
- OAuth2 Resource Server
- JWT using jjwt 0.11.5
- Google Authenticator 2FA

### Cache / Messaging / Realtime
- Redis
- Caffeine Cache
- WebSocket
- Spring Security Messaging

### External API / Integration
- Spring Cloud OpenFeign
- Feign Form
- AWS SDK v2 S3
- Google Cloud Translate
- Firebase Admin
- Google Android Publisher API
- Jsoup
- HtmlUnit
- KOMORAN

### Resilience / Observability
- Resilience4j
- Micrometer
- Prometheus
- Micrometer Tracing Brave
- Spring Boot Actuator
- Logback / Janino

### API Docs
- Springdoc OpenAPI UI 2.8.4

### Test / Quality
- JUnit 5
- Spring Boot Test
- Spring Security Test
- Mockito Inline
- JaCoCo
- SonarQube / SonarCloud

## Architecture
- Controller -> Service -> Repository 계층 구조 유지
- Controller는 비즈니스 로직을 가지지 않음
- Service는 트랜잭션 경계를 담당
- Repository는 데이터 접근만 담당
- Entity를 API 응답으로 직접 반환하지 않음
- Request/Response DTO 사용
- 도메인별 기존 패키지 구조 우선

## Coding Convention
- 기존 코드의 네이밍, 패키지 위치, 예외 처리 방식 유지
- Lombok 사용 방식은 기존 코드 기준 유지
- DTO가 record 기반이면 record 유지
- Builder 패턴을 쓰는 곳은 기존 방식 유지
- QueryDSL이 쓰이는 조회 로직은 임의로 Stream/filter 방식으로 변경 금지
- 새 유틸 클래스 생성보다 기존 컴포넌트 재사용 우선

## Forbidden
- 요청 없이 전체 아키텍처 변경 금지
- 요청 없이 DB 스키마 변경 금지
- 요청 없이 application.yml, secrets, Docker, CI/CD 파일 수정 금지
- Controller에서 Repository 직접 호출 금지
- Entity 직접 JSON 응답 반환 금지
- N+1이 발생할 수 있는 단순 findAll + loop 조회 금지
- 테스트 없이 비즈니스 로직 변경 금지
- 기존 API 응답 형식 임의 변경 금지
- 인증/인가 로직 임의 완화 금지
- Flyway 없이 DB 스키마 직접 변경 금지
- 민감정보(token, password, OAuth secret, AWS/GCP key) 로그 출력 금지
- 외부 API 연동부의 timeout/retry/circuit breaker 정책 임의 변경 금지
- SecurityConfig, JwtProvider, OAuth2 관련 코드는 명시 요청 없이는 수정 금지
- S3/Firebase/Google API 설정 및 인증 흐름 임의 변경 금지

## Quality / Validation Rules
- 변경 후 최소 `./gradlew test`를 실행한다.
- 테스트가 실패하면 실패 원인을 요약하고, 임의로 우회하지 않는다.
- JaCoCo 리포트가 필요한 작업이면 `./gradlew jacocoTestReport`를 실행한다.
- SonarCloud 관련 설정은 요청 없이는 변경하지 않는다.

## Detected Project-Specific Rules

### Runtime Shape
- 현재 코드는 단일 Spring Boot 애플리케이션 안에서 `core.domain.*`와 `core.global.*`로 나뉜 구조를 따른다.
- README의 과거 MSA 언급만으로 현재 구조를 MSA로 가정하지 않는다.
- 새 기능은 기존 도메인 패키지의 `controller`, `service`, `repository`, `entity`, `dto` 위치를 우선 사용한다.
- `core.global.*`에는 보안, 설정, 공통 DTO, 예외, Redis, WebSocket, 이미지, 메트릭, 외부 연동 등 횡단 관심사를 둔다.

### Package / Naming
- 기존 오탈자 패키지명이나 클래스명은 임의 수정하지 않는다.
- 예: comunity, cahtRoom, cloum, proonga, Avaliable 등은 호환성을 위해 그대로 둔다.
- 새 이름을 만들 때는 Java PascalCase, camelCase, snake_case DB 컬럼명 등 기존 관례를 따른다.
- 구현체가 이미 `service.impl` 또는 `repository.impl` 아래에 있으면 같은 위치와 `*Impl` 패턴을 유지한다.

### API / Response
- API 응답은 주변 코드가 `core.global.dto.ApiResponse`를 쓰는지, DTO를 직접 반환하는지 먼저 확인하고 일관성을 맞춘다.
- `Entity`를 직접 API 응답으로 반환하지 않는다.
- 기존 API path, response shape, status code를 호환성 검토 없이 변경하지 않는다.
- Controller에는 비즈니스 로직을 넣지 않고, Service로 위임한다.

### Domain Logic
- `User` role/profile completion, follow/block, chat participant status, notification setting, payment entitlement처럼 Entity와 Service에 흩어진 도메인 규칙은 변경 전 관련 흐름을 함께 확인한다.
- 현재 사용자 식별은 `@AuthenticationPrincipal CustomUserDetails`와 `SecurityContextHolder`가 혼재하므로 주변 코드의 방식을 우선 따른다.
- `BusinessException`과 `core.global.enums.errorcode.*` 기반 예외 처리 방식을 우선 사용한다.

### Security
- SecurityConfig, JwtTokenFilter, JwtTokenProvider, PermitAllPaths, AdminOnlyPaths, AIOnlyPaths는 명시 요청 없이는 수정하지 않는다.
- WebSocket 보안은 HTTP Security와 별도 흐름이므로 StompChannelInterceptor를 함께 확인한다.
- token, OAuth secret, JWT secret, Firebase/NCP/GCP key는 로그/문서/테스트 출력에 남기지 않는다.
- `core.global.constants.PermitAllPaths`, `AdminOnlyPaths`, `AIOnlyPaths`와 유사한 이름의 `core.global.security.*Paths`가 함께 존재하므로 실제 import 경로를 확인한다.
- `SmokeTokenFilter`와 `PresenceActivityFilter`가 SecurityFilterChain에 함께 등록되어 있으므로 인증 필터 순서 변경은 영향 범위를 먼저 설명한다.

### Database / Migration
- 기존 Flyway migration은 수정하지 않는다.
- DB 변경이 필요하면 새 migration 파일을 추가한다.
- Java enum 변경 시 DB check constraint/Flyway 변경 필요 여부를 함께 확인한다.
- Entity 변경과 migration이 어긋나지 않는지 확인한다.
- 기존 migration 파일명 형식이 혼재하므로 새 파일명은 주변 최신 migration 규칙을 먼저 확인한다.

### Query / Search
- PGroonga, QueryDSL, cursor pagination 기반 검색 로직을 단순 JPA 조회나 Java Stream filter로 대체하지 않는다.
- cursor pagination은 score, createdAt, id 조합을 유지한다.
- 검색 관련 변경은 중복/누락 페이지 가능성을 검토한다.
- `PostSearchService`, `PostSearchRepositoryCustomImpl`, `MainContentSearchService` 주변 변경은 PGroonga 함수, custom SQL, memory suggest index, Redis recent search 흐름을 함께 확인한다.

### Image / Storage
- 이미지 URL과 object key 변환 로직은 명시 요청 없이는 수정하지 않는다.
- default/ 경로 이미지는 삭제 대상에서 제외한다.
- 이미지 삭제 실패 보상 로직은 FailedImageCleanup 흐름과 함께 확인한다.
- CDN URL, thumbnail URL, staging key 규칙 변경은 클라이언트 표시와 실제 object 삭제에 영향을 줄 수 있으므로 먼저 영향 범위를 정리한다.

### Chat / WebSocket
- 메시지 저장, 번역, 이벤트 발행, WebSocket 전송, push 전송 순서를 임의로 바꾸지 않는다.
- unread count, read status 변경은 1:1 채팅과 그룹 채팅을 함께 검증한다.
- /chat.sendMessageBad 같은 레거시/성능 테스트 경로는 명시 요청 없이는 제거하지 않는다.
- `ChatWebSocketController.deleteMessage`는 현재 로그만 남기는 흐름으로 보이므로 삭제 기능 변경 시 실제 구현 위치를 먼저 확인한다.
- WebSocket executor pool, queue, broker prefix(`/app`, `/topic`) 변경은 성능과 호환성 영향이 크므로 명시 요청 없이는 수정하지 않는다.

### Payment / IAP
- Apple/Google IAP 검증, webhook, entitlement, item grant 로직은 명시 요청 없이는 수정하지 않는다.
- 결제 멱등성 기준은 iOS transactionId, Android purchaseToken이다.
- 구매/환불/갱신 관련 변경은 테스트 없이 진행하지 않는다.
- product catalog와 store product id 전체 목록은 코드만으로 확정하지 않는다.
- Android 상품 미매핑 시 기존 TODO성 error code 사용이 있으므로 에러 정책 변경은 별도 확인한다.

### External APIs
- Translation, Firebase, NCP S3, Google/Apple OAuth, Google Maps, Perspective, Sightengine, OpenAI, IAP 외부 API 연동은 timeout/retry/circuit breaker 정책을 임의 변경하지 않는다.
- fallback 동작을 바꾸면 사용자 응답이 달라질 수 있으므로 반드시 명시한다.
- Jsoup/HtmlUnit 기반 크롤러 selector, URL, scheduler cron 변경은 외부 사이트 응답과 중복 수집 방지 로직을 함께 검증한다.

### Async / Event / Scheduler
- `@Scheduled`, `@EventListener`, `@TransactionalEventListener`, `ApplicationEventPublisher`가 연결된 흐름은 트랜잭션 커밋 전후 실행 시점을 확인한다.
- 알림, AI 응답, 검색 인덱싱, 이미지 검수/정리, 메트릭 scheduler 변경은 side effect와 재시도 가능성을 함께 검토한다.
- metric 이름이나 tag 변경은 Prometheus/Grafana dashboard와 alert에 영향을 줄 수 있으므로 명시 요청 없이는 바꾸지 않는다.

### Admin
- 관리자 컨트롤러는 Thymeleaf 화면 redirect와 실제 side effect가 섞여 있으므로 API 스타일로 임의 변경하지 않는다.
- 관리자 삭제/승인/푸시/이미지 검수 기능은 운영 데이터에 직접 영향이 있으므로 테스트와 영향 범위를 먼저 정리한다.
- 관리자 debug/health/OAuth endpoint 노출 여부는 SecurityConfig, `@PreAuthorize`, profile 설정을 함께 확인한다.

### Testing
- 운영 소스 대비 테스트 수가 적으므로 비즈니스 로직 변경 시 관련 테스트를 추가한다.
- 인증, 결제, WebSocket, 검색, 이미지 삭제, 관리자 삭제류 변경은 기존 테스트만으로 충분하지 않을 수 있다.
- 문서만 변경한 경우에도 요청된 검증 명령 실행 여부와 결과를 요약한다.
- PGroonga repository 테스트는 test DB의 `pgroonga` extension 권한/설치 상태에 의존한다.

### Unclear / Do Not Assume
- 실제 운영 인프라, secret 관리 방식, Prometheus/Grafana dashboard, 배포 파이프라인 전체는 이 저장소만으로 확정하지 않는다.
- Elasticsearch 템플릿과 스크립트가 있어도 Java 코드에서 직접 사용하는 흐름이 확인되지 않으면 사용 중이라고 단정하지 않는다.
- Caffeine dependency가 있어도 주요 캐시 계층으로 일관되게 쓰인다고 단정하지 않는다.
- README의 Spring Batch 표기가 있어도 `build.gradle`에 dependency가 확인되지 않으면 현재 stack으로 가정하지 않는다.
