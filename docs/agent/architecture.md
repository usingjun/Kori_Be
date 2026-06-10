# 아키텍처 분석

## 분석 범위

- 기준 코드: `src/main/java/core`, `src/main/resources`, `src/test/java`, `build.gradle`, `docker-compose.test.yml`, `scripts/agent-check.sh`
- 확인된 규모: 운영 Java 소스 605개, 테스트 Java 소스 14개, Flyway SQL 마이그레이션 59개
- 이 문서는 실제 코드에서 확인되는 구조만 정리한다. 의도가 코드로 명확하지 않은 부분은 별도로 "불명확한 부분"에 적었다.

## 애플리케이션 형태

- Java 17, Spring Boot 3.5.4 기반의 단일 Spring Boot 백엔드 애플리케이션이다.
- 진입점은 `core.CoreApplication`이다.
- `@SpringBootApplication`, `@EnableJpaAuditing`, `@EnableFeignClients(basePackages = "core.global.apple.client")`, `@ConfigurationPropertiesScan`, `@EnableAsync`, `@EnableScheduling`이 활성화되어 있다.
- README에는 과거 MSA 이후 Monolithic 재구성이라는 설명이 있으나, 현재 코드 구조는 하나의 Spring Boot 애플리케이션 안에 도메인 패키지를 나눈 형태다.

## 패키지 구조

- `core.domain.*`: 업무 도메인 기능
- `core.global.*`: 보안, 설정, 공통 DTO, 예외, Redis, WebSocket, 이미지, 메트릭, 외부 연동 등 공통 기능
- `src/main/resources/db/migration`: Flyway 마이그레이션
- `src/main/resources/templates`: Thymeleaf 관리자 화면과 이메일 템플릿
- `infra/elasticsearch`, `infra/elasticsearch-events`: Elasticsearch 인덱스 템플릿과 스크립트
- `docker/init`, `docker-compose.test.yml`: 테스트용 PostgreSQL/Redis 보조 구성

확인된 도메인 패키지는 다음과 같다.

- `user`, `userdevicetoken`
- `chat`
- `post`, `comment`, `bookmark`, `board`, `poll`
- `maincontent`
- `notification`, `usernotificationsetting`
- `payment`
- `admin`
- `aiuser`

## 계층 구조

코드는 전반적으로 Controller -> Service -> Repository -> Entity 흐름을 따른다.

- Controller는 `@RestController` 또는 `@Controller`로 HTTP/API와 관리자 Thymeleaf 화면 요청을 받는다.
- WebSocket 메시지는 `@Controller`와 `@MessageMapping`을 사용한다.
- Service는 `@Service`와 `@Transactional`을 중심으로 트랜잭션 경계를 잡는다.
- Repository는 Spring Data JPA Repository와 QueryDSL/JDBC 기반 Custom Repository가 섞여 있다.
- Entity는 JPA Entity로 PostgreSQL 테이블에 매핑된다.
- API 응답은 주로 DTO와 `core.global.dto.ApiResponse`를 사용한다. 모든 응답이 동일 래퍼를 쓰는 것은 아니며, 일부 컨트롤러는 DTO를 직접 반환한다.

## 요청 처리 흐름

일반 REST 요청의 전형적인 흐름은 다음과 같다.

1. 클라이언트가 `/api/v1/**`, `/admin/**`, `/health`, `/ws` 등으로 요청한다.
2. `SecurityConfig`의 SecurityFilterChain이 CORS, JWT, Smoke 토큰, Presence 필터를 적용한다.
3. Controller가 요청 DTO와 path/query parameter를 받는다.
4. Service가 현재 사용자 조회, 비즈니스 규칙, 트랜잭션, 이벤트 발행을 처리한다.
5. Repository 또는 외부 클라이언트가 데이터 조회/저장 및 외부 API 호출을 수행한다.
6. DTO 또는 `ApiResponse` 형태로 응답한다.
7. 예외는 `GlobalExceptionHandler`에서 `ApiErrorResponse`로 변환된다.

## 보안 구조

- `SecurityConfig`에서 stateless 세션, CSRF 비활성화, HTTP Basic/Form Login 비활성화를 설정한다.
- 공개 경로는 `core.global.constants.PermitAllPaths`를 사용한다.
- 관리자 경로는 `AdminOnlyPaths`로 분리되어 `ROLE_ADMIN`이 필요하다.
- AI 전용 경로는 `AIOnlyPaths`로 분리되어 `ROLE_AI` 또는 `ROLE_ADMIN`이 필요하다.
- 나머지 요청은 `ROLE_VISITOR`, `ROLE_USER`, `ROLE_ADMIN` 중 하나가 필요하다.
- JWT 처리는 `JwtTokenFilter`, `JwtTokenProvider`, `JwtAuthenticationEntryPoint`에서 수행한다.
- Refresh token과 access token blacklist는 Redis를 사용한다.
- WebSocket 보안은 `StompChannelInterceptor`를 통해 inbound 채널에 적용되는 구조다. `SecurityConfig` 안의 메시지 보안 설정은 주석 처리되어 있다.

## 데이터 저장소

