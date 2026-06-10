# 아키텍처 흐름 다이어그램

이 문서는 `docs/agent/project-context.md`, `docs/agent/architecture.md`, `docs/agent/domain-overview.md`, `docs/agent/coding-convention-detected.md`, `docs/agent/risky-areas.md`와 실제 코드 진입점을 기준으로 작성했다. 코드로 명확하지 않은 부분은 단정하지 않는다.

## 1. Request Flow

일반 REST 요청은 `SecurityFilterChain`을 거친 뒤 Controller -> Service -> Repository 흐름으로 처리된다. WebSocket은 `/ws` endpoint와 STOMP 흐름을 따르므로 별도 섹션에서 다룬다.

```mermaid
flowchart TD
    Client["Client"]
    HTTP["HTTP Request"]
    Security["SecurityConfig / SecurityFilterChain"]
    Smoke["SmokeTokenFilter"]
    JWT["JwtTokenFilter"]
    Presence["PresenceActivityFilter"]
    Authz["Path Authorization Rules"]
    Controller["Controller (@RestController / @Controller)"]
    Service["Service (@Service / @Transactional)"]
    Repository["Repository (Spring Data JPA / QueryDSL / JDBC)"]
    DB["PostgreSQL"]
    Redis["Redis"]
    External["External APIs"]
    Response["DTO / ApiResponse"]
    Exception["GlobalExceptionHandler / ApiErrorResponse"]

    Client --> HTTP --> Security
    Security --> Smoke --> JWT --> Presence --> Authz
    Authz -->|허용| Controller
    Authz -->|거부| Exception
    Controller --> Service
    Service --> Repository --> DB
    Service --> Redis
    Service --> External
    Service --> Response --> Client
    Controller -->|BusinessException / Validation / 기타 예외| Exception --> Client
```

확인된 규칙:

- `core.domain.*`는 업무 도메인, `core.global.*`는 보안/설정/예외/Redis/WebSocket/이미지/메트릭/외부 연동을 맡는다.
- API 응답은 주로 `core.global.dto.ApiResponse`와 DTO를 사용하지만, 일부 Controller는 DTO를 직접 반환한다.
- `Entity`를 직접 API 응답으로 반환하지 않는 원칙이 문서화되어 있다.

## 2. Authentication Flow

인증은 로그인/회원가입 시 JWT를 발급하고, 일반 요청에서는 `JwtTokenFilter`가 header 또는 cookie의 access token을 확인해 `SecurityContext`를 구성하는 방식이다.

```mermaid
sequenceDiagram
    participant Client as Client
    participant Login as LoginRegisterController
    participant UserSvc as UserService / GoogleAuthService / AppleAuthService
    participant UserRepo as UserRepository
    participant Jwt as JwtTokenProvider
    participant Redis as RedisService
    participant Security as JwtTokenFilter
    participant Context as SecurityContextHolder

    Client->>Login: 로그인 요청<br/>/doLogin, /google/app-login, /apple/app-login
    Login->>UserSvc: 인증 처리 위임
    UserSvc->>UserRepo: 사용자 조회 또는 생성
    UserSvc->>Jwt: access token / refresh token 생성
    UserSvc->>Redis: refresh token 저장
    UserSvc-->>Client: LoginResponseDto / AuthResponse

    Client->>Security: 보호 API 요청<br/>Authorization: Bearer 또는 accessToken cookie
    Security->>Redis: blacklist 여부 확인
    Security->>Jwt: token validate / email / userId / role 추출
    Security->>Context: CustomUserDetails 인증 객체 저장
    Security-->>Client: SecurityConfig 권한 규칙에 따라 통과 또는 거부
```

권한 판단 구조:

```mermaid
flowchart TD
    Request["Request URI"]
    Permit["PermitAllPaths"]
    Admin["AdminOnlyPaths"]
    AI["AIOnlyPaths"]
    Any["Other Paths"]
    Public["permitAll"]
    AdminRole["ROLE_ADMIN"]
    AIRole["ROLE_AI 또는 ROLE_ADMIN"]
    UserRole["ROLE_VISITOR / ROLE_USER / ROLE_ADMIN"]

    Request --> Permit --> Public
    Request --> Admin --> AdminRole
    Request --> AI --> AIRole
    Request --> Any --> UserRole
```

주의할 점:

- `SecurityConfig`, `JwtTokenFilter`, `JwtTokenProvider`, `PermitAllPaths`, `AdminOnlyPaths`, `AIOnlyPaths`는 고위험 영역이다.
- Refresh token은 Redis에 저장되고, logout 시 access token blacklist와 refresh token 삭제가 수행된다.
- WebSocket 보안은 HTTP Security와 별도 흐름이며 `StompChannelInterceptor`를 함께 확인해야 한다.

