package core.global.service;

import core.domain.user.entity.User;
import core.domain.user.repository.UserRepository;
import core.global.enums.errorcode.UserErrorCode;
import core.global.exception.BusinessException;
import core.global.translation.client.TranslationClient;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class TranslationService {

    private final UserRepository userRepository;
    private final TranslationClient translationClient;

    /**
     * [리스트 번역]
     * 외부 API 호출 중 에러가 발생하거나 응답 지연이 발생하면 서킷이 Open됩니다.
     */
    @CircuitBreaker(name = "translationApi", fallbackMethod = "fallbackTranslateMessages")
    public List<String> translateMessages(List<String> messages, String targetLanguage) {
        if (messages == null || messages.isEmpty() || targetLanguage == null || targetLanguage.isEmpty()) {
            return messages;
        }

        return translationClient.translate(messages, targetLanguage);
    }

    /**
     * [포스트 단일 번역]
     * 내부 호출 문제를 방지하기 위해 여기에도 서킷 브레이커를 명시합니다.
     */
    @CircuitBreaker(name = "translationApi", fallbackMethod = "fallbackTranslateSingle")
    public String translatePost(String post, String targetLanguage) {
        List<String> results = translateMessages(List.of(post), targetLanguage);
        return results.get(0);
    }

    /**
     * [댓글 리스트 번역]
     */
    @CircuitBreaker(name = "translationApi", fallbackMethod = "fallbackTranslateMessages")
    public List<String> translateComments(List<String> comments, String targetLanguage) {
        return translateMessages(comments, targetLanguage);
    }

    public String detectLanguage(String text) {
        return "en";
    }

    @Transactional
    public void saveUserLanguage(Authentication auth, String language) {
        User user = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        if (language != null && !language.isEmpty()) {
            user.updateTranslateLanguage(language);
        }
        userRepository.save(user);
    }

    // ==========================================
    // Fallback Methods (에러 발생 혹은 서킷 Open 시 실행)
    // ==========================================

    /**
     * 리스트 번역 실패 시: 원문 리스트 그대로 반환
     */
    public List<String> fallbackTranslateMessages(List<String> messages, String targetLanguage, Throwable t) {
        log.error("🚨 [Translation Service] 번역 리스트 호출 실패. 원문 반환. 사유: {}", t.getMessage());
        return messages;
    }

    /**
     * 단일 번역 실패 시: 원문 그대로 반환
     */
    public String fallbackTranslateSingle(String post, String targetLanguage, Throwable t) {
        log.error("🚨 [Translation Service] 단일 번역 호출 실패. 원문 반환. 사유: {}", t.getMessage());
        return post;
    }
}
