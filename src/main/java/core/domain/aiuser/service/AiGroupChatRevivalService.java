package core.domain.aiuser.service;

import core.domain.aiuser.client.AiClient;
import core.domain.aiuser.entity.AiPersona;
import core.domain.aiuser.repository.AiPersonaRepository;
import core.domain.chat.dto.SendMessageRequest;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.chat.service.ChatMessageService;
import core.domain.user.entity.User;
import core.global.enums.chat.MessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiGroupChatRevivalService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatMessageService chatMessageService;

    // AI 연동 관련
    private final AiClient aiClient;
    private final AiPromptManager aiPromptManager;
    private final AiPersonaRepository aiPersonaRepository;

    private final TransactionTemplate transactionTemplate;


    private static final SecureRandom secureRandom = new SecureRandom();

    // 1. 침묵 기준 시간
    private static final long SILENCE_THRESHOLD_MINUTES = 720;

    // 2. 스케줄러 실행 주기
    //@Scheduled(cron = "0 0 * * * *")
    public void reviveSilentChatRooms() {

        // 1. AI가 참여 중인 방 ID 목록 확보
        List<Long> aiRoomIds = chatParticipantRepository.findAllAiParticipatedRoomIds();

        if (aiRoomIds.isEmpty()) return;

        if (aiRoomIds.size() > 2000) {
            aiRoomIds = aiRoomIds.subList(0, 2000);
        }

        // 2. 침묵 방 조회 (인덱스 활용)
        Instant threshold = Instant.now().minus(Duration.ofMinutes(SILENCE_THRESHOLD_MINUTES));
        Pageable limit = PageRequest.of(0, 40);

        List<ChatRoom> silentRooms = chatRoomRepository.findSilentRoomsByRoomIds(aiRoomIds, threshold, limit);

        if (silentRooms.isEmpty()) return;

        log.info("📢 Revival Service (TEST): Found {} silent rooms.", silentRooms.size());

        for (ChatRoom room : silentRooms) {
            // 3. 확률 체크
            if (secureRandom.nextInt(100) < 70) {
                tryTriggerRevivalMessage(room);
            }
        }
    }

    private void tryTriggerRevivalMessage(ChatRoom room) {
        RevivalContext context = transactionTemplate.execute(status -> {
            // 1. AI 참여자 조회
            List<User> aiParticipants = chatRoomRepository.findAiParticipantsByRoomId(room.getId());
            if (aiParticipants.isEmpty()) return null;

            // 2. 메시지 조회 (최신 20개 가져와서 시간순 정렬)
            // Repository 메서드명: findTop20ByChatRoomIdOrderBySentAtDesc
            List<ChatMessage> lastMessages = chatMessageRepository.findTop20ByChatRoomIdOrderBySentAtDesc(room.getId());
            Collections.reverse(lastMessages); // 과거 -> 최신 순으로 정렬 변경

            // ==========================================================
            // 🚨 [수정됨] 3. 발화자 선정 (Entity 구조 반영 + 스킵 로직)
            // ==========================================================
            User initiatorAi;

            if (lastMessages.isEmpty()) {
                // 메시지가 하나도 없으면 아무나 선정
                initiatorAi = aiParticipants.get(secureRandom.nextInt(aiParticipants.size()));
            } else {
                // 가장 최근 메시지 (리스트를 뒤집었으므로 마지막 요소가 최신)
                ChatMessage lastMsg = lastMessages.get(lastMessages.size() - 1);

                // ⚠️ Entity 수정 반영: User 객체에서 ID 추출
                Long lastSenderId = lastMsg.getSender().getId();

                // "마지막에 말한 AI"를 제외한 후보군 생성
                List<User> candidates = aiParticipants.stream()
                        .filter(ai -> !ai.getId().equals(lastSenderId))
                        .collect(Collectors.toList());

                if (!candidates.isEmpty()) {
                    // 후보가 있다면 그 중에서 랜덤 선정 (티키타카)
                    initiatorAi = candidates.get(secureRandom.nextInt(candidates.size()));
                } else {
                    // 🛑 후보가 없다면? (방금 말한 애가 유일한 AI인 경우 등) -> 스킵!
                    log.info("🚫 Revival Skipped: Room[{}] AI[{}] already spoke last.", room.getId(), lastSenderId);
                    return null; // 트랜잭션 종료 및 스킵
                }
            }
            // ==========================================================

            // 4. Lazy Loading 강제 초기화 (User 정보 및 Persona)
            // 프롬프트 생성 시 필요한 정보들을 미리 로딩
            String hobby = initiatorAi.getHobby();
            String country = initiatorAi.getCountry();

            AiPersona persona = aiPersonaRepository.findByUserId(initiatorAi.getId()).orElse(null);
            if (persona != null) {
                persona.getInstruction(); // LOB 데이터 등 Lazy 로딩 트리거
            }

            return new RevivalContext(initiatorAi, persona, lastMessages);
        });

        // context가 null이면(후보가 없어서 스킵된 경우) 메서드 종료
        if (context == null) return;

        // 🧠 [2단계] AI 메시지 생성 (DB 연결 불필요 구간)
        generateDynamicRevivalMessage(context.aiUser, context.persona, context.lastMessages)
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        revivalMessage -> sendRevivalMessage(room, context.aiUser, revivalMessage),
                        error -> log.error("Revival generation failed for room {}", room.getId(), error)
                );
    }

    private record RevivalContext(User aiUser, AiPersona persona, List<ChatMessage> lastMessages) {}

    /**
     * AI 페르소나와 이전 대화를 기반으로 '살아있는' 멘트 생성
     * (24시간 지난 대화는 문맥에서 제외)
     */
    private Mono<String> generateDynamicRevivalMessage(User aiUser, AiPersona persona, List<ChatMessage> lastMessages) {
        try {
            // 🟢 [핵심] 24시간 필터링: 너무 오래된 메시지는 문맥에서 제거
            Instant oneDayAgo = Instant.now().minus(Duration.ofHours(24));

            List<ChatMessage> validContext = lastMessages.stream()
                    .filter(msg -> msg.getSentAt().isAfter(oneDayAgo))
                    .collect(Collectors.toList());

            // (참고) validContext가 비어있으면 프롬프트 매니저가 "대화 내역 없음"으로 처리하여
            // AI가 "새로운 주제"를 꺼내도록 유도하게 됨.

            // 1. 프롬프트 생성 (받아온 persona 사용)
            String prompt = aiPromptManager.buildRevivalPrompt(aiUser, persona, validContext);

            // 2. API 호출 (System / User 메시지 분리 권장)
            List<Map<String, Object>> input = List.of(
                    Map.of("role", "system", "content", prompt),
                    Map.of("role", "user", "content",
                            "이곳은 여러 명이 있는 '단체 채팅방'입니다. " +
                                    "특정 1명을 지칭('너')하지 말고, '너희', '다들' 등을 사용하여 그룹 전체에게 자연스럽게 대화를 유도하거나 질문을 던져주세요."
                    )
            );

            return aiClient.generateResponse(input)
                    .filter(response -> !response.isBlank())
                    .map(response -> response.replace("\"", "").trim())
                    .defaultIfEmpty(randomFallbackTopic())
                    .onErrorResume(error -> {
                        log.warn("LLM Revival Failed (Using Fallback): {}", error.getMessage());
                        return Mono.just(randomFallbackTopic());
                    });
        } catch (Exception e) {
            log.warn("LLM Revival Failed (Using Fallback): {}", e.getMessage());
            return Mono.just(randomFallbackTopic());
        }
    }

    private void sendRevivalMessage(ChatRoom room, User aiUser, String revivalMessage) {
        SendMessageRequest request = new SendMessageRequest(
                room.getId(),
                aiUser.getId(),
                revivalMessage,
                MessageType.TEXT
        );

        try {
            chatMessageService.processAndSendChatMessage(request);
            log.info("CPR Success: Room[{}] AI[{}] Msg[{}]", room.getId(), aiUser.getFirstName(), revivalMessage);
        } catch (Exception e) {
            log.error("Revival failed for room {}", room.getId(), e);
        }
    }

    private String randomFallbackTopic() {
        return FALLBACK_TOPICS[secureRandom.nextInt(FALLBACK_TOPICS.length)];
    }

    private static final String[] FALLBACK_TOPICS = {
            "뭐해?",
            "뭐하니",
            "hmmm",
            "hi",
            "Hi guys!",
            "Anyone awake?",
            // Korean Version
            "다들 자니?",
            "심심하다",
            "하이",
            "반가워요!",
            "다들 뭐해요?",
            "누구 없나",
            "배고프다...",
            "오늘 날씨 어때요?",
            "안녕 안녕",
            "다들 밥 먹었어?",
            "심심한 사람",
            "하이",
            "좋은 아침!",
            "굿밤",
            "졸리다",
            "누구 대화하자",
            "반가워",
            "인사해줘요",
            "헬로",
            "궁금한게 있어",
            "다들 뭐함",
            "처음 온 사람?",
            "뭐하고 놀까",
            "노래 추천좀",
            "다들 오늘 하루 어땠어?",
            "다들 있는 곳 날씨 어때?",
            "다들 자나보네",
            "안뇽",
            "zzz",
            "좋은날!!",
            "뭐 재미있는거 없나",
            "웃긴 얘기 해줄 사람",
            "배고프다",
            "게임 추천좀",
            "드라마 추천 해 줄 사람?",
            "안녕들 하신가",
            "심심하네",
            "같이 놀자",
            "질문 있어!",
            "하이요",
            "반갑습니다",
            "뭐해 다들?",
            "깨어있는 사람?",
            // English Version
            "Hey!",
            "What's up?",
            "Hi there",
            "Hello everyone!",
            "Anyone here?",
            "How's it going?",
            "Bored...",
            "Hi guys!",
            "Good morning",
            "Good night",
            "What are you doing?",
            "Anybody awake?",
            "How are you?",
            "Hey hey",
            "Need someone to talk to",
            "Anyone active?",
            "What's the vibe?",
            "Hellooo",
            "Yo",
            "Sup",
            "Feeling bored",
            "How's your day?",
            "Any plans today?",
            "Where is everyone?",
            "Let's chat",
            "What's everyone up to?",
            "Can you hear me?",
            "Anyone online?",
            "Vibe check",
            "Hope you're well",
            "Busy?",
            "Talk to me",
            "How's life?",
            "Hey friends",
            "Just saying hi",
            "What's new?",
            "Peace",
            "Having a good day?",
            "Can't sleep",
            "Hi hi",
            "What's the tea?",
            "So bored right now",
            "Still awake?",
            "Say something!",
            "Helloooo?",
            "Anyone want to talk?",
            "Is it just me or is it quiet?",
            "Yo yo",
            "Have a nice day!"
    };
}
