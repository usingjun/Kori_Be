# Service Refactoring Progress

## 목표

대형 Service가 인증, 프로필, 탈퇴, 조회 등 서로 다른 유스케이스를 동시에 담당하지 않도록 책임을 분리한다.

리팩터링 원칙:

- 기존 API 요청과 응답 계약은 유지한다.
- 기능 단위 Application Service를 단일 진입점으로 사용한다.
- 트랜잭션 경계와 도메인 작업 순서를 명시한다.
- 한 번에 하나의 유스케이스만 분리하고 회귀 테스트 후 다음 단계로 이동한다.
- 단순히 클래스를 나누기보다 기존 대형 Service의 의존성과 책임을 실제로 제거한다.

## 완료: 1단계 회원 탈퇴 유스케이스 분리

### 적용 패턴

- Facade Pattern
- Application Service 분리

### 변경 전

`UserService`가 로그인, 프로필, 사용자 조회와 함께 회원 탈퇴도 처리했다.

회원 탈퇴 메서드는 Apple 토큰 폐기와 Payment, Report, Vote, Chat, Community, Image,
Notification 데이터 삭제를 직접 조율했고 탈퇴 전용 Repository에도 의존했다.

### 변경 후

`UserWithdrawalService.withdraw()`가 회원 탈퇴 유스케이스의 단일 진입점이다.

```text
LoginRegisterController
→ UserWithdrawalService
  → Apple 계정 폐기
  → 결제 데이터 정리
  → 신고·투표 데이터 정리
  → 채팅 데이터 정리
  → 커뮤니티 데이터 정리
  → 사용자 소유 데이터 정리
  → 사용자 삭제
  → UserWithdrawalEvent 발행
```

`UserService`에서는 회원 탈퇴 메서드와 탈퇴 전용 의존성을 제거했다.

### 유지한 동작

- Apple 계정만 Apple 토큰을 폐기한다.
- FK 제약을 고려한 기존 데이터 삭제 순서를 유지한다.
- 사용자 삭제까지 하나의 transaction으로 처리한다.
- 토큰 정리는 기존 `UserWithdrawalEvent`와 `TransactionalEventListener`를 사용한다.
- 회원 탈퇴 API의 응답 형식을 유지한다.

### 검증

- `./gradlew compileJava`: 성공
- `UserWithdrawalServiceTest`, `UserServiceTest`: 성공
- 전체 테스트 `232개` 중 `18개` 실패, `4개` skip
- 전체 테스트 실패 원인은 기존 테스트 DB의 `pgroonga` extension 생성 권한 부족 및 스키마 충돌이다.

## 다음 구현 명세: 2단계 로컬 인증 영역 분리

### 2단계 설계 결정

로그인, 토큰 관리, 회원가입, 이메일 인증을 하나의 `UserAuthenticationService`에 모두 넣지 않는다.
그렇게 하면 `UserService`의 책임을 이름만 바꾼 또 다른 대형 Service가 생긴다.

2단계를 다음 두 하위 단계로 분리한다.

| 단계 | 신규 Service | 담당 유스케이스 |
| --- | --- | --- |
| 2A | `UserAuthenticationService` | 로그인, 로그아웃, Refresh Token Rotation |
| 2B | `LocalUserRegistrationService` | 로컬 회원가입, 이메일 인증 코드 발송·검증 |

적용 패턴:

- Facade Pattern: Controller에 인증·가입별 단순 진입점 제공
- Application Service: 유스케이스별 트랜잭션과 외부 의존성 조율
- Single Responsibility Principle: 세션 인증과 가입 검증의 변경 이유 분리

### 현재 문제

현재 `UserService`는 다음 책임을 동시에 가진다.

- JWT 생성·파싱·갱신
- Redis Refresh Token 저장·삭제
- Access Token blacklist
- 로컬 이메일·비밀번호 로그인
- 로컬 회원가입
- 이메일 인증 코드 발송·검증
- OAuth 사용자 생성
- 사용자 프로필 설정·수정
- 사용자 조회

인증 관련 메서드가 `UserService`의 `PasswordEncoder`, `JwtTokenProvider`, `RedisService`,
`RedisTemplate`, `SmtpMailService` 의존성을 만든다.

### 목표 구조

```text
LoginRegisterController
├─ UserAuthenticationService
│  ├─ login
│  ├─ logout
│  └─ refreshTokens
├─ LocalUserRegistrationService
│  ├─ signup
│  ├─ sendEmailVerificationCode
│  └─ verifyEmailCode
├─ UserWithdrawalService
└─ UserService
   ├─ OAuth 사용자 생성·조회
   ├─ 프로필 설정·수정
   └─ 사용자 조회
```

---

## 2A 상세 명세: 인증 세션 분리

### 신규 파일

