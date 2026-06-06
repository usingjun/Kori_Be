# 도메인 개요

## 서비스 성격

코드와 README 기준으로 이 백엔드는 외국인 대상 K-culture 커뮤니티, 실시간 채팅, 친구/팔로우, 메인 콘텐츠, 알림, 결제, 관리자 운영 기능을 제공한다.

## 사용자 도메인

주요 패키지: `core.domain.user`, `core.domain.userdevicetoken`, `core.domain.usernotificationsetting`

- `User`는 사용자 기본 프로필, OAuth provider/social id, email/password, Apple refresh token, 역할, push 동의, 접속/활동 지표를 가진다.
- 역할은 `VISITOR`, `USER`, `ADMIN`, `AI` 계열 enum으로 관리된다.
- `User` 내부의 프로필 완성도 로직은 생년월일, 목적, 소개, 언어, 취미, 성별, 국가 값이 채워지면 일반 USER로 바꾸는 구조다. ADMIN과 AI는 이 자동 변경에서 제외된다.
- 팔로우/친구 관계는 `Follow`, `FollowActivityLog`, `FollowService`에서 처리된다.
- 차단은 `BlockUser`, `BlockRepository`를 통해 여러 조회 흐름에서 제외 조건으로 사용된다.
- 기기 토큰은 `UserDeviceToken`과 `UserDeviceTokenRepository`로 관리되며 FCM push와 연결된다.
- 알림 설정은 `UserNotificationSetting`으로 사용자별 notification type on/off를 저장한다.

불명확한 부분:

- 프로필 완성도 기준은 코드에 존재하지만, 클라이언트가 어떤 단계에서 어떤 필드를 반드시 보내는지는 이 저장소만으로 확정할 수 없다.
- `sex`, `birthdate`, `language`, `hobby`가 문자열로 저장되며 구체 포맷 검증 범위는 API별 DTO와 서비스 로직에 흩어져 있다.

## 인증/계정 도메인

주요 패키지: `core.domain.user.controller`, `core.global.security`, `core.global.service`, `core.global.apple`

- 로그인/회원가입 컨트롤러는 email login, Google app login, Apple app login, admin login, OTP 검증, token refresh, signup, logout, withdrawal 등을 다룬다.
- JWT access/refresh token을 사용하고 refresh token 및 blacklist는 Redis에 저장한다.
- 관리자 OTP는 Google Authenticator 기반 서비스와 `AdminOtp` 엔티티를 사용한다.
- Apple 로그인/탈퇴는 Apple OAuth properties, public key, client secret generator, Feign client를 사용한다.
- 비밀번호 재설정과 이메일 인증은 Redis TTL과 mail template을 사용한다.

불명확한 부분:

- OAuth redirect와 app login의 클라이언트별 UX 흐름은 서버 코드만으로 완전한 순서를 확정하기 어렵다.

## 커뮤니티 도메인

주요 패키지: `core.domain.board`, `core.domain.post`, `core.domain.comment`, `core.domain.bookmark`, `core.domain.poll`, `core.global.entity.like`

- 게시판은 `Board`와 `BoardCategory`로 구분된다.
- 게시글은 `Post` 엔티티이며 작성자, 게시판, 본문, 익명 여부, 조회수 성격의 `checkCount`, 투표와 댓글 관계를 가진다.
- 게시글 작성/수정/삭제, 상세 조회, 내 게시글, 사용자 게시글, 좋아요, 신고/차단, 채팅방 공유 글 작성이 제공된다.
- 댓글은 `Comment`, `CommentServiceImpl`, `CommentRepositoryCustomImpl` 중심으로 작성/수정/삭제/좋아요/차단/내 댓글 조회를 처리한다.
- 북마크는 게시글 단위로 추가/삭제/목록 조회를 제공한다.
- 투표/퀴즈는 `Poll`, `PollOption`, `VoteRecord`, `PollService`, `PollController`로 처리한다.
- 금칙어 검사는 `ForbiddenWordService`와 `forbidden_words.json`을 사용한다.

불명확한 부분:

- `checkCount`가 API 문맥에서는 조회수로 보이나, 정확한 비즈니스 명칭은 코드만으로 확정할 수 없다.
- 익명 정책과 게시판별 작성 가능 조건은 서비스 로직에 존재하지만, 기획 원문은 저장소에 없다.

