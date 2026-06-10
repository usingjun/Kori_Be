# 감지된 코딩 컨벤션

## 기본 스타일

- 패키지는 `core.domain.<domain>` 또는 `core.global.<area>` 구조를 사용한다.
- 도메인 내부는 대체로 `controller`, `service`, `repository`, `entity`, `dto` 하위 패키지로 나뉜다.
- 구현체가 분리된 서비스는 `service.impl` 아래에 `*ServiceImpl` 이름을 사용한다.
- Custom Repository 구현체는 `*RepositoryCustom`, `*RepositoryImpl`, `*RepositoryCustomImpl` 패턴을 사용한다.
- 클래스명은 Java PascalCase, 메서드/필드는 camelCase를 사용한다.
- 일부 기존 오탈자 또는 표기 흔들림이 있다. 예: `comunity`, `cahtRoom`, `cloum`, `proonga`, `Avaliable`. 기존 호환을 위해 임의 수정하면 안 된다.

## Lombok 사용

자주 확인되는 Lombok annotation:

- `@Getter`
- `@NoArgsConstructor`
- `@RequiredArgsConstructor`
- `@Builder`
- `@Slf4j`

Entity는 대체로 `@Getter`, `@NoArgsConstructor`를 사용하고, 필요한 경우 생성자 또는 정적 builder를 둔다. setter를 넓게 열기보다 도메인 메서드로 변경하는 패턴이 많이 보인다.

## Entity 스타일

- JPA annotation은 Jakarta namespace를 사용한다.
- ID는 대체로 `@GeneratedValue(strategy = GenerationType.IDENTITY)`를 사용한다.
- 연관관계는 `@ManyToOne(fetch = FetchType.LAZY)`가 자주 사용된다.
- enum은 `@Enumerated(EnumType.STRING)`으로 저장하는 패턴이 많다.
- 생성/수정 시각은 `@CreationTimestamp`, `@UpdateTimestamp`, 또는 Spring Data `@CreatedDate`, `@LastModifiedDate`가 혼재한다.
- 테이블/컬럼명은 snake_case를 사용한다.
- 일부 Entity에는 비즈니스 메서드가 들어 있다. 예: `User.updateRoleBasedOnProfile`, `ChatRoom.incrementMessageCount`, `ChatMessage.maskContentAsDeleted`.

## DTO 스타일

- DTO는 record와 class가 혼재한다.
- API request/response DTO는 도메인별 `dto` 패키지에 둔다.
- OpenAPI annotation인 `@Schema`, `@Operation`, `@Tag`, `@ApiResponse`가 API DTO와 Controller에 널리 사용된다.
- 공통 API 래퍼는 `core.global.dto.ApiResponse<T>` record를 사용한다.
- 에러 응답은 `ApiErrorResponse`를 사용한다.

## Controller 스타일

- REST API는 `@RestController`, 관리자 화면은 `@Controller`를 사용한다.
- API prefix는 `/api/v1`이 많이 쓰인다.
- 인증 사용자 접근은 `@AuthenticationPrincipal CustomUserDetails`와 `SecurityContextHolder`가 혼재한다.
- 요청 검증은 `@Valid`, `@Validated`, `@Positive` 등을 사용한다.
- 응답은 `ResponseEntity`를 많이 사용한다.
- 성공 응답은 `ApiResponse.success(...)`가 자주 쓰이지만, 일부 컨트롤러는 DTO를 직접 반환한다.
- 컨트롤러에 OpenAPI 설명과 커스텀 error docs annotation을 붙이는 패턴이 있다.

## Service 스타일

- 서비스는 생성자 주입을 사용한다. Lombok `@RequiredArgsConstructor`가 일반적이지만, 직접 생성자를 둔 클래스도 있다.
- 트랜잭션은 서비스 메서드 또는 서비스 클래스에 `@Transactional`을 붙인다.
- 조회 메서드는 `@Transactional(readOnly = true)`를 쓰는 패턴이 많다.
- 현재 로그인 사용자 조회는 `SecurityContextHolder.getContext().getAuthentication().getName()`로 email을 얻은 뒤 `UserRepository.findByEmail`로 찾는 방식이 자주 보인다.
- 비즈니스 오류는 `BusinessException`과 도메인별 error code enum으로 표현한다.
- 외부 API 호출, 이벤트 발행, 캐시 처리, DB 저장이 한 서비스 안에 함께 있는 경우가 있다. 변경 시 트랜잭션 경계와 외부 호출 순서를 확인해야 한다.

