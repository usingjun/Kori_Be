# 개선 후보 분석

## 분석 범위

- `AGENTS.md`와 `docs/agent/*.md`를 먼저 읽고, 실제 코드에서 확인되는 근거만 기준으로 정리했다.
- 생산 코드, 테스트, 설정, Docker, CI/CD, 배포, 인프라, secrets, env 파일은 수정하지 않았다.
- 근거가 부족한 의도나 요구사항은 "불명확"으로 표시했다.
- 기존 `pgroonga` 테스트 실패는 수정 대상이 아니며, 테스트 실행 환경 제한으로만 다룬다.

## 개선 후보 목록

### 1. FCM push가 한 번의 요청에서 두 번 전송될 수 있음

- 제목: `PushNotificationService`의 중복 `firebaseMessaging.send`
- 분류: External API failure handling / Notification consistency
- 위치: `src/main/java/core/domain/notification/service/PushNotificationService.java:131-134`
- 실제 코드 근거: `Message fcmMessage = messageBuilder.build();` 이후 `firebaseMessaging.send(fcmMessage);`가 먼저 호출되고, 바로 아래 `try` 블록 안에서 같은 `fcmMessage`를 다시 `firebaseMessaging.send(fcmMessage);`로 전송한다.
- 위험도: High
- 개선 방향: 단일 전송만 수행하도록 중복 호출을 제거하고, 성공/실패 로깅과 invalid token 삭제가 동일한 `try/catch` 경로에서 처리되게 정리한다.
- 수정 시 주의사항: `@CircuitBreaker(name = "fcmPush")` fallback 동작, Firebase invalid token 삭제, `ChatMetrics` 성공/실패 카운트가 중복 또는 누락되지 않아야 한다.
- 추천 테스트: `FirebaseMessaging` mock으로 단건 push가 정확히 1회 호출되는지, `FirebaseMessagingException` 발생 시 invalid token 삭제와 실패 metric이 기대대로 처리되는지 검증한다.

### 2. IAP 검증 트랜잭션 안에서 외부 API 호출이 실행됨

- 제목: `IapService.verify`의 트랜잭션 경계와 주석 불일치
- 분류: Transaction boundary risks / External API failure handling
- 위치: `src/main/java/core/domain/payment/service/IapService.java:37-52`
- 실제 코드 근거: `verify(VerifyRequest req)`가 `@Transactional`이고, 주석에는 "외부 API 호출 (비트랜잭션 영역)"이라고 되어 있지만 같은 메서드 안에서 `appleClient.getTransaction(req.transactionId())`, `googleClient.verify(req.productId(), req.purchaseToken())`를 호출한다.
- 위험도: High
- 개선 방향: 외부 검증 API 호출과 DB 상태 변경을 분리하고, DB 갱신 구간만 명확한 트랜잭션으로 감싼다.
- 수정 시 주의사항: `PaymentTransaction` 중복 저장 방지, `PointWallet` 적립의 멱등성, iOS/Android 검증 결과 매핑, 실패 시 사용자에게 내려가는 `PaymentErrorCode`가 바뀌지 않도록 확인해야 한다.
- 추천 테스트: Apple/Google client 지연 또는 예외 상황에서 DB 트랜잭션이 열린 상태로 오래 유지되지 않는지, 같은 `transactionId`/`purchaseToken` 재요청 시 포인트가 중복 적립되지 않는지 테스트한다.

### 3. refresh token 일부가 로그에 남음

- 제목: `UserService.refreshTokens`의 token fragment 로깅
- 분류: Security concerns
- 위치: `src/main/java/core/domain/user/service/UserService.java:189-196`
- 실제 코드 근거: refresh token 마지막 15자를 `tokenFragment`로 만든 뒤 `log.info("[토큰 갱신 시도] UserID: {}, Token(끝) : ...{}", userId, tokenFragment, ...)` 형태로 기록한다.
- 위험도: High
- 개선 방향: refresh token 값 또는 token fragment를 로그에서 제거하고, 필요하면 `userId`, request id, token 만료 여부 같은 비민감 정보만 남긴다.
- 수정 시 주의사항: 장애 분석에 필요한 식별자는 남기되 재사용 가능한 인증 정보 일부가 저장되지 않게 해야 한다.
- 추천 테스트: `refreshTokens` 실행 시 로그 메시지에 refresh token 원문 또는 suffix가 포함되지 않는지 검증한다.

