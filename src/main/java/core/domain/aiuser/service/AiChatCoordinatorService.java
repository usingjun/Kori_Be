package core.domain.aiuser.service;

import core.domain.aiuser.dto.MessageCreatedEvent;
import core.domain.chat.entity.ChatMessage;
import core.domain.chat.repository.ChatMessageRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.user.entity.User;
import core.global.enums.user.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiChatCoordinatorService {

    private final AiChatUserService aiChatUserService;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ThreadPoolTaskScheduler taskScheduler;
    private final AiThinkingStateManager thinkingStateManager;

    private static final SecureRandom secureRandom = new SecureRandom();

    // 1. 유저 활동 판단 기준 (1시간)
    // - 마지막 사람이 말한 지 1시간 이내면 'Active Mode' (AI 절제)
    // - 1시간이 지났으면 'Revival/Playground Mode' (AI 티키타카 허용)
    private static final int HUMAN_SILENCE_THRESHOLD_MINUTES = 60;

    // 2. 최근 화자 판단 기준 (5분)
    private static final int ACTIVE_TALKER_WINDOW_MINUTES = 5;

    // 3. 한 턴당 최대 빠른 응답 수
    private static final int MAX_FAST_REPLIES_PER_TURN = 2;

    public void coordinateReplies(Long roomId, Set<User> aiParticipants, MessageCreatedEvent lastEvent, String userMessage) {

        boolean isGroupChat = chatRoomRepository.isGroupChat(roomId);

        Long senderId = lastEvent.messageResponse().senderId();
        boolean isHumanMessage = aiParticipants.stream()
                .noneMatch(ai -> ai.getId().equals(senderId));

        if (isGroupChat && !isHumanMessage && shouldSkipByProbabilityDecay(roomId)) {
            log.info("💤 AI Chat Faded out (Decay Logic triggered) | RoomId: {}", roomId);
            return;
        }

        List<User> aiList = new ArrayList<>(aiParticipants);
        Collections.shuffle(aiList);

        List<User> mentionedAIs = aiList.stream()
                .filter(ai -> isMentioned(userMessage, ai.getFirstName()))
                .toList();

        if (!mentionedAIs.isEmpty()) {
            aiList.retainAll(mentionedAIs);
        }
        if (aiList.isEmpty()) return;


        Long lastAiSpeakerId = findLastAiSpeakerId(roomId);
        sortParticipantsByPriority(aiList, roomId, userMessage, lastAiSpeakerId);

        if (aiList.isEmpty()) return;

        long accumulatedFastDelay = 0;
        int currentFastCount = 0;
        long baseThinkingTime = calculateBaseThinkingTime(userMessage);

        for (int i = 0; i < aiList.size(); i++) {
            User aiUser = aiList.get(i);

            if (thinkingStateManager.isThinking(roomId, aiUser.getId())) {
                log.info("🚫 AI [{}] is already thinking. Skipping to prevent duplicate reply.", aiUser.getFirstName());
                continue;
            }

            boolean isMainSpeakerCandidate = (i == 0);

            // 1. 기본 타입 결정
            ResponseType responseType = determineResponseType(aiUser, roomId, userMessage, isMainSpeakerCandidate);

            // 2. Quota(쿼터) 체크
            boolean isDirectlyMentioned = isMentioned(userMessage, aiUser.getFirstName());
            boolean isLastSpeaker = (lastAiSpeakerId != null && aiUser.getId().equals(lastAiSpeakerId));

            if (responseType == ResponseType.FAST) {
                if (!isDirectlyMentioned && !isLastSpeaker) {
                    if (currentFastCount >= MAX_FAST_REPLIES_PER_TURN) {
                        responseType = ResponseType.SLOW;
                    } else {
                        currentFastCount++;
                    }
                } else {
                    currentFastCount++;
                }
            }

            if (responseType == ResponseType.FAST) {
                long randomGap = secureRandom.nextLong(1000, 3000);
                long totalStepDelay = baseThinkingTime + randomGap;
                accumulatedFastDelay += totalStepDelay;

                scheduleFastResponse(aiUser, lastEvent, userMessage, isMainSpeakerCandidate, accumulatedFastDelay);

            } else {
                long lateDelayMinutes = secureRandom.nextLong(10, 60);
                scheduleLateReply(aiUser, lastEvent, userMessage, roomId, lateDelayMinutes);
            }
        }
    }

    /**
     * 🟢 [핵심 로직 변경] 상황별(Context-Aware) 확률 감쇠
     * + ⏰ [추가됨] 시간 단절(Time Gap) 체크: 오래된 대화는 Streak에 포함하지 않음
     */
    private boolean shouldSkipByProbabilityDecay(Long roomId) {
        // AI Streak 및 마지막 인간 대화 시간 파악을 위해 넉넉히 20개 조회
        List<ChatMessage> history = chatMessageRepository.findTop20ByChatRoomIdOrderBySentAtDesc(roomId);

        int aiStreak = 0;
        Instant lastHumanChatTime = null;
        Instant previousMsgTime = null; // 직전 메시지(현재 루프보다 더 최신 메시지)의 시간

        for (ChatMessage msg : history) {
            // 1. 시간 단절 체크 (대화가 끊긴 지 오래됐으면 Streak 계산 중단)
            if (previousMsgTime != null) {
                long gapMinutes = Duration.between(msg.getSentAt(), previousMsgTime).toMinutes();
                if (gapMinutes >= HUMAN_SILENCE_THRESHOLD_MINUTES) { // 60분 이상 차이나면
                    break; // 여기서 카운팅 종료! (예전 대화는 무시)
                }
            }
            previousMsgTime = msg.getSentAt(); // 시간 갱신

            // 2. 역할 확인
            if (msg.getSender().getUserRole() == Role.AI) {
                if (lastHumanChatTime == null) {
                    aiStreak++; // 사람 나오기 전까지 AI 연속 발언 카운트
                }
            } else {
                // 사람 발견! 시간 기록하고 루프 종료
                lastHumanChatTime = msg.getSentAt();
                break;
            }
        }

        // 모드 결정: 마지막 인간 대화가 60분 이내인가?
        boolean isHumanActive = false;
        if (lastHumanChatTime != null) {
            long minutesSinceHuman = Duration.between(lastHumanChatTime, Instant.now()).toMinutes();
            isHumanActive = (minutesSinceHuman < HUMAN_SILENCE_THRESHOLD_MINUTES);
        } else {
            // 사람이 아예 말한 적 없거나(null), 위 루프에서 끊겨서 null인 경우
            // -> 즉, 아주 오랫동안 사람이 없었으므로 Revival 모드로 간주
            isHumanActive = false;
        }

        // 상황에 맞는 확률 테이블 적용
        int survivalProb = isHumanActive
                ? getActiveModeProbability(aiStreak)   // 유저 있을 때 (엄격)
                : getRevivalModeProbability(aiStreak); // 죽은 방 일 때 (널널)

        // 주사위 굴리기 (확률보다 높으면 Skip)
        return secureRandom.nextInt(100) >= survivalProb;
    }

    /**
     * [Active Mode] 유저 대화 중: AI는 조미료 역할만 하고 빠르게 빠짐
     */
    private int getActiveModeProbability(int streak) {
        return switch (streak) {
            case 0 -> 100; // 첫 반응은 무조건
            case 1 -> 80;  // 티키
            case 2 -> 40;  // 타카 (여기서부터 급격히 감소)
            case 3 -> 10;  // 뇌절 방지
            default -> 0;
        };
    }

    /**
     * [Revival Mode] 유저 부재 중: AI끼리 대화를 길게 이어가며 분위기 조성
     */
    private int getRevivalModeProbability(int streak) {
        return switch (streak) {
            case 0, 1, 2 -> 100; // 초반 3턴은 무조건 이어감
            case 3, 4 -> 90;     // 5턴까지도 높은 확률 유지
            case 5 -> 70;        // 슬슬 마무리 각
            case 6 -> 50;
            case 7 -> 20;
            default -> 0;
        };
    }

    private Long findLastAiSpeakerId(Long roomId) {
        List<ChatMessage> history = chatMessageRepository.findTop10ByChatRoomIdOrderBySentAtDesc(roomId);
        for (ChatMessage msg : history) {
            if (msg.getSender().getUserRole() == Role.AI) {
                return msg.getSender().getId();
            }
        }
        return null;
    }

    private void sortParticipantsByPriority(List<User> aiList, Long roomId, String userMessage, Long lastAiSpeakerId) {
        Instant fiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(ACTIVE_TALKER_WINDOW_MINUTES));
        Set<Long> activeTalkerIds = new HashSet<>();

        // Active Talker 미리 조회 (쿼리 최적화 가능 포인트지만 일단 유지)
        for (User ai : aiList) {
            if (chatMessageRepository.existsBySenderIdAndChatRoomIdAndSentAtAfter(ai.getId(), roomId, fiveMinutesAgo)) {
                activeTalkerIds.add(ai.getId());
            }
        }

        aiList.sort((u1, u2) -> {
            // 1순위: 이름 멘션
            boolean u1Mentioned = isMentioned(userMessage, u1.getFirstName());
            boolean u2Mentioned = isMentioned(userMessage, u2.getFirstName());
            if (u1Mentioned && !u2Mentioned) return -1;
            if (!u1Mentioned && u2Mentioned) return 1;

            // 2순위: 현재 생각 중이거나 직전 화자
            boolean u1Target = (lastAiSpeakerId != null && u1.getId().equals(lastAiSpeakerId))
                    || thinkingStateManager.isThinking(roomId, u1.getId());
            boolean u2Target = (lastAiSpeakerId != null && u2.getId().equals(lastAiSpeakerId))
                    || thinkingStateManager.isThinking(roomId, u2.getId());

            if (u1Target && !u2Target) return -1;
            if (!u1Target && u2Target) return 1;

            // 3순위: 최근 활동 (Active Talker)
            boolean u1Active = activeTalkerIds.contains(u1.getId());
            boolean u2Active = activeTalkerIds.contains(u2.getId());
            if (u1Active && !u2Active) return -1;
            if (!u1Active && u2Active) return 1;

            return 0;
        });
    }

    private ResponseType determineResponseType(User aiUser, Long roomId, String userMessage, boolean isMainSpeaker) {

        if (isMentioned(userMessage, aiUser.getFirstName())) return ResponseType.FAST;

        Instant fiveMinutesAgo = Instant.now().minus(Duration.ofMinutes(ACTIVE_TALKER_WINDOW_MINUTES));
        boolean isActiveTalker = chatMessageRepository.existsBySenderIdAndChatRoomIdAndSentAtAfter(aiUser.getId(), roomId, fiveMinutesAgo);

        if (isActiveTalker) return ResponseType.FAST;
        if (isMainSpeaker) return ResponseType.FAST;

        return (secureRandom.nextInt(100) < 30) ? ResponseType.FAST : ResponseType.SLOW;
    }

    private void scheduleFastResponse(User aiUser, MessageCreatedEvent event, String userMessage, boolean isMainSpeaker, long delayMs) {
        Instant executionTime = Instant.now().plusMillis(delayMs);
        Long roomId = event.messageResponse().roomId();

        thinkingStateManager.markAsThinking(roomId, aiUser.getId());

        taskScheduler.schedule(() -> {
            try {
                aiChatUserService.processAiResponse(aiUser, event, userMessage, isMainSpeaker)
                        .doFinally(signalType -> thinkingStateManager.finishThinking(roomId, aiUser.getId()))
                        .subscribe(
                                ignored -> {
                                },
                                error -> log.error("Fast Response Error", error)
                        );
            } catch (Exception e) {
                log.error("Fast Response Error", e);
                thinkingStateManager.finishThinking(roomId, aiUser.getId());
            }
        }, executionTime);
    }

    private void scheduleLateReply(User aiUser, MessageCreatedEvent originalEvent, String originalUserMessage, Long roomId, long delayMinutes) {
        Instant executionTime = Instant.now().plus(Duration.ofMinutes(delayMinutes));
        log.info("🕒 AI [{}] scheduled LATE reply in {} minutes.", aiUser.getFirstName(), delayMinutes);
        taskScheduler.schedule(() -> {
            validateAndSendLateReply(aiUser, originalEvent, roomId);
        }, executionTime);
    }

    @Transactional
    public void validateAndSendLateReply(User aiUser, MessageCreatedEvent originalEvent, Long roomId) {
        try {
            Optional<ChatMessage> latestMsgOpt = chatMessageRepository.findTopByChatRoomIdOrderBySentAtDesc(roomId);
            if (latestMsgOpt.isEmpty()) return;
            ChatMessage latestMsg = latestMsgOpt.get();

            if (latestMsg.getId() > originalEvent.messageResponse().id()) {
                log.info("✋ AI [{}] Late reply ABORTED. Context changed.", aiUser.getFirstName());
                return;
            }
            aiChatUserService.processAiResponse(aiUser, originalEvent, latestMsg.getContent(), false)
                    .subscribe(
                            ignored -> {
                            },
                            error -> log.error("Late Response Error", error)
                    );
        } catch (Exception e) {
            log.error("Late Response Error", e);
        }
    }

    private long calculateBaseThinkingTime(String userMessage) {
        long baseReactionTime = secureRandom.nextLong(500, 1500);
        long readingTime = (userMessage != null ? userMessage.length() : 0) * 50L;
        long thinkingVariance = secureRandom.nextLong(0, 1000);

        return baseReactionTime + readingTime + thinkingVariance;
    }

    private boolean isMentioned(String message, String name) {
        if (message == null || name == null) return false;
        return message.contains(name);
    }

    private enum ResponseType { FAST, SLOW }
}