- 주 저장소는 PostgreSQL이다.
- JPA/Hibernate의 `ddl-auto`는 test 설정에서 `none`이며, 스키마 변경은 Flyway 마이그레이션으로 관리된다.
- QueryDSL은 복잡 조회와 커서 기반 조회에 사용된다.
- 일부 검색/추천 쿼리는 `NamedParameterJdbcTemplate` 또는 JDBC를 직접 사용한다.
- Redis는 refresh token/blacklist, 채팅/번역 캐시, 최근 검색, presence/metric 관련 데이터에 사용된다.
- Caffeine dependency는 존재하지만, 코드에서 주요 캐시 계층으로 일관되게 쓰이는지는 명확하지 않다.

## 검색 구조

- 게시글 검색은 `PostSearchService`와 `PostSearchRepositoryCustomImpl`에서 처리한다.
- 메인 콘텐츠 검색은 `MainContentSearchService`와 `MainContentSearchRepository` 계열에서 처리한다.
- PGroonga 관련 SQL 함수와 인덱스가 Flyway 마이그레이션에 포함되어 있다.
- 자동완성은 메모리 인덱스 우선 조회 후 DB 보충 방식이다.
- 검색 페이징은 score, createdAt, id를 포함한 커서 기반 방식이다.

## 실시간 채팅 구조

- WebSocket endpoint는 `/ws`이다.
- STOMP application prefix는 `/app`, simple broker prefix는 `/topic`이다.
- `ChatWebSocketController`는 `/chat.sendMessage`, `/chat.markAsRead`, `/chat.sendMedia` 등의 메시지를 받는다.
- `ChatMessageService`는 메시지 저장, 수신자 컨텍스트 구성, 번역, 이벤트 발행을 처리한다.
- `ChatEventListener`, `FastSocketSender`, Redis chat publisher/subscriber 계열이 실시간 전송 흐름에 참여한다.
- 채팅 번역은 `ChatTranslationService`와 `TranslationService`를 함께 사용하며, Redis 캐시와 외부 번역 API 호출이 섞여 있다.

## 비동기, 스케줄러, 이벤트

- `@EnableAsync`, `@EnableScheduling`이 활성화되어 있다.
- 크롤러, 인기 키워드 배치, 알림 정리, 사용자 메트릭, AI 온보딩, 이미지 정리 등에 `@Scheduled`가 사용된다.
- `ApplicationEventPublisher`, `@EventListener`, `@TransactionalEventListener`가 알림, AI 응답, 검색 인덱싱, 이미지 검수 이벤트에 사용된다.
- 메일 발송은 `AsyncMailDispatcher`와 resilience4j retry를 사용한다.

## 외부 연동

확인된 외부 연동은 다음과 같다.

- Apple OAuth: Feign Client와 Apple public key/client secret 관련 서비스
- Google OAuth: OAuth 설정과 Google service
- Firebase Admin: FCM push notification
- NCP Object Storage/S3 호환 API: 이미지 업로드, 삭제, presigned URL, CDN URL 생성
- Google Translate 또는 호환 번역 API: 게시글/댓글/채팅 번역
- Google Maps API: 위치/지오코딩 계열
- Google Perspective API와 Sightengine: 관리자/이미지/콘텐츠 검수 계열
- OpenAI API: AI 사용자 응답 생성
- Apple/Google IAP: 인앱 결제 검증과 webhook 처리
- Jsoup/HtmlUnit: K-news와 외부 콘텐츠 크롤링
- Micrometer/Prometheus: 지표 수집

## 관리자 화면

- `core.domain.admin.controller`에 다수의 `@Controller` 기반 Thymeleaf 관리자 화면이 있다.
- 템플릿은 `src/main/resources/templates/admin` 아래에 있다.
- 관리자 기능은 유저, 게시글, 댓글, 채팅, 신고, 메인 콘텐츠, 크롤링 데이터, 이미지 검수, 푸시, 메트릭, 모니터링 등을 다룬다.

## 테스트와 검증

- `scripts/agent-check.sh`는 `./gradlew test jacocoTestReport`를 실행한다.
- 테스트는 Mockito 기반 단위 테스트와 일부 `@DataJpaTest`가 확인된다.
- 테스트용 docker compose는 PostgreSQL `groonga/pgroonga:latest`와 Redis 7을 제공한다.
- `src/test/resources/schema-test.sql`이 존재한다.

## 불명확한 부분

- 현재 실제 운영 배포 구조는 코드만으로 확정할 수 없다. Dockerfile과 README는 있으나 런타임 인프라 전체 구성이 이 저장소에 모두 있지는 않다.
- Elasticsearch 관련 템플릿과 스크립트가 있지만, 애플리케이션 Java 코드에서 Elasticsearch 클라이언트를 직접 사용하는 흐름은 확인되지 않았다.
- README에는 Spring Batch 배지가 있으나 `build.gradle`에는 Spring Batch dependency가 확인되지 않는다.
- Caffeine dependency는 있으나 핵심 캐시로 사용되는 구체 흐름은 코드 훑기만으로 명확하지 않다.
- 일부 관리자/Smoke/OAuth debug endpoint의 운영 노출 방식은 보안 경로 설정과 프로파일 설정을 함께 봐야 정확히 판단할 수 있다.