### 4. WebSocket CONNECT에서 HTTP JWT 검증 경로와 다른 검증을 사용함

- 제목: `StompChannelInterceptor`의 token validation/blacklist 누락 가능성
- 분류: Security concerns / WebSocket consistency risks
- 위치: `src/main/java/core/global/websocket/config/StompChannelInterceptor.java:65-82`, `src/main/java/core/global/security/JwtTokenFilter.java:103-120`
- 실제 코드 근거: HTTP 필터는 `redisService.isBlacklisted(token)`, `jwtTokenProvider.validateToken(token)`, `getRoleFromToken(token)`을 수행한다. 반면 WebSocket CONNECT는 `jwtTokenProvider.getUserIdFromAccessToken(token)`과 `getEmailFromToken(token)`만 호출하고 Redis blacklist와 role 검증을 수행하지 않는다.
- 위험도: High
- 개선 방향: WebSocket CONNECT에서도 HTTP 인증 경로와 동일한 최소 검증 정책을 적용하고, 실패 시 STOMP 연결이 계속 인증된 것처럼 진행되지 않도록 명확히 차단한다.
- 수정 시 주의사항: 부하 테스트 허용 로직이라는 주석이 있어 실제 운영 요구가 불명확하다. 테스트용 예외가 필요하다면 profile 또는 명시적 설정으로 분리해야 한다.
- 추천 테스트: blacklist token, expired token, role 없는 token, `OUTCAST` role token으로 CONNECT 시 세션 속성과 Redis active set이 생성되지 않는지 검증한다.

### 5. WebSocket message authorization 설정이 비활성화되어 있음

- 제목: `messageAuthorizationManager` 주석 처리
- 분류: Security concerns / WebSocket consistency risks
- 위치: `src/main/java/core/global/security/SecurityConfig.java:99-108`
- 실제 코드 근거: `/app/**` 인증 요구를 설정하는 `AuthorizationManager<Message<?>> messageAuthorizationManager`가 전체 주석 처리되어 있다. 실제 목적은 "chat 리팩토링 끝날시 다시 open"으로만 남아 있어 현재 운영 의도는 불명확하다.
- 위험도: High
- 개선 방향: STOMP destination별 인증/인가 정책을 명시하고, Controller 단에서 userId를 header/session에서 신뢰하는 흐름과 일관되게 맞춘다.
- 수정 시 주의사항: 현재 `StompChannelInterceptor`와 중복 검증될 수 있으므로 연결 인증, destination authorization, message payload 권한 검증의 책임을 나눠야 한다.
- 추천 테스트: 인증 없는 SEND, 다른 사용자의 roomId로 SEND, 허용되지 않은 destination 접근이 거부되는지 WebSocket 통합 테스트로 검증한다.

### 6. Redis active user set이 stale 상태를 오래 유지할 수 있음