```text
src/main/java/core/domain/user/service/UserAuthenticationService.java
src/test/java/core/domain/user/service/UserAuthenticationServiceTest.java
```

### 이동할 메서드

`UserService`에서 다음 메서드를 제거하고 `UserAuthenticationService`로 이동한다.

| 기존 메서드 | 신규 메서드 | 변경 허용 여부 |
| --- | --- | --- |
| `logout(String accessToken)` | 동일 | 시그니처 유지 |
| `refreshTokens(String refreshToken)` | 동일 | 시그니처 유지 |
| `login(EmailLoginDto req)` | 동일 | 시그니처 유지 |
| `nullToEmpty(String)` | private helper 이동 | 동작 유지 |
| `normalizeEmail(String)` | private helper 이동 | 동작 유지 |

### 신규 Service 의존성

```java
private final UserRepository userRepository;
private final PasswordEncoder passwordEncoder;
private final RedisService redisService;
private final JwtTokenProvider jwtTokenProvider;
private final ApplicationEventPublisher eventPublisher;
```

`RedisTemplate`, `SmtpMailService`, `ImageService` 등 가입·프로필 의존성은 주입하지 않는다.

### Controller 변경

[LoginRegisterController]의 다음 API만 `UserAuthenticationService`를 호출하도록 변경한다.

| HTTP API | 현재 호출 | 변경 호출 |
| --- | --- | --- |
| `POST /api/v1/member/doLogin` | `userService.login` | `userAuthenticationService.login` |
| `POST /api/v1/member/logout` | `userService.logout` | `userAuthenticationService.logout` |
| `POST /api/v1/member/refresh` | `userService.refreshTokens` | `userAuthenticationService.refreshTokens` |

다른 API의 호출 대상은 변경하지 않는다.

### 로그인 동작 계약

입력:

```java
EmailLoginDto
```

처리 순서:

1. 이메일의 앞뒤 공백을 제거하고 소문자로 변환한다.
2. `UserRepository.findByEmail()`로 사용자를 조회한다.
3. 사용자가 없으면 `UserErrorCode.USER_NOT_FOUND`를 던진다.
4. provider가 `local`이 아니면 `UserErrorCode.AUTHENTICATION_FAILED`를 던진다.
5. 비밀번호가 없거나 일치하지 않으면 `UserErrorCode.AUTHENTICATION_FAILED`를 던진다.
6. 기존 claim 규칙으로 Access Token과 Refresh Token을 생성한다.
7. Refresh Token 만료까지 남은 시간을 계산한다.
8. Redis에 Refresh Token을 저장한다.
9. `UserLoggedInEvent(userId, "email")`을 발행한다.
10. 기존 `AuthResponse`를 반환한다.

반드시 유지할 응답:

```java
new AuthResponse(
    "Bearer",
    accessToken,
    refreshToken,
    accessTokenRemainingMillis,
    userId,
    email,
    isNewUser
)
```

### 로그아웃 동작 계약

처리 순서:

1. Access Token 만료까지 남은 시간을 계산한다.
2. Access Token을 Redis blacklist에 저장한다.
3. Access Token에서 userId를 추출한다.
4. Redis의 사용자 Refresh Token을 삭제한다.

변경 금지:

- Controller의 `Authorization: Bearer` 추출 방식
- 정상 응답 `204 No Content`
- blacklist TTL 단위인 milliseconds

추가 방어 조건:

- 계산된 Access Token 잔여 시간이 `0` 이하인 경우 blacklist 저장 여부를 테스트로 고정한다.
- 이번 단계에서는 기존 동작을 우선 유지하며 정책 자체는 변경하지 않는다.

### Refresh Token Rotation 동작 계약

처리 순서:

1. 요청 Refresh Token에서 userId와 expiration을 파싱한다.
2. 토큰 유효성을 검증한다.
3. 만료된 토큰은 `AuthErrorCode.INVALID_REFRESH_TOKEN`으로 변환한다.
4. 형식·서명 등 기타 검증 오류는 `AuthErrorCode.INVALID_TOKEN`으로 변환한다.
5. userId가 없으면 `AuthErrorCode.INVALID_TOKEN`을 던진다.
6. 사용자가 없으면 `UserErrorCode.USER_NOT_FOUND`를 던진다.
7. Redis에 저장된 Refresh Token을 조회한다.
8. Redis token이 없으면 `AuthErrorCode.INVALID_REFRESH_TOKEN`을 던진다.
9. 요청 token과 Redis token이 다르면 Redis token을 삭제하고
   `AuthErrorCode.INVALID_REFRESH_TOKEN`을 던진다.
10. 기존 Refresh Token을 삭제한다.
11. 새 Access Token과 Refresh Token을 생성한다.
12. 새 Refresh Token을 Redis에 저장한다.
13. 기존 `TokenRefreshResponse`를 반환한다.

반드시 유지할 응답:

```java
new TokenRefreshResponse(newAccessToken, newRefreshToken, userId)
```

보안 정리:

- 현재 `refreshTokens()`는 요청·저장 Refresh Token의 마지막 문자열을 로그로 남긴다.
- 토큰 일부도 credential이므로 신규 Service로 이동하면서 해당 fragment 로그를 제거한다.
- userId, 만료 시각, 실패 사유 로그는 유지할 수 있다.
- 이는 API 동작 변경이 아니라 민감정보 로깅 제거다.

### 트랜잭션 경계

| 메서드 | 권장 트랜잭션 |
| --- | --- |
| `login` | `@Transactional(readOnly = true)` 유지 |
| `logout` | DB 접근이 없으므로 `@Transactional` 사용하지 않음 |
| `refreshTokens` | 사용자 조회만 하므로 `@Transactional(readOnly = true)` 사용 |

주의:

- Redis 저장과 JWT 생성은 DB transaction으로 원자화되지 않는다.
- 이번 단계에서는 기존 동작을 유지하며 분산 트랜잭션이나 Outbox를 도입하지 않는다.
- `refreshTokens`의 기존 `@Transactional`을 `readOnly = true`로 바꾸는 것은 허용하지만,
  동시 Rotation 정책 자체는 변경하지 않는다.

### 2A 테스트 명세

`UserAuthenticationServiceTest`에 최소 다음 테스트를 작성한다.

#### 로그인

1. 사용자 없음 → `USER_NOT_FOUND`
2. local provider가 아님 → `AUTHENTICATION_FAILED`
3. 비밀번호 없음 → `AUTHENTICATION_FAILED`
4. 비밀번호 불일치 → `AUTHENTICATION_FAILED`
5. 정상 로그인 → Access/Refresh Token 반환
6. 정상 로그인 → Refresh Token을 정확한 TTL로 Redis에 저장
7. 정상 로그인 → `UserLoggedInEvent` 발행

#### 로그아웃

1. Access Token을 남은 TTL로 blacklist 처리
2. Access Token에서 추출한 userId의 Refresh Token 삭제

#### Refresh Token Rotation

1. 유효하지 않은 Refresh Token → `INVALID_TOKEN`
2. 만료 Refresh Token → `INVALID_REFRESH_TOKEN`
3. token에서 userId 추출 실패 → `INVALID_TOKEN`
4. 사용자 없음 → `USER_NOT_FOUND`
5. Redis token 없음 → `INVALID_REFRESH_TOKEN`
6. 요청 token과 Redis token 불일치 → 저장 token 삭제 후 `INVALID_REFRESH_TOKEN`
7. 정상 rotation → 기존 token 삭제 후 새 token 저장
8. 정상 rotation → `TokenRefreshResponse` 값 검증

### 2A 완료 조건

- 세 API의 URL, HTTP method, 응답 body, 상태 코드가 변경되지 않는다.
- 기존 JWT claim 생성 방식이 변경되지 않는다.
- 기존 Redis refresh token 및 blacklist key 규칙이 변경되지 않는다.
- `UserService`에서 `login`, `logout`, `refreshTokens`가 제거된다.
- `UserService`에서 `RedisService`, `JwtTokenProvider` 의존성이 인증 용도로 더 이상 사용되지 않는다.
- 인증 테스트가 `UserServiceTest`에서 `UserAuthenticationServiceTest`로 이동한다.
- `./gradlew compileJava`와 대상 테스트가 통과한다.

---

## 2B 상세 명세: 로컬 가입·이메일 인증 분리

### 신규 파일

```text
src/main/java/core/domain/user/service/LocalUserRegistrationService.java
src/test/java/core/domain/user/service/LocalUserRegistrationServiceTest.java
```

### 이동할 메서드와 상수

| 종류 | 이동 대상 |
| --- | --- |
| public method | `signup` |
| public method | `sendEmailVerificationCode` |
| public method | `verifyEmailCode` |
| private helper | `normalizeEmail` |
| private helper | `buildLocalSocialId` |
| private helper | `sha256Hex` |
| constant | `EMAIL_VERIFY_CODE_KEY` |
| constant | `EMAIL_VERIFIED_FLAG_KEY` |
| constant | `EMAIL_VERIFY_ATTEMPT_KEY` |
| constant | `CODE_TTL_MIN` |
| constant | `VERIFIED_TTL_MIN` |

`PW_RULE`은 현재 signup에서 사용되지 않는다. 실제 사용처를 확인한 뒤:

- 사용처가 없으면 별도 정리 커밋에서 제거한다.
- 이번 이동 작업에서는 인증 정책을 임의 변경하지 않는다.

### 신규 Service 의존성