## 검색/추천 도메인

주요 패키지: `core.domain.post.service.search`, `core.domain.maincontent.service.search`, `core.domain.user.service`

- 게시글 검색은 PGroonga 기반 full-text 검색과 QueryDSL/JDBC를 사용한다.
- 게시글 자동완성은 메모리 인덱스(`PostSuggestIndex`) 우선, DB fallback 방식이다.
- 메인 콘텐츠 검색도 유사하게 score, createdAt, id 기반 커서 페이징을 사용한다.
- 최근 검색어는 Redis에 저장되는 구조다.
- 사용자 추천은 `RecommenderService`, `ContentBasedRecommender`가 담당한다.
- hot keyword batch가 게시글과 메인 콘텐츠 쪽에 별도로 존재한다.

불명확한 부분:

- 추천 알고리즘의 정확한 제품 목표와 랭킹 가중치는 코드 이상의 문서가 없어 확정할 수 없다.

## 채팅 도메인

주요 패키지: `core.domain.chat`, `core.global.websocket`, `core.global.redis`

- `ChatRoom`은 1:1/그룹 여부, 방 이름, 설명, 카테고리, 추천 가능 여부, owner, 마지막 메시지 시각, 메시지 수를 가진다.
- `ChatParticipant`는 참여자 상태, 알림/번역 설정 등 방별 사용자 상태를 관리한다.
- `ChatMessage`는 방, sender, content, sentAt, messageType을 가진다.
- REST API는 방 생성/조회/참여자 관리/메시지 조회/검색/미디어 presigned URL 등을 제공한다.
- WebSocket API는 메시지 전송, 읽음 처리, 미디어 메시지 전송을 처리한다.
- 메시지 저장 후 수신자 컨텍스트를 구성하고, 언어별 번역을 병렬 수행한 뒤 이벤트를 발행해 실시간 전송과 push 흐름으로 이어진다.
- `ChatMessageTranslation`은 메시지별 번역 결과를 저장한다.
- 신고는 `ChatReport`와 관리자 처리 흐름으로 연결된다.

불명확한 부분:

- `ChatWebSocketController.deleteMessage`는 현재 로그만 남기고 서비스 삭제 호출이 보이지 않는다. 실제 삭제 API가 다른 경로에 있는지, 미구현인지 코드만으로는 확정할 수 없다.
- Redis pub/sub이 단일 인스턴스 simple broker와 어떤 배포 토폴로지에서 함께 쓰이는지는 코드만으로 명확하지 않다.

## 메인 콘텐츠/K-news 도메인

주요 패키지: `core.domain.maincontent`, `core.domain.post.service.crawling`

- `MainContent`는 메인 페이지 콘텐츠/뉴스 계열 데이터로 보인다.
- `MainContentController`, `MainContentSearchController`, `MainContentService`, `MainContentSearchService`가 조회와 검색을 담당한다.
- `MainContentRecommendation`, `MainContentHotKeywords`는 추천 키워드와 인기 키워드를 저장한다.
- K-news 크롤러는 Soompi, Allkpop, Korea.net, Seoul Global, MyDramaList, Harper's Bazaar, K-life 계열 외부 사이트를 Jsoup/HtmlUnit으로 수집한다.
- 수집 데이터는 `CrawledData`와 관리자 승인/병합 흐름으로 연결된다.

불명확한 부분:

- 메인 콘텐츠와 게시글/크롤링 데이터 사이의 최종 게시 정책은 관리자 승인 흐름을 통해 추정 가능하지만, 정확한 운영 절차 문서는 없다.

## 이미지/미디어 도메인

주요 패키지: `core.global.entity.image`

- `Image` 엔티티는 USER, POST, MAIN_CONTENT 등 관련 대상별 이미지를 저장하는 공통 이미지 모델로 사용된다.
- S3 호환 Object Storage와 CDN URL을 사용한다.
- 프로필 이미지, 게시글 이미지, 메인 콘텐츠 이미지 서비스가 분리되어 있다.
- presigned URL 생성, staging key 확인, CDN URL/thumbnail URL 생성, 폴더/객체 삭제가 구현되어 있다.
- 이미지 검수/중재 이벤트와 실패한 삭제 작업을 재시도하는 `FailedImageCleanup` 계열 코드가 존재한다.