- 제목: `chat:active_users` 전역 Set의 TTL/heartbeat 부재
- 분류: Redis/cache consistency risks / WebSocket consistency risks
- 위치: `src/main/java/core/global/websocket/config/StompChannelInterceptor.java:47-93`, `src/main/java/core/domain/chat/service/ChatMessageService.java:243-257`
- 실제 코드 근거: CONNECT에서 `redisService.addSetElement(ACTIVE_USERS_KEY, userId.toString())`, DISCONNECT에서 `removeSetElement`만 수행한다. 메시지 전송 시 `redisService.getSetElements(ACTIVE_USERS_KEY)` 결과를 `Long::valueOf`로 변환해 온라인 수신자를 결정한다.
- 위험도: Medium
- 개선 방향: 세션 단위 presence key와 TTL, heartbeat 갱신, 서버 재시작/비정상 종료 시 cleanup 전략을 도입한다.
- 수정 시 주의사항: 동일 사용자의 다중 접속을 단일 userId set으로만 표현하면 한 세션 종료가 다른 세션의 online 상태를 지울 수 있다. 다중 세션 요구사항은 불명확하다.
- 추천 테스트: DISCONNECT 누락, Redis에 잘못된 값 존재, 동일 userId 다중 세션 중 하나만 종료되는 상황에서 수신자 계산이 깨지지 않는지 검증한다.

### 7. DISCONNECT에서 dwell leave 처리가 도달하지 않음

- 제목: `StompChannelInterceptor.preSend`의 unreachable `dwell.onLeave`
- 분류: WebSocket/chat consistency risks / Technical debt
- 위치: `src/main/java/core/global/websocket/config/StompChannelInterceptor.java:51-59`
- 실제 코드 근거: `else if (StompCommand.DISCONNECT.equals(command))`에서 `handleDisconnect(accessor)`가 먼저 실행되므로, 뒤의 `else if (StompCommand.UNSUBSCRIBE.equals(command) || StompCommand.DISCONNECT.equals(command))`에서 DISCONNECT 조건은 도달하지 않는다.
- 위험도: Medium
- 개선 방향: DISCONNECT에서 `handleDisconnect`와 `dwell.onLeave`가 모두 실행되도록 분기 구조를 정리한다.
- 수정 시 주의사항: `UNSUBSCRIBE`와 `DISCONNECT`가 같은 세션에 연속 발생할 수 있으므로 dwell metric 중복 기록 방지 기준이 필요하다.
- 추천 테스트: SUBSCRIBE 후 DISCONNECT 시 `ChatRoomDwellRecorder.onLeave`가 1회 호출되는지 검증한다.

### 8. WebSocket delete message endpoint가 실제 삭제를 수행하지 않음

- 제목: `ChatWebSocketController.deleteMessage` no-op
- 분류: WebSocket/chat consistency risks / Technical debt
- 위치: `src/main/java/core/domain/chat/controller/ChatWebSocketController.java:65-68`, `src/main/java/core/domain/chat/service/ChatMessageService.java:475-489`
- 실제 코드 근거: `@MessageMapping("/chat.deleteMessage")` 메서드는 `log.info`만 수행한다. 실제 삭제와 이벤트 발행 로직은 `ChatMessageService.deleteMessageAndBroadcast`에 있으나 해당 Controller 메서드에서 호출되지 않는다.
- 위험도: Medium
- 개선 방향: WebSocket 삭제 요청이 공식 기능이라면 service 호출과 권한 검증을 연결한다. 기능이 폐기된 것이라면 endpoint를 제거하거나 명확히 문서화한다.
- 수정 시 주의사항: 삭제 권한은 `message.sender.id == userId` 검증과 room 참여자 검증을 함께 고려해야 한다.
- 추천 테스트: 작성자 삭제 성공, 비작성자 삭제 실패, 삭제 후 `MessageDeletedEvent` 발행 여부를 WebSocket 또는 service 테스트로 검증한다.

### 9. AI 응답 후보 판단에서 per-AI 쿼리가 반복됨