```java
private final UserRepository userRepository;
private final PasswordEncoder passwordEncoder;
private final RedisTemplate<String, String> redisTemplate;
private final RedisService redisService;
private final JwtTokenProvider jwtTokenProvider;
private final SmtpMailService smtpMailService;
private final ApplicationEventPublisher eventPublisher;
```

### Controller 변경

| HTTP API | 변경 호출 |
| --- | --- |
| `POST /api/v1/member/signup` | `localUserRegistrationService.signup` |
| `POST /api/v1/member/send-verification-email` | `localUserRegistrationService.sendEmailVerificationCode` |
| `POST /api/v1/member/verify-code` | `localUserRegistrationService.verifyEmailCode` |

`POST /api/v1/member/email/check`는 현재 `UserService.existsByEmail()`을 유지한다.
중복 확인 API까지 가입 Service에 넣을지는 2B 완료 후 별도로 판단한다.

### 회원가입 동작 계약

처리 순서:

1. 약관 미동의면 `UserErrorCode.AGREEMENT_INPUT`
2. 이메일 normalize
3. 중복 이메일이면 `UserErrorCode.DUPLICATE_RESOURCE`
4. Redis의 `email_verification:verified:{email}` 값이 `"1"`이 아니면
   `UserErrorCode.AUTHENTICATION_FAILED`
5. local provider와 `local:{sha256(email)}` socialId 설정
6. 비밀번호 암호화
7. 신규 사용자의 role, 약관, push, 생성·수정 시간을 기존 규칙으로 설정
8. 사용자 저장
9. 이메일 인증 완료 flag 삭제
10. Access/Refresh Token 생성
11. Refresh Token Redis 저장
12. `UserLoggedInEvent(userId, "local")` 발행
13. 기존 `LoginResponseDto` 반환

### 이메일 인증 코드 발송 계약

- 이메일 normalize 규칙 유지
- 가입된 이메일이면 `DUPLICATE_RESOURCE`
- `SmtpMailService.sendVerificationEmail()` 결과 코드를 Redis에 저장
- key: `email_verification:code:{email}`
- TTL: `3분`

### 이메일 인증 코드 검증 계약

- code가 없으면 `VERIFY_CODE_EXPIRES`
- 코드 불일치 시 실패 횟수 증가
- 실패 횟수 key: `auth:verify-attempt:{email}`
- 실패 횟수 TTL: `10분`
- 5회 이상 실패 시 code와 attempt 삭제 후 `VERIFY_CODE_NEED_RESEND`
- 5회 미만 실패 시 `VERIFY_CODE_NOT_MATCH`
- 성공 시 code와 attempt 삭제
- 성공 flag `email_verification:verified:{email} = "1"` 저장
- 성공 flag TTL: `10분`

### 2B 테스트 명세

#### 회원가입

1. 약관 미동의
2. 이메일 중복
3. 이메일 인증 flag 없음
4. 정상 가입 필드와 암호화 비밀번호 검증
5. 정상 가입 후 인증 flag 삭제
6. 정상 가입 후 Refresh Token 저장
7. 정상 가입 후 `UserLoggedInEvent("local")` 발행

#### 이메일 발송

1. 중복 이메일 발송 거부
2. 정상 발송 코드와 3분 TTL 저장

#### 이메일 코드 검증

1. 만료 코드
2. 5회 미만 불일치
3. 5회째 불일치 후 code와 attempt 삭제
4. 정상 검증 후 code와 attempt 삭제
5. 정상 검증 후 10분 TTL 완료 flag 저장

### 2B 완료 조건

- 가입과 이메일 인증 API 계약이 변경되지 않는다.
- Redis key 이름과 TTL이 변경되지 않는다.
- local socialId 생성 규칙이 변경되지 않는다.
- `UserService`에서 가입·이메일 인증 메서드와 관련 상수가 제거된다.
- `UserService`에서 `PasswordEncoder`, `RedisTemplate`, `SmtpMailService` 의존성이 제거된다.
- 대상 테스트와 compile이 통과한다.

---

## 2단계 범위 제외

- Google·Apple OAuth 인증 흐름 통합
- 관리자 로그인과 OTP 처리
- 비밀번호 찾기·재설정 `PasswordService`
- JWT claim, 서명 알고리즘, 만료 정책 변경
- Redis key prefix 구조 변경
- Refresh Token 동시 재발급 방지용 lock 도입
- Controller 클래스 자체 분리
- API 요청·응답 DTO 변경

## 2단계 최종 검증 순서

```bash
./gradlew compileJava
./gradlew test --tests core.domain.user.service.UserAuthenticationServiceTest
./gradlew test --tests core.domain.user.service.LocalUserRegistrationServiceTest
./gradlew test --tests core.domain.user.service.UserServiceTest
./gradlew test
```

전체 테스트에서 기존 `pgroonga` 권한 실패가 반복되면 대상 테스트 성공 여부와 환경 실패 원인을
진행 문서에 분리 기록한다.