## 3. Chat Flow

채팅은 STOMP `/app` 메시지를 `ChatWebSocketController`가 받고, `ChatMessageService`가 DB 저장, 번역, 수신자 계산, 이벤트 발행을 처리한다. 실제 WebSocket 전송과 채팅 push 알림은 이벤트 리스너에서 이어진다.

```mermaid
sequenceDiagram
    participant Client as Client
    participant WS as WebSocket / STOMP (/ws)
    participant Controller as ChatWebSocketController
    participant MsgSvc as ChatMessageService
    participant DbSvc as ChatDbService
    participant DB as PostgreSQL
    participant Redis as RedisService
    participant Trans as ChatTranslationService / TranslationService
    participant Event as ApplicationEventPublisher
    participant Listener as ChatEventListener
    participant Socket as FastSocketSender / SimpMessagingTemplate
    participant Notify as NotificationEventListener

    Client->>WS: /app/chat.sendMessage
    WS->>Controller: @MessageMapping("/chat.sendMessage")
    Controller->>MsgSvc: processAndSendChatMessage(req)
    MsgSvc->>DbSvc: saveAndProcessBusinessRules(req)
    DbSvc->>DB: ChatMessage / ChatRoom 규칙 저장
    MsgSvc->>Redis: active user 목록 조회
    MsgSvc->>Trans: 언어별 번역 및 캐시
    MsgSvc->>DB: ChatMessageTranslation 저장
    MsgSvc->>Event: MessageSentEvent 발행
    Event->>Listener: AFTER_COMMIT + @Async
    Listener->>Socket: 수신자별 NEW_MESSAGE / ROOM_UPDATE 전송
    Listener->>Event: NotificationBulkEvent 발행
    Event->>Notify: in-app 알림 저장 및 FCM push 처리
```

읽음/삭제/미디어 메시지:

```mermaid
flowchart TD
    MarkRead["/app/chat.markAsRead"]
    Media["/app/chat.sendMedia"]
    Delete["/app/chat.deleteMessage"]
    Service["ChatMessageService"]
    ReadEvent["MessageReadEvent"]
    MediaEvent["MessageSentEvent + ImageModerationEvent"]
    DeleteNote["현재 Controller는 로그만 확인됨"]

    MarkRead --> Service --> ReadEvent
    Media --> Service --> MediaEvent
    Delete --> DeleteNote
```

주의할 점:

- 메시지 저장, 번역, 이벤트 발행, WebSocket 전송, push 전송 순서는 임의로 바꾸면 안 된다.
- `/chat.sendMessageBad`는 레거시/성능 테스트용 경로로 남아 있다.
- `ChatWebSocketController.deleteMessage`는 코드상 로그만 남기는 것으로 확인되어 실제 삭제 구현 위치는 추가 확인이 필요하다.

## 4. Image Upload Flow

이미지 업로드는 presigned URL을 발급하고, 클라이언트가 Object Storage에 직접 업로드한 뒤, 게시글/프로필/채팅방 등 도메인 저장 시 key 또는 URL을 받아 최종 경로로 복사/저장하는 구조다.

```mermaid
sequenceDiagram
    participant Client as Client
    participant Controller as ImageController
    participant ImageSvc as ImageServiceImpl
    participant PostImage as PostImageServiceImpl
    participant Presigner as S3Presigner
    participant Storage as NCP Object Storage / S3 API
    participant Domain as PostService / UserService / ChatRoomService
    participant Repo as ImageRepository
    participant Event as ApplicationEventPublisher
    participant Moderation as ImageModerationEventListener

    Client->>Controller: POST /api/v1/images/presign
    Controller->>ImageSvc: generatePresignedUrls(request)
    ImageSvc->>PostImage: generatePresignedUrls(request)
    PostImage->>Presigner: presignPutObject
    Presigner-->>Client: upload URL, method, headers, key
    Client->>Storage: PUT image with presigned URL

    Client->>Domain: 게시글/프로필/채팅방 저장 요청에 key 또는 URL 전달
    Domain->>ImageSvc: savePostImages / saveUserProfileImage 등
    ImageSvc->>PostImage: staging key 정규화 및 최종 경로 복사
    PostImage->>Storage: copy/delete staging object
    PostImage->>Repo: Image 엔티티 저장
    PostImage->>Event: ImageModerationEvent 발행
    Event->>Moderation: 이미지 검수/상태 처리
```

삭제와 실패 보상:

```mermaid
flowchart TD
    DeleteReq["이미지 삭제 요청"]
    StorageClient["S3ImageStorageClient"]
    DefaultCheck["default/ 경로 여부 확인"]
    DeleteObjects["deleteObjectsBulk / deleteFolder"]
    Fail["SdkException 또는 부분 실패"]
    Cleanup["FailedImageCleanupService"]
    Scheduler["FailedImageCleanupScheduler"]

    DeleteReq --> StorageClient --> DefaultCheck
    DefaultCheck -->|default/| Skip["삭제 제외"]
    DefaultCheck -->|일반 object| DeleteObjects
    DeleteObjects -->|성공| Done["완료"]
    DeleteObjects -->|실패| Fail --> Cleanup --> Scheduler
```

주의할 점:

- `default/` 경로 이미지는 삭제 대상에서 제외된다.
- URL과 object key 변환, CDN URL, thumbnail URL, staging key 규칙은 클라이언트 표시와 실제 삭제에 직접 영향을 준다.
- `FailedImageCleanup` 흐름은 현재 작업트리에 변경이 존재하므로 같은 파일을 다룰 때 최신 상태를 다시 확인해야 한다.

## 5. Notification Flow

알림은 도메인 이벤트를 받아 in-app `Notification`을 저장하고, 사용자 push 동의/카테고리 설정/채팅방 음소거를 확인한 뒤 Firebase FCM으로 전송한다.

```mermaid
sequenceDiagram
    participant Domain as Domain Service
    participant Event as ApplicationEventPublisher
    participant Listener as NotificationEventListener
    participant MsgGen as NotificationMessageGenerator
    participant NotiSvc as UserNotificationService
    participant DB as PostgreSQL
    participant Push as PushNotificationService
    participant Settings as UserNotificationSettingRepository
    participant TokenRepo as UserDeviceTokenRepository
    participant FCM as FirebaseMessaging

    Domain->>Event: NotificationEvent 또는 NotificationBulkEvent 발행
    Event->>Listener: @Async @EventListener
    Listener->>MsgGen: 알림 메시지 생성
    Listener->>NotiSvc: createAndSaveNotification
    NotiSvc->>DB: Notification 저장
    Listener->>Push: sendPushNotification 또는 sendGroupPush
    Push->>Settings: master/category/room setting 확인
    Push->>TokenRepo: device token 조회
    Push->>FCM: 단건 또는 batch push 전송
    Push->>TokenRepo: invalid token 삭제
```

채팅 알림은 `ChatEventListener`에서 `NotificationBulkEvent`를 발행해 대량 알림 흐름으로 이어진다.

```mermaid
flowchart TD
    MessageSent["MessageSentEvent"]
    ChatListener["ChatEventListener"]
    Bulk["NotificationBulkEvent"]
    SaveAll["NotificationRepository.saveAll"]
    GroupPush["PushNotificationService.sendGroupPush"]
    Firebase["FirebaseMessaging.sendEachForMulticast"]

    MessageSent --> ChatListener --> Bulk --> SaveAll --> GroupPush --> Firebase
```

주의할 점:

- 알림 타입 enum 변경은 DB constraint, migration, 클라이언트 표시 로직과 함께 확인해야 한다.
- FCM 실패 시 `UNREGISTERED`, `INVALID_ARGUMENT`, `SENDER_ID_MISMATCH` 계열 token은 삭제된다.
- `PushNotificationService`는 `@CircuitBreaker` fallback을 사용한다.

## 6. Payment Flow

결제는 `/api/iap/verify`에서 플랫폼별 store 검증을 수행하고, purchase, entitlement, item, bonus grant를 저장한다.

```mermaid
sequenceDiagram
    participant Client as Client
    participant Controller as IapController
    participant Service as IapService
    participant Apple as AppleClient
    participant Google as GoogleClient
    participant ProductRepo as IapProductRepository
    participant PurchaseRepo as IapPurchaseRepository
    participant EntRepo as IapEntitlementRepository
    participant ItemRepo as UserItemRepository
    participant BonusRepo as IapBonusGrantRepository

    Client->>Controller: POST /api/iap/verify
    Controller->>Service: verify(VerifyRequest)
    alt iOS
        Service->>Apple: getTransaction(transactionId)
        Apple-->>Service: AppleTransactionInfo
        Service->>ProductRepo: platform + storeProductId 매핑
        Service->>PurchaseRepo: iOS transactionId 기준 멱등 조회/저장
    else Android
        Service->>Google: verify(productId, purchaseToken)
        Google-->>Service: GooglePurchase
        Service->>ProductRepo: platform + storeProductId 매핑
        Service->>PurchaseRepo: Android purchaseToken 기준 멱등 조회/저장
    end
    Service->>ItemRepo: boost_profile 등 consumable item 반영
    Service->>BonusRepo: premium 최초 활성 welcome_frame 부여 여부 확인/저장
    Service->>EntRepo: IapEntitlement 저장
    Service-->>Client: EntitlementResponse
```