- 제목: `AiChatCoordinatorService`의 AI별 `exists` 반복 조회
- 분류: Potential N+1 issues / AI User Flow
- 위치: `src/main/java/core/domain/aiuser/service/AiChatCoordinatorService.java:218-257`
- 실제 코드 근거: `for (User ai : aiList)` 루프 안에서 `chatMessageRepository.existsBySenderIdAndChatRoomIdAndSentAtAfter(...)`를 호출하고, `determineResponseType`에서도 같은 형태의 조회를 다시 수행한다. 코드 주석에도 "쿼리 최적화 가능"이라고 적혀 있다.
- 위험도: Medium
- 개선 방향: roomId와 AI user id 목록을 기준으로 최근 발화 여부를 한 번에 조회해 Map으로 계산한다.
- 수정 시 주의사항: "LAST_10_MINUTES"와 "LAST_24_HOURS" 기준이 서로 다르므로 bulk query 결과를 시간 기준별로 분리해야 한다.
- 추천 테스트: AI 1명, 여러 명, 최근 10분 발화 있음/없음, 24시간 발화 있음/없음 케이스에서 응답 타입과 쿼리 수를 검증한다.

### 10. OpenAI client가 수동 retry와 blocking sleep에 의존함

- 제목: `OpenAiClientImpl.generateResponse`의 외부 API 실패 처리 한계
- 분류: External API failure handling risks / AI User Flow
- 위치: `src/main/java/core/domain/aiuser/client/OpenAiClientImpl.java:31-77`
- 실제 코드 근거: `maxRetries = 3`, `waitTime = 1000`으로 수동 retry를 수행하고, 실패 시 `Thread.sleep(waitTime)` 후 재시도한다. 모든 실패 후 `return null`을 반환한다.
- 위험도: Medium
- 개선 방향: timeout, retry, circuit breaker, fallback 결과를 명확히 설정하고 호출자에서 `null` 의미를 분기하지 않아도 되는 typed result를 사용한다.
- 수정 시 주의사항: 현재 모델명 `gpt-5.1-codex-mini`의 실제 사용 가능 여부는 로컬 코드만으로 확인할 수 없어 불명확하다. 네트워크/공식 문서 확인 없이는 모델명 변경을 권장하지 않는다.
- 추천 테스트: HTTP timeout, 5xx, 4xx, 빈 응답, retry 후 성공, retry 전부 실패 시 AI 메시지 예약이 생성되지 않는지 검증한다.

### 11. Chat 번역 cache write 동작이 메서드명/주석과 다름

- 제목: `ChatTranslationService.translateAndCache`가 Redis cache를 쓰지 않음
- 분류: Redis/cache consistency risks / Technical debt
- 위치: `src/main/java/core/domain/chat/service/ChatTranslationService.java:115-154`
- 실제 코드 근거: 주석은 "번역 결과를 DB와 Redis에 비동기로 저장"이라고 설명하지만 `saveTranslationAsync`는 `translationRepository.saveIgnoreDuplicate(...)`만 호출한다. `translateAndCache`도 external translation 호출 후 결과만 반환하고 `cacheToRedis`를 호출하지 않는다.
- 위험도: Medium
- 개선 방향: write path에서 DB와 Redis cache를 함께 갱신할지, read path cache warming만 사용할지 정책을 명확히 하고 메서드명/주석/동작을 일치시킨다.
- 수정 시 주의사항: 메시지 저장 트랜잭션이 커밋되기 전 번역 cache가 생기면 orphan cache가 될 수 있으므로 afterCommit 기준이 필요하다.
- 추천 테스트: 새 메시지 번역 후 Redis에 값이 저장되는 정책인지, read path에서 DB hit 후 Redis warming이 되는지, Redis 실패가 메시지 전송을 막지 않는지 검증한다.

### 12. 게시글 이미지 저장이 `@Async`로 부모 트랜잭션과 분리됨