## Repository 스타일

- 기본 CRUD는 Spring Data JPA Repository를 사용한다.
- 복잡 조회는 QueryDSL `JPAQueryFactory`를 사용한다.
- PGroonga, phrase extraction, 특수 검색 쿼리는 JDBC template 또는 native SQL 성격의 문자열 쿼리를 사용한다.
- N+1 방지를 위해 일부 Repository method에 `@EntityGraph`를 사용한다.
- 목록 조회는 offset pagination보다 cursor pagination이 많이 보인다.

## 예외/에러 처리

- 도메인별 error code enum은 `core.global.enums.errorcode` 아래에 있다.
- `BusinessException`은 `AppError` 구현체를 받아 status, code, message를 제공하는 구조다.
- `GlobalExceptionHandler`는 validation, JSON parse/type mismatch, method not allowed, generic exception을 처리한다.
- WebSocket 예외는 별도 `GlobalWebSocketExceptionHandler`가 있다.
- 일부 임시/TODO 코드에서는 정확한 도메인 에러가 아닌 기존 에러 코드를 재사용하는 흔적이 있다.

## 보안 관련 스타일

- 경로 권한 목록은 `core.global.constants` 아래 `PermitAllPaths`, `AdminOnlyPaths`, `AIOnlyPaths`를 사용한다.
- 유사 이름의 `core.global.security.PermitAllPaths`, `core.global.security.AdminOnlyPaths`도 존재한다. 실제 `SecurityConfig`는 constants 패키지를 import한다.
- `JwtTokenFilter`가 SecurityContext를 구성한다.
- `SmokeTokenFilter`와 `PresenceActivityFilter`가 security filter chain에 같이 등록된다.
- 역할 기반 접근 제어는 SecurityConfig와 일부 `@PreAuthorize`가 혼재한다.

## 비동기/이벤트 스타일

- `@Scheduled`는 크롤링, 메트릭, 정리 작업, AI 온보딩 등에 사용한다.
- 트랜잭션 이후 처리가 필요한 곳은 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`를 사용한다.
- 일반 이벤트 리스너는 `@EventListener`를 사용한다.
- 채팅 메시지는 저장 후 이벤트를 발행하고, listener/sender가 실제 WebSocket/push 전송을 맡는 구조다.

## 설정 스타일

- 설정값은 `application-*.yml`에서 환경변수 placeholder를 주로 사용한다.
- `@Value` 기반 주입이 많고, 일부는 `@ConfigurationProperties`를 사용한다.
- 민감정보는 `${...}` placeholder로 표현되어 있으며, 문서화/로그 작성 시 값을 노출하면 안 된다.
- `application-dev.yml`, `application-local.yml`, `application-test.yml`가 존재한다.

## 테스트 스타일

- Mockito 기반 단위 테스트는 `@ExtendWith(MockitoExtension.class)`를 사용한다.
- 일부 테스트는 `@MockitoSettings(strictness = Strictness.LENIENT)`를 사용한다.
- Repository 테스트는 `@DataJpaTest`가 사용된다.
- 테스트 데이터 생성을 위한 builder/helper가 존재한다.
- 검증 스크립트는 `./scripts/agent-check.sh`이며 `./gradlew test jacocoTestReport`를 실행한다.

## 주석/로그 스타일

- 주석은 한국어 설명이 많고, 성능/리팩토링/TODO 맥락이 그대로 남아 있는 곳이 있다.
- 로그는 `@Slf4j` 또는 직접 `LoggerFactory`를 사용한다.
- 일부 debug 목적 로그가 info/error 레벨에 남아 있다. 운영 영향 여부는 별도 검토가 필요하다.

## 변경 시 지켜야 할 감지 규칙

- 기존 도메인 패키지 위치를 우선 사용한다.
- 새 기능은 Controller -> Service -> Repository 흐름을 유지한다.
- Entity를 직접 API 응답으로 반환하지 않는 기존 원칙을 유지한다.
- QueryDSL/PGroonga 기반 조회를 단순 Stream/filter로 바꾸지 않는다.
- 현재 사용자 식별 방식이 섞여 있으므로, 주변 코드와 같은 방식을 우선 따른다.
- error code enum과 `BusinessException`을 우선 사용한다.
- DB 스키마 변경은 Flyway migration으로만 한다.
- 보안, JWT, OAuth, 외부 API timeout/retry/circuit breaker 설정은 명시 요청 없이 바꾸지 않는다.