권한 조회:

```mermaid
flowchart LR
    Client["Client"]
    EntApi["GET /api/iap/entitlements"]
    Service["IapService.getEntitlements"]
    Repo["IapEntitlementRepository"]
    Response["EntitlementResponse"]

    Client --> EntApi --> Service --> Repo --> Response --> Client
```

주의할 점:

- 결제 멱등성 기준은 iOS `transactionId`, Android `purchaseToken`이다.
- product catalog와 store product id 전체 목록은 코드만으로 확정하지 않는다.
- Android 상품 미매핑 시 TODO성 error code 사용이 있어 에러 정책은 불명확하다.

## 7. AI User Flow

AI 응답은 일반 채팅 메시지가 저장된 뒤 `MessageCreatedEvent`가 커밋 이후 처리되면서 시작된다. AI 메시지도 최종적으로 `ChatMessageService.processAndSendChatMessage`를 다시 호출해 일반 채팅 전송 흐름을 탄다.

```mermaid
sequenceDiagram
    participant Human as Human User
    participant ChatSvc as ChatMessageService
    participant DB as PostgreSQL
    participant Event as ApplicationEventPublisher
    participant AiListener as AiReplyEventListener
    participant Debounce as AiMessageDebouncer
    participant Coordinator as AiChatCoordinatorService
    participant AiUserSvc as AiChatUserService
    participant Prompt as AiPromptManager / PromptMapper
    participant OpenAI as OpenAiClientImpl
    participant Scheduler as ThreadPoolTaskScheduler

    Human->>ChatSvc: 일반 채팅 메시지 전송
    ChatSvc->>DB: ChatMessage 저장
    ChatSvc->>Event: MessageCreatedEvent 또는 관련 채팅 이벤트 발행
    Event->>AiListener: AFTER_COMMIT
    AiListener->>DB: 채팅방 참여자 중 AI_BOT 조회
    AiListener->>Debounce: room별 메시지 buffer
    Debounce->>Scheduler: debounce 지연 실행 예약
    Scheduler->>Coordinator: coordinateReplies(roomId, aiParticipants, message)
    Coordinator->>DB: 최근 메시지/마지막 AI 화자/그룹 여부 조회
    Coordinator->>Scheduler: FAST 또는 SLOW reply 예약
    Scheduler->>AiUserSvc: processAiResponse
    AiUserSvc->>Prompt: system prompt와 input 구성
    AiUserSvc->>OpenAI: Responses API 호출
    OpenAI-->>AiUserSvc: AI 응답 텍스트
    AiUserSvc->>Scheduler: 분할 메시지별 전송 지연 예약
    Scheduler->>ChatSvc: AI SendMessageRequest 전송
```

AI 응답 제어:

```mermaid
flowchart TD
    Event["MessageCreatedEvent"]
    Listener["AiReplyEventListener"]
    IsAiSender["sender가 Role.AI 인가"]
    DebounceHuman["debounce 2500ms"]
    DebounceAi["debounce 5000ms"]
    Buffer["AiMessageDebouncer"]
    Thinking["AiThinkingStateManager"]
    Coordinator["AiChatCoordinatorService"]
    Policy["멘션, 최근 화자, group 여부, 확률 감쇠 판단"]
    Fast["FAST reply 예약"]
    Slow["SLOW reply 예약"]
    Generate["AiChatUserService + OpenAiClientImpl"]
    Send["ChatMessageService로 재전송"]

    Event --> Listener --> IsAiSender
    IsAiSender -->|아니오| DebounceHuman --> Buffer
    IsAiSender -->|예| DebounceAi --> Buffer
    Buffer --> Thinking
    Thinking -->|이미 생각 중| Reschedule["1초 뒤 재시도"]
    Thinking -->|가능| Coordinator --> Policy
    Policy --> Fast --> Generate --> Send
    Policy --> Slow --> Generate --> Send
```

주의할 점:

- AI 응답은 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` 이후 시작된다.
- jailbreak/AI identity 패턴 필터와 `PASS` 응답 skip 로직이 있다.
- AI 응답 정책, prompt 버전 관리, 안전 필터링 기준은 코드 외부 문서가 없어 완전히 확정할 수 없다.

## 공통으로 불명확한 부분

- 실제 운영 인프라, secret 관리 방식, Prometheus/Grafana dashboard, 배포 파이프라인 전체는 저장소만으로 확정할 수 없다.
- Elasticsearch 템플릿과 스크립트는 존재하지만 Java 코드에서 직접 사용하는 흐름은 확인되지 않았다.
- README의 `Spring Batch` 표기는 `build.gradle`에서 dependency가 확인되지 않으므로 현재 실행 흐름으로 단정하지 않는다.