- 제목: `PostServiceImpl.writePost`와 `PostImageServiceImpl.savePostImages`의 비동기 트랜잭션 경계
- 분류: Transaction boundary risks / Image/S3 cleanup risks
- 위치: `src/main/java/core/domain/post/service/impl/PostServiceImpl.java:396-418`, `src/main/java/core/global/entity/image/service/impl/PostImageServiceImpl.java:137-170`
- 실제 코드 근거: `writePost`는 `@Transactional` 안에서 `imageService.savePostImages(post.getId(), request.imageUrls())`를 호출한다. 실제 이미지 저장 구현은 `@Async("imageExecutor")`와 `@Transactional`이 붙어 별도 스레드/트랜잭션에서 S3 copy, DB 저장, staging 삭제를 수행한다.
- 위험도: Medium
- 개선 방향: 게시글 DB 커밋 이후 이미지 저장이 시작되도록 afterCommit 이벤트 또는 명시적 outbox/job 흐름으로 정리한다.
- 수정 시 주의사항: `ImageModerationEvent`, staging object 삭제, 실패 cleanup 기록이 게시글 생성 성공/실패와 어떤 관계를 가져야 하는지 정책이 필요하다.
- 추천 테스트: 게시글 트랜잭션 rollback 시 이미지 copy/staging 삭제가 발생하지 않는지, image executor 실패 시 게시글과 cleanup 상태가 기대대로 남는지 검증한다.

### 13. S3 folder delete 실패 재시도는 있지만 부분 실패 의미가 호출자에게 충분히 전달되지 않음

- 제목: `S3ImageStorageClient.deleteFolder`와 `deleteObjectsBulk`의 partial failure 처리
- 분류: Image/S3 cleanup risks
- 위치: `src/main/java/core/global/entity/image/service/impl/S3ImageStorageClient.java:47-74`, `src/main/java/core/global/entity/image/service/impl/S3ImageStorageClient.java:81-123`, `src/main/java/core/global/entity/image/service/FailedImageCleanupService.java:45-77`
- 실제 코드 근거: `deleteObjectsBulk`는 S3 `errors()`를 `FailedImageCleanupService.recordDeleteObject`에 기록하지만 호출자에게 실패를 알리지 않는다. `deleteFolder`는 partial error를 object 단위로 기록하고 계속 진행하며, `SdkException`에서는 folder 단위 기록 후 `BusinessException(ImageErrorCode.IMAGE_FOLDER_DELETE_FAILED)`를 던진다. 재시도 서비스는 존재하지만 S3 호출을 트랜잭션 안에서 수행한다는 주석도 있다.
- 위험도: Medium
- 개선 방향: bulk/object/folder 삭제의 실패 전파 정책을 분리하고, cleanup job 상태와 사용자 요청 성공 여부가 어떤 관계인지 명확히 한다.
- 수정 시 주의사항: 이미 실패 cleanup 보상 로직과 테스트 일부가 있으므로, 기존 보상 흐름을 깨지 않도록 object 단위 idempotency와 folder retry 중복 기록을 유지해야 한다.
- 추천 테스트: bulk partial error, chunk 전체 `SdkException`, folder list 실패, folder delete partial error, retry 중 중복 scheduler 실행 상황을 검증한다.

### 14. 고위험 도메인 테스트 범위가 좁음

- 제목: Payment, Notification, WebSocket, AI, Security 테스트 부재
- 분류: Missing tests
- 위치: `src/test/java`
- 실제 코드 근거: 현재 테스트 파일은 14개이며, `Bookmark`, `Chat` 일부, `Comment`, `Post`, `User`, `ImageCleanup` 일부에 집중되어 있다. `Payment/IAP`, `PushNotificationService`, `JwtTokenFilter`, `StompChannelInterceptor`, `OpenAiClientImpl`, `AiChatCoordinatorService` 테스트 파일은 확인되지 않았다.
- 위험도: High
- 개선 방향: 결제 멱등성, 알림 중복 전송, WebSocket 인증/인가, Redis presence, AI 외부 API 실패, 이미지 cleanup 보상에 대한 단위/통합 테스트를 우선 추가한다.
- 수정 시 주의사항: `pgroonga` extension 권한이 필요한 검색 테스트는 로컬 환경에서 실패할 수 있으므로, 환경 의존 테스트와 순수 단위 테스트를 분리해야 한다.
- 추천 테스트: 위 각 고위험 도메인별 service 단위 테스트와 WebSocket/STOMP 통합 테스트를 추가하고, `pgroonga` 의존 테스트는 실행 조건을 명확히 한다.

