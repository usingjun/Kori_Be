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

## 다음 구현 명세: 2단계 인증·토큰 유스케이스 분리

### 목적

`UserService`에서 로그아웃, 토큰 갱신, 로컬 회원가입·로그인 책임을 분리한다.

분리 대상 메서드:

- `logout`
- `refreshTokens`
- `signup`
- `login`
- 로컬 인증에만 사용되는 private helper

### 목표 구조

```text
LoginRegisterController
├─ UserAuthenticationService
│  ├─ login
│  ├─ signup
│  ├─ logout
│  └─ refreshTokens
├─ UserWithdrawalService
└─ UserService
   ├─ 프로필 설정·수정
   └─ 사용자 조회
```

### 책임 경계

`UserAuthenticationService`가 담당:

- 비밀번호 검증
- 로컬 계정 회원가입
- JWT access/refresh token 생성과 갱신
- Redis refresh token 저장·삭제 및 access token blacklist
- 인증 관련 오류 변환

`UserService`가 계속 담당:

- OAuth 사용자 생성과 조회
- 사용자 프로필 설정·수정
- 사용자 카드, 게시글, 온라인 상태 등 조회

### 구현 순서

1. 인증 관련 현재 동작과 Controller 호출부를 테스트로 고정한다.
2. `UserAuthenticationService`를 추가하고 인증 메서드와 전용 helper를 이동한다.
3. Controller가 인증 API에서 새 Service를 직접 호출하도록 변경한다.
4. `UserService`에서 인증 전용 의존성을 제거한다.
5. 대상 테스트와 전체 테스트를 실행한다.

### 완료 조건

- 로그인, 회원가입, 로그아웃, 토큰 갱신 API 계약이 변경되지 않는다.
- 기존 JWT claim과 만료 정책이 변경되지 않는다.
- 기존 Redis key와 TTL 규칙이 변경되지 않는다.
- `UserService`가 `PasswordEncoder`, `JwtTokenProvider`, `RedisService`에 인증 목적으로 의존하지 않는다.
- 인증 유스케이스 단위 테스트가 추가된다.

### 범위 제외

- OAuth Google/Apple 인증 흐름 통합
- 이메일 인증 코드 발송·검증 분리
- JWT 포맷 또는 Redis key 정책 변경
- 인증 API 응답 모델 변경