불명확한 부분:

- 이미지 검수 정책이 Sightengine, 관리자 검수, 내부 이벤트 중 어느 단계에서 최종 판단되는지 모든 케이스를 코드만으로 완전히 확정하기 어렵다.

## 알림 도메인

주요 패키지: `core.domain.notification`, `core.domain.usernotificationsetting`

- in-app notification은 `Notification` 엔티티로 저장된다.
- push notification은 Firebase Admin SDK를 사용한다.
- 알림 타입은 `NotificationType` enum으로 관리된다.
- 알림 설정 초기화, bulk update, OS push 권한 sync, 읽음 처리, 목록 조회가 구현되어 있다.
- 이벤트 리스너가 댓글/팔로우/채팅 등 도메인 이벤트를 받아 알림을 생성/전송하는 구조다.
- 오래된 알림 정리 스케줄러가 있다.

불명확한 부분:

- 모든 알림 타입별 트리거 조건은 이벤트 발행 지점까지 추적해야 완전히 확정할 수 있다.

## 결제 도메인

주요 패키지: `core.domain.payment`

- Apple/Google IAP 검증과 webhook 처리를 제공한다.
- `IapProduct`, `IapPurchase`, `IapEntitlement`, `IapWebhookEvent`, `IapBonusGrant`, `UserItem` 엔티티가 있다.
- 구매 검증 후 entitlement를 갱신하고, boost/profile frame 같은 item 또는 bonus grant를 부여한다.
- Android는 Google Android Publisher API, iOS는 Apple transaction/JWS 계열 검증을 사용한다.

불명확한 부분:

- 실제 상품 catalog와 store product id 전체 목록은 코드 일부와 DB 데이터에 의존하므로 저장소만으로 전체를 확정할 수 없다.
- Android 상품 미매핑 시 `UserErrorCode.INVALID_FOLLOW_STATUS`를 던지는 TODO가 있어 최종 에러 코드 정책이 불명확하다.

## AI 사용자 도메인

주요 패키지: `core.domain.aiuser`

- `AiPersona` 엔티티와 AI 사용자 생성/초대/수정 관리자 흐름이 있다.
- `AiChatCoordinatorService`, `AiChatUserService`, `AiPromptManager`, `OpenAiClientImpl` 등이 AI 응답 생성을 담당한다.
- 메시지 생성 후 `AiReplyEventListener`가 트랜잭션 커밋 이후 이벤트를 받아 AI 응답 흐름을 시작하는 구조다.
- AI 온보딩과 그룹 채팅 revival 스케줄러가 있다.

불명확한 부분:

- AI 응답 정책, prompt 버전 관리, 안전 필터링 기준은 코드 외부 문서가 없어 완전히 판단할 수 없다.

## 관리자 도메인

주요 패키지: `core.domain.admin`

- Thymeleaf 기반 관리자 화면이 다수 존재한다.
- 관리 대상은 유저, AI 유저, 게시판, 게시글, 댓글, 채팅방/메시지/신고, 크롤링 데이터, 메인 콘텐츠, 이미지 리뷰, 푸시, 메트릭, 모니터링이다.
- 관리자 인증은 admin login과 OTP 검증을 포함한다.
- 일부 관리자 컨트롤러는 REST API를 함께 제공한다.

불명확한 부분:

- 관리자 권한 세분화는 현재 코드상 `ROLE_ADMIN` 중심으로 보이며, 세부 permission 모델은 확인되지 않는다.

## 글로벌/운영 도메인

주요 패키지: `core.global`

- 공통 예외와 에러 코드는 `BusinessException`, `AppError`, `GlobalExceptionHandler`, `core.global.enums.errorcode`에서 관리한다.
- OpenAPI 문서 설정과 error docs annotation이 존재한다.
- 앱 버전 체크, 앱 설정, 유저 피드백, health/smoke endpoint가 있다.
- Micrometer/Prometheus 메트릭이 사용자 활동, presence, 채팅, 알림, cohort, feature usage 등에 걸쳐 구현되어 있다.

불명확한 부분:

- 운영 중 실제로 수집/대시보드화되는 지표 목록은 Prometheus/Grafana 설정이 저장소에 없어 확정할 수 없다.