### 15. 권한 모델에서 `VISITOR`와 `USER`가 광범위하게 동일 취급됨

- 제목: `SecurityConfig.anyRequest().hasAnyRole("VISITOR", "USER", "ADMIN")`
- 분류: Security concerns / Architectural smells
- 위치: `src/main/java/core/global/security/SecurityConfig.java:61-86`
- 실제 코드 근거: TODO에는 "user와 visitor 경로 권한 분리"가 필요하다고 적혀 있으나, 현재 대부분의 비공개 경로는 `VISITOR`, `USER`, `ADMIN` 모두 접근 가능하다.
- 위험도: Medium
- 개선 방향: visitor가 허용되는 기능과 profile setup 이후 user만 허용되는 기능을 endpoint 기준으로 분리한다.
- 수정 시 주의사항: 여러 service에서 `userRoleDetectService.isProfileSetUpUser(...)`를 별도로 호출하고 있어, SecurityConfig에서 일괄 차단할 경우 기존 응답 코드와 사용자 온보딩 흐름이 달라질 수 있다.
- 추천 테스트: visitor token으로 게시글 작성, 채팅 발송, 결제, 이미지 업로드 등 핵심 기능 접근이 의도대로 허용/거부되는지 검증한다.

### 16. Chat DB 저장 경로에 중복 상태 갱신과 강제 flush가 있음

- 제목: `ChatDbService.saveMessage`의 중복 `updateLastMessageSentAt`와 `saveAndFlush`
- 분류: Technical debt / Transaction boundary risks
- 위치: `src/main/java/core/domain/chat/service/ChatDbService.java:54-76`
- 실제 코드 근거: 같은 `message.getSentAt()` 값으로 `room.updateLastMessageSentAt(...)`가 두 번 연속 호출되고, 메시지는 `chatMessageRepository.saveAndFlush(message)`로 즉시 flush된다.
- 위험도: Low
- 개선 방향: 중복 호출을 제거하고, 즉시 flush가 필요한 이유를 명확히 하거나 일반 `save`로 대체 가능한지 검토한다.
- 수정 시 주의사항: 이후 `savedMessage.getId()`가 즉시 필요하고 unread/room summary 계산에 사용되므로 ID 생성 전략과 이벤트 발행 시점을 확인해야 한다.
- 추천 테스트: 메시지 저장 후 `lastMessageSentAt`, `messageCount`, sender `lastReadMessageId`, 이벤트 payload가 기존과 동일한지 검증한다.

## 종합 요약

1. 발견한 개선 후보 개수: 16개
2. 가장 위험한 후보 5개:
   - `PushNotificationService`의 FCM 중복 전송
   - `IapService.verify`의 외부 API 호출 포함 트랜잭션
   - `UserService.refreshTokens`의 refresh token fragment 로깅
   - `StompChannelInterceptor`의 WebSocket token 검증/blacklist 누락 가능성
   - `SecurityConfig`의 WebSocket message authorization 비활성화
3. 먼저 처리하면 좋은 순서:
   - 인증/인가와 token 로그 제거
   - 결제 멱등성 및 트랜잭션 경계 정리
   - FCM 중복 전송 제거와 알림 실패 처리 테스트 추가
   - WebSocket/Redis presence 일관성 정리
   - 이미지 cleanup과 AI 외부 API 실패 처리 보강
4. 사용자가 직접 판단해야 할 항목:
   - `VISITOR`와 `USER`를 어떤 endpoint 기준으로 분리할지
   - WebSocket 부하 테스트 허용 로직을 운영에서도 유지할지
   - Chat 번역 cache를 write path에서 즉시 저장할지, read path warming만 유지할지
   - 이미지 삭제 partial failure를 사용자 요청 실패로 볼지, cleanup job 보상 대상으로만 볼지
   - `pgroonga` 의존 테스트를 로컬 필수 검증에 포함할지 별도 환경 검증으로 둘지
