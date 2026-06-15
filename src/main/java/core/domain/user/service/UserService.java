package core.domain.user.service;


import core.domain.bookmark.repository.BookmarkRepository;
import core.domain.chat.entity.ChatParticipant;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.repository.ChatParticipantRepository;
import core.domain.chat.repository.ChatRoomRepository;
import core.domain.comment.repository.CommentRepository;
import core.domain.notification.dto.NewUserJoinedEvent;
import core.domain.post.entity.Post;
import core.domain.post.repository.PostRepository;
import core.domain.user.dto.*;
import core.domain.user.entity.Follow;
import core.domain.user.entity.User;
import core.domain.user.repository.BlockRepository;
import core.domain.user.repository.FollowRepository;
import core.domain.user.repository.UserRepository;
import core.global.apple.dto.AppleLoginByCodeRequest;
import core.global.dto.*;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageService;
import core.global.entity.like.repository.LikeRepository;
import core.global.enums.*;
import core.global.enums.Oauthplatform;
import core.global.enums.chat.ChatParticipantStatus;
import core.global.enums.common.ImageType;
import core.global.enums.common.LikeType;
import core.global.enums.errorcode.AuthErrorCode;
import core.global.enums.errorcode.UserErrorCode;
import core.global.enums.user.FollowStatus;
import core.global.enums.user.Role;
import core.global.exception.BusinessException;
import core.global.pagination.CursorCodec;
import core.global.pagination.CursorPageResponse;
import core.global.pagination.CursorPages;
import core.global.redis.service.RedisService;
import core.global.security.JwtTokenProvider;
import core.global.service.SmtpMailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private static final String EMAIL_VERIFY_CODE_KEY = "email_verification:code:";
    private static final String EMAIL_VERIFIED_FLAG_KEY = "email_verification:verified:";
    private static final String EMAIL_VERIFY_ATTEMPT_KEY = "auth:verify-attempt:";
    private static final long CODE_TTL_MIN = 3L;
    private static final long VERIFIED_TTL_MIN = 10L;

    /**
     * 8~12자, 특수문자(@/!/~) 1+ 포함, 허용문자 제한
     */
    private static final Pattern PW_RULE = Pattern.compile(
            "^(?=.*[@/!/~])[A-Za-z0-9@/!/~]{8,12}$"
    );
    private final ApplicationEventPublisher eventPublisher;
    private final PasswordEncoder passwordEncoder;
    private final BlockRepository blockRepository;
    private final SmtpMailService smtpService;
    private final RedisTemplate<String, String> redisTemplate;
    private final UserRepository userRepository;
    private final ImageService imageService;
    private final RedisService redisService;
    private final JwtTokenProvider jwtTokenProvider;
    private final CommentRepository commentRepository;
    private final BookmarkRepository bookmarkRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final PostRepository postRepository;
    private final ImageRepository imageRepository;
    private final FollowRepository followRepository;
    private final LikeRepository likeRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ApplicationEventPublisher publisher;

    Pattern pattern = Pattern.compile("\\[(.*?)\\]");

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public void logout(String accessToken) {
        long expiration = jwtTokenProvider.getExpiration(accessToken).getTime() - System.currentTimeMillis();
        redisService.blacklistAccessToken(accessToken, expiration);
        Long userId = jwtTokenProvider.getUserIdFromAccessToken(accessToken);
        redisService.deleteRefreshToken(userId);

        log.info("사용자 {} 로그아웃 처리 완료 (Service).", userId);
    }
    @Transactional
    public TokenRefreshResponse refreshTokens(String refreshToken) {
        log.info("==================================================");
        log.info(">>> [토큰 재발급 요청 진입]");

        // 0. 로그 및 예외 처리를 위한 임시 변수 선언
        Long tempUserId = null;
        Date tempExpiration = null;

        // ------------------------------------------------------------------
        // [Pre-Parsing] 검증 전에 정보를 먼저 추출 (로그 및 에러 핸들링 목적)
        // ------------------------------------------------------------------
        try {
            // 토큰에서 userId 추출 시도
            tempUserId = jwtTokenProvider.getUserIdFromRefreshToken(refreshToken);
            tempExpiration = jwtTokenProvider.getExpiration(refreshToken);
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            // 만료된 토큰이어도 Claims 정보는 가져올 수 있음
            log.warn(">>> [1차 파싱 경고] 이미 만료된 토큰입니다. 정보를 강제 추출합니다.");
            try {
                // ExpiredJwtException에서 직접 Claims 꺼내기
                tempUserId = Long.parseLong(e.getClaims().getSubject());
                tempExpiration = e.getClaims().getExpiration();
            } catch (Exception ex) {
                log.error(">>> [1차 파싱 실패] 만료된 토큰 정보 추출 중 에러: {}", ex.getMessage());
            }
        } catch (Exception e) {
            log.error(">>> [1차 파싱 실패] 토큰 형식이 완전히 잘못되었습니다: {}", e.getMessage());
        }

        // ------------------------------------------------------------------
        // [상세 로그 출력]
        // ------------------------------------------------------------------
        long remainingTime = 0;
        if (tempExpiration != null) {
            remainingTime = tempExpiration.getTime() - System.currentTimeMillis();
        }

        String tokenFragment = (refreshToken != null && refreshToken.length() > 10)
                ? refreshToken.substring(Math.max(0, refreshToken.length() - 15))
                : refreshToken;

        log.info(">>> [요청 상세 정보]");
        log.info("    -> User ID   : {}", tempUserId);
        log.info("    -> Token(끝) : ...{}", tokenFragment);
        log.info("    -> Expiration: {} (남은시간: {}ms)", tempExpiration, remainingTime);

        // ------------------------------------------------------------------
        // 1. 진짜 토큰 유효성 검사 (수정된 부분: 예외 처리 강화)
        // ------------------------------------------------------------------
        try {
            // validateToken은 만료 시 ExpiredJwtException을 던질 수 있음 -> catch로 잡아야 함
            if (!jwtTokenProvider.validateToken(refreshToken)) {
                // 서명이 틀리거나 형식이 잘못된 경우 (false 리턴 시)
                log.warn("<<< [재발급 실패] 유효하지 않은 토큰(서명 불일치 등) - UserID: {}", tempUserId);
                throw new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN);
            }
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            // ★ [핵심 수정] 토큰 만료 에러를 잡아서 비즈니스 예외로 변환
            log.warn("<<< [재발급 실패] 리프레시 토큰 만료됨 (재로그인 필요) - UserID: {}", tempUserId);
            throw new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        } catch (Exception e) {
            // 그 외 알 수 없는 토큰 오류
            log.warn("<<< [재발급 실패] 토큰 검증 중 에러 발생: {}", e.getMessage());
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }

        // 2. 사용자 조회 (파싱 실패로 ID가 없으면 에러)
        if (tempUserId == null) {
            throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
        }

        User user = userRepository.getUserById(tempUserId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        log.info("    -> 사용자 정보: Email={}, Name={}, Role={}", user.getEmail(), user.getFirstName(), user.getUserRole());

        // 3. Redis 검증 (Refresh Token Rotation 및 탈취 감지)
        String storedRefreshToken = redisService.getRefreshToken(tempUserId);

        if (storedRefreshToken == null) {
            log.warn("<<< [재발급 실패] Redis에 토큰 없음 (로그아웃/만료됨). ID: {}", tempUserId);
            throw new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        if (!storedRefreshToken.equals(refreshToken)) {
            // 들어온 토큰과 저장된 토큰이 다르면 탈취 가능성 있음 -> 저장된 것 삭제
            log.warn("<<< [재발급 실패] Redis 토큰 불일치 (토큰 탈취 의심). ID: {}", tempUserId);
            log.warn("    -> 요청: ...{}", tokenFragment);
            String storedFragment = (storedRefreshToken.length() > 10) ? storedRefreshToken.substring(storedRefreshToken.length() - 15) : storedRefreshToken;
            log.warn("    -> 저장: ...{}", storedFragment);

            redisService.deleteRefreshToken(tempUserId);
            throw new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }

        // 4. 기존 토큰 삭제 및 새 토큰 발급 (Rotation)
        redisService.deleteRefreshToken(tempUserId);

        String newAccessToken = jwtTokenProvider.createAccessToken(tempUserId, user.getUserRole().toString(), user.getEmail());
        String newRefreshToken = jwtTokenProvider.createRefreshToken(tempUserId);

        Date newExpirationDate = jwtTokenProvider.getExpiration(newRefreshToken);
        long newExpirationMillis = newExpirationDate.getTime() - System.currentTimeMillis();

        // 새 리프레시 토큰 Redis 저장
        redisService.saveRefreshToken(tempUserId, newRefreshToken, newExpirationMillis);

        log.info("<<< [토큰 재발급 성공] User: {}, Exp: {}ms", user.getEmail(), newExpirationMillis);
        log.info("==================================================");

        return new TokenRefreshResponse(newAccessToken, newRefreshToken, tempUserId);
    }
    public User create(UserCreateDto memberCreateDto) {
        User user = User.builder()
                .email(memberCreateDto.getEmail())
                .build();
        userRepository.save(user);
        return user;
    }

    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    @Transactional
    public User createOauth(String socialId, String email, String provider) {
        User u = User.builder()
                .socialId(socialId)
                .email(email)
                .provider(provider)
                .createdAt(Instant.now())
                .build();
        return userRepository.save(u);
    }

    @Transactional
    public User createAppleOauth(String socialId, String email, String provider, String appleRefreshToken
            , AppleLoginByCodeRequest.FullNameDto name) {
        User u = User.builder()
                .socialId(socialId)
                .email(email)
                .provider(provider)
                .appleRefreshToken(appleRefreshToken)
                .firstName(name.familyName())
                .lastName(name.givenName())
                .createdAt(Instant.now())
                .build();

        return userRepository.save(u);
    }

    /**
     * 사용자 정보(이름)를 업데이트합니다.
     *
     * @param user     업데이트할 User 엔티티
     * @param fullName Apple 로그인 시 전달받은 이름 정보 DTO
     */
    @Transactional
    public void updateUser(User user, AppleLoginByCodeRequest.FullNameDto fullName) {
        if (user == null) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }
        if (fullName != null) {
            boolean isUpdated = false;
            if (fullName.givenName() != null && !fullName.givenName().isBlank()) {
                user.updateFirstName(fullName.givenName());
                isUpdated = true;
            }
            if (fullName.familyName() != null && !fullName.familyName().isBlank()) {
                user.updateLastName(fullName.familyName());
                isUpdated = true;
            }
            if (isUpdated) {
                userRepository.save(user);
            }
        }
    }

    public User getUserBySocialIdAndProvider(String socialId, String provider) {
        return userRepository.findByProviderAndSocialId(provider.trim(), socialId.trim()).orElse(null);
    }

    @Transactional
    public void setupUserProfile(UserSetupRequest dto) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();
        log.info("UserSetupRequest dto: {}", dto);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (!user.isNewUser()) {
            throw new BusinessException(UserErrorCode.INVALID_PROFILE,
                    "이미 프로필이 설정된 사용자입니다.");
        }

        if (!Objects.equals(user.getProvider(), Oauthplatform.APPLE.toString())) {

            if (notBlank(dto.firstname())) {
                user.updateFirstName(dto.firstname().trim());
            }
            if (notBlank(dto.lastname())) {
                user.updateLastName(dto.lastname().trim());
            }
        }

        user.updateSex(dto.gender());
        user.updateBirthdate(dto.birthday());
        user.updateCountry(dto.country());
        user.updatePurpose(dto.purpose());
        String v = dto.introduction();
        user.updateIntroduction(v.length() > 70 ? v.substring(0, 70) : v);

// UserSetupRequest dto를 받는 메서드 내부
        if (dto.language() != null && !dto.language().isEmpty()) {

            // 1. 초기 정제: null, 공백 제거 및 trim만 수행. (대소문자/포맷은 유지)
            List<String> rawLanguages = dto.language().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();

            if (!rawLanguages.isEmpty()) {

                // 2. 번역 언어 (translate_language) 추출 및 저장 (무조건 소문자)
                String firstTranslatedLanguage = rawLanguages.stream()
                        .findFirst() // 첫 번째 언어를 선택
                        .map(s -> normalizeLanguageCode(s).toLowerCase()) // 코드를 추출하고 소문자화
                        .orElse("");

                if (!firstTranslatedLanguage.isEmpty()) {
                    user.updateTranslateLanguage(firstTranslatedLanguage);
                }

                // 3. 언어 목록 (languages CSV) 추출 및 저장 (무조건 대문자)
                List<String> normalizedLanguagesForCsv = rawLanguages.stream()
                        .map(s -> normalizeLanguageCode(s).toUpperCase()) // 코드를 추출하고 대문자화
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();

                if (!normalizedLanguagesForCsv.isEmpty()) {
                    String userLanguagesCsv = String.join(",", normalizedLanguagesForCsv);
                    user.updateLanguage(userLanguagesCsv);
                }
            }
        }

        if (dto.hobby() != null && !dto.hobby().isEmpty()) {
            String csv = String.join(",", dto.hobby());
            user.updateHobby(csv);
        }

        user.updateIsNewUser(false);
        if (dto.imageKey() != null) {
            imageService.saveUserProfileImage(user.getId(), dto.imageKey());
        }
    }



    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getUserProfile() {
        var auth = SecurityContextHolder.getContext().getAuthentication();

        String email = (auth instanceof JwtAuthenticationToken jwtAuth)
                ? jwtAuth.getToken().getClaim("templates/email")
                : auth.getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        String profileKey = imageService.getUserProfileKey(user.getId());

        return new UserProfileResponse(user, stringToList(user.getTranslateLanguage()), stringToList(user.getHobby()), profileKey);
    }

    private String normalizeLanguageCode(String rawLang) {
        if (rawLang == null) return "";

        String normalized = rawLang.toLowerCase().trim();

        // 1. 정규식 패턴 기반 추출 (예: 'abkhaz [ab]' -> 'ab')
        Matcher matcher = pattern.matcher(normalized);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 2. 대시(-) 처리 (예: 'fr-fr' -> 'fr')
        if (normalized.contains("-")) {
            return normalized.split("-")[0].trim();
        }

        // 3. 단순 코드인 경우 (예: 'ko', 'en')
        // 코드 길이가 2~5자인 경우 (예: zh-CN)는 그대로 반환
        if (normalized.length() >= 2 && normalized.length() <= 5) {
            return normalized;
        }

        return ""; // 그 외 알 수 없는 포맷은 무시
    }

    @Transactional
    public void deleteProfileImage() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        String email = (auth instanceof JwtAuthenticationToken jwtAuth)
                ? jwtAuth.getToken().getClaim("templates/email")
                : auth.getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        imageService.deleteUserProfileImage(user.getId());
    }

    @Transactional
    public LoginResponseDto signup(SignupRequest req) {
        if (!req.isAgreedToTerms()) {
            throw new BusinessException(UserErrorCode.AGREEMENT_INPUT);
        }

        String email = normalizeEmail(req.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.DUPLICATE_RESOURCE);
        }

        String verified = redisTemplate.opsForValue().get(EMAIL_VERIFIED_FLAG_KEY + email);
        if (!"1".equals(verified)) {
            throw new BusinessException(UserErrorCode.AUTHENTICATION_FAILED);
        }

        String rawPw = req.getPassword();

        User u = new User();
        u.updateProvider(Oauthplatform.local.toString());
        u.updateSocialId(buildLocalSocialId(email));
        u.updateEmail(email);
        u.updatePassword(passwordEncoder.encode(rawPw));
        u.updateIsNewUser(true);
        u.updateAgreedToTerms(req.isAgreedToTerms());
        u.updateAgreedToPushNotification(false);

        Instant now = Instant.now();
        u.updateCreatedAt(now);
        u.updateUpdatedAt(now);
        u.changeUserRole(Role.VISITOR);

        userRepository.save(u);

        redisTemplate.delete(EMAIL_VERIFIED_FLAG_KEY + email);

        String accessToken = jwtTokenProvider.createAccessToken(u.getId(), u.getUserRole().toString(), u.getEmail());
        String refreshToken = jwtTokenProvider.createRefreshToken(u.getId());

        Date expirationDate = jwtTokenProvider.getExpiration(refreshToken);
        long expirationMillis = expirationDate.getTime() - System.currentTimeMillis();
        redisService.saveRefreshToken(u.getId(), refreshToken, expirationMillis);

        publisher.publishEvent(new UserLoggedInEvent(u.getId().toString(), "local"));
        return new LoginResponseDto(u.getId(), accessToken, refreshToken, u.isNewUser());
    }


    private String normalizeEmail(String email) {
        if (email == null) return null;
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String buildLocalSocialId(String email) {
        return "local:" + sha256Hex(email);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 일반 회원 가입 로직
     */
    @Transactional(readOnly = true)
    public AuthResponse login(EmailLoginDto req) {
        String email = normalizeEmail(req.getEmail());

        User u = userRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("[LOGIN] 사용자 없음: email={}", email);
                    return new BusinessException(UserErrorCode.USER_NOT_FOUND);
                });

        log.debug("[LOGIN] 사용자 조회 성공: id={}, provider={}", u.getId(), u.getProvider());

        if (!Oauthplatform.local.toString().equalsIgnoreCase(nullToEmpty(u.getProvider()))) {
            log.warn("[LOGIN] provider 불일치: provider={}", u.getProvider());
            throw new BusinessException(UserErrorCode.AUTHENTICATION_FAILED);
        }

        if (u.getPassword() == null || !passwordEncoder.matches(req.getPassword(), u.getPassword())) {
            log.warn("[LOGIN] 비밀번호 불일치: email={}", email);
            throw new BusinessException(UserErrorCode.AUTHENTICATION_FAILED);
        }

        String access = jwtTokenProvider.createAccessToken(u.getId(), u.getUserRole().name(), u.getEmail());
        String refresh = jwtTokenProvider.createRefreshToken(u.getId());
        long expiresInMs = jwtTokenProvider.getExpiration(access).getTime() - System.currentTimeMillis();
        Date refreshExpiration = jwtTokenProvider.getExpiration(refresh);
        long refreshExpirationMillis = refreshExpiration.getTime() - System.currentTimeMillis();
        redisService.saveRefreshToken(u.getId(), refresh, refreshExpirationMillis);
        log.info("[LOGIN] 로그인 성공: id={}, email={}, expiresInMs={}", u.getId(), u.getEmail(), expiresInMs);

        publisher.publishEvent(new UserLoggedInEvent(u.getId().toString(), "email"));

        return new AuthResponse("Bearer", access, refresh, expiresInMs, u.getId(), u.getEmail(), u.isNewUser());
    }

    /**
     * 이메일 보내주는 로직
     */
    public void sendEmailVerificationCode(String rawEmail, Locale locale) {
        String email = normalizeEmail(rawEmail);

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.DUPLICATE_RESOURCE);
        }

        Duration ttl = Duration.ofMinutes(CODE_TTL_MIN);

        String verificationCode = smtpService.sendVerificationEmail(
                email,
                ttl,
                locale
        );
        String redisKey = EMAIL_VERIFY_CODE_KEY + email;
        log.info(">>> [Redis Save] Key: [{}], Code: [{}], TTL: {} min", redisKey, verificationCode, CODE_TTL_MIN);
        redisTemplate.opsForValue().set(
                EMAIL_VERIFY_CODE_KEY + email,
                verificationCode,
                CODE_TTL_MIN,
                TimeUnit.MINUTES
        );
    }


    /**
     * 이메일 인증 코드 검증 (5회 이상 실패 시 재발급 필요)
     */
    public boolean verifyEmailCode(EmailVerificationRequest request) {
        String email = normalizeEmail(request.getEmail());
        String verificationCode = request.getVerificationCode();

        String codeKey = EMAIL_VERIFY_CODE_KEY + email;
        String attemptKey = EMAIL_VERIFY_ATTEMPT_KEY + email;

        // Redis에서 코드 조회
        String storedCode = redisTemplate.opsForValue().get(codeKey);

        if (storedCode == null) {
            throw new BusinessException(AuthErrorCode.VERIFY_CODE_EXPIRES);
        }

        // 틀린 경우 처리
        if (!storedCode.equals(verificationCode)) {

            // 실패 횟수 증가
            Long attempt = redisTemplate.opsForValue().increment(attemptKey);

            // 실패 카운트 TTL 설정(없으면 기본 10분, 코드 TTL과 같게)
            redisTemplate.expire(attemptKey, VERIFIED_TTL_MIN, TimeUnit.MINUTES);

            // 5회 이상이면 재발급 필요
            if (attempt != null && attempt >= 5) {
                // 인증 코드 삭제
                redisTemplate.delete(codeKey);
                redisTemplate.delete(attemptKey);

                throw new BusinessException(AuthErrorCode.VERIFY_CODE_NEED_RESEND);
            }

            // 5회 미만이면 일반적인 "코드 불일치"
            throw new BusinessException(AuthErrorCode.VERIFY_CODE_NOT_MATCH);
        }

        // ★ 성공한 경우: 코드 및 시도 횟수 삭제
        redisTemplate.delete(codeKey);
        redisTemplate.delete(attemptKey);

        // 인증 완료 플래그 저장
        String flagKey = EMAIL_VERIFIED_FLAG_KEY + email;
        redisTemplate.opsForValue().set(
                flagKey,
                "1",
                VERIFIED_TTL_MIN,
                TimeUnit.MINUTES
        );

        log.info(">>> [Redis Save Flag] 인증 완료 도장 저장 성공! Key: [{}], TTL: {} min", flagKey, VERIFIED_TTL_MIN);

        return true;
    }

    /**
     * 쉼표로 구분된 문자열을 List<String>으로 변환
     */
    private List<String> stringToList(String str) {
        if (str == null || str.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @Transactional
    public ProfileEditResponseDto updateUserProfile(UserProfileEditDto dto) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Role oldRole = user.getUserRole();

        if (notBlank(dto.firstname())) user.updateFirstName(dto.firstname().trim());
        if (notBlank(dto.lastname())) user.updateLastName(dto.lastname().trim());
        if (dto.gender() != null) user.updateGender(dto.gender());
        if (dto.birthday() != null) user.updateBirthdate(dto.birthday());
        if (notBlank(dto.country())) user.updateCountry(dto.country().trim());

        if (notBlank(dto.introduction())) {
            String v = dto.introduction().trim();
            user.updateIntroduction(v.length() > 70 ? v.substring(0, 70) : v);
        }
        if (notBlank(dto.purpose())) {
            user.updatePurpose(dto.purpose());
        }

// UserLanguageDTO dto를 받는 메서드 내부 (updateUserLanguage 로직)

        if (dto.language() != null && !dto.language().isEmpty()) {

            // 1. 초기 정제: null, 공백 제거 및 trim만 수행. (대소문자/포맷은 유지)
            List<String> rawLanguages = dto.language().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();

            if (!rawLanguages.isEmpty()) {

                // 2. 번역 언어 (translate_language) 추출 및 저장 (무조건 소문자)
                String firstTranslatedLanguage = rawLanguages.stream()
                        .findFirst() // 첫 번째 언어를 선택
                        .map(s -> normalizeLanguageCode(s).toLowerCase()) // 코드를 추출하고 소문자화
                        .orElse("");

                if (!firstTranslatedLanguage.isEmpty()) {
                    user.updateTranslateLanguage(firstTranslatedLanguage);
                }

                // 3. 언어 목록 (languages CSV) 추출 및 저장 (무조건 대문자)
                List<String> normalizedLanguagesForCsv = rawLanguages.stream()
                        .map(s -> normalizeLanguageCode(s).toUpperCase()) // 코드를 추출하고 대문자화
                        .filter(s -> !s.isEmpty())
                        .distinct()
                        .toList();

                if (!normalizedLanguagesForCsv.isEmpty()) {
                    String userLanguagesCsv = String.join(",", normalizedLanguagesForCsv);
                    user.updateLanguage(userLanguagesCsv);
                }
            }
        }

        if (dto.hobby() != null) {
            String csv = dto.hobby().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.joining(","));
            if (!csv.isEmpty()) user.updateHobby(csv);
        }

        String finalImageKey = imageService.getUserProfileKey(user.getId());
        if (notBlank(dto.imageKey())) {
            finalImageKey = imageService.updateUserProfileImage(user.getId(), dto.imageKey());
        }

        Role newRole = user.getUserRole();

        ProfileEditResponseDto responseDto = new ProfileEditResponseDto(
                user,
                stringToList(user.getLanguage()),
                stringToList(user.getHobby()),
                finalImageKey
        );

        if (oldRole == Role.VISITOR && newRole == Role.USER) {

            String accessToken = jwtTokenProvider.createAccessToken(
                    user.getId(),
                    user.getUserRole().name(),
                    user.getEmail()
            );

            redisService.deleteRefreshToken(user.getId());
            String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());
            long ttlMs = jwtTokenProvider.getExpiration(refreshToken).getTime() - System.currentTimeMillis();
            redisService.saveRefreshToken(user.getId(), refreshToken, ttlMs);
            responseDto.setNewTokens(accessToken, refreshToken);
        }
        return responseDto;
    }

    @Transactional
    public void updateSkipUserSetup(UserUpdateDto dto) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String email = auth.getName();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (dto.birthday() != null) user.updateBirthdate(dto.birthday());
        if (notBlank(dto.country())) user.updateCountry(dto.country().trim());

        if (notBlank(dto.introduction())) {
            String v = dto.introduction().trim();
            user.updateIntroduction(v.length() > 70 ? v.substring(0, 70) : v); // 컬럼 길이 보호
        }
        if (notBlank(dto.purpose())) {
            user.updatePurpose(dto.purpose());
        }

        if (dto.language() != null && !dto.language().isEmpty()) {
            List<String> languages = dto.language().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();

            if (!languages.isEmpty()) {
                // CSV 형태로 저장
                String userLanguagesCsv = String.join(",", languages);
                user.updateLanguage(userLanguagesCsv);

                // 첫 번째 요소에서 번역 코드 추출
                String firstTranslatedLanguage = languages.stream()
                        .map(s -> {
                            Matcher matcher = pattern.matcher(s);
                            return matcher.find() ? matcher.group(1).trim() : "";
                        })
                        .filter(s -> !s.isEmpty())
                        .findFirst()
                        .orElse("");

                if (!firstTranslatedLanguage.isEmpty()) {
                    user.updateTranslateLanguage(firstTranslatedLanguage);
                }
            }
        }

        if (dto.hobby() != null) {
            String csv = dto.hobby().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .collect(Collectors.joining(","));
            if (!csv.isEmpty()) user.updateHobby(csv);
        }

        if (notBlank(dto.imageKey())) {
            imageService.updateUserProfileImage(user.getId(), dto.imageKey());
        }
        NewUserJoinedEvent event = new NewUserJoinedEvent(user.getId());
        eventPublisher.publishEvent(event);
    }


    public UserProfileCardResponse findCardUserProfile(Long userId, Long currentUserId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        String profileKey = imageService.getUserProfileKey(user.getId());

        Optional<Follow> myFollow = followRepository.findByUser_IdAndFollowing_Id(currentUserId, userId);
        // theirFollow: 상대가 나를 어떻게 하고 있는지
        Optional<Follow> theirFollow = followRepository.findByUser_IdAndFollowing_Id(userId, currentUserId);

        // 4. 상태 추출 (데이터가 없거나 REJECTED면 null 취급과 비슷하게 처리하기 위해 변수화)
        FollowStatus myStatus = myFollow.map(Follow::getStatus).orElse(null);
        FollowStatus theirStatus = theirFollow.map(Follow::getStatus).orElse(null);

        FriendType relationshipLabel = calculateRelationship(myStatus, theirStatus);

        return new UserProfileCardResponse(
                user,
                stringToList(user.getTranslateLanguage()),
                stringToList(user.getHobby()),
                profileKey,
                relationshipLabel
        );
    }

    private FriendType calculateRelationship(FollowStatus myStatus, FollowStatus theirStatus) {
        // 1. 서로 수락된 상태 -> 친구 (맞팔)
        if (myStatus == FollowStatus.ACCEPTED || theirStatus == FollowStatus.ACCEPTED) {
            return FriendType.FRIEND;
        }

        // 2. 내가 보낸 요청이 대기 중 -> 요청 보냄 (버튼: '요청 취소' 등)
        if (myStatus == FollowStatus.PENDING) {
            return FriendType.FOLLOWING;
        }

        // 4. 상대가 나를 팔로우 중 (나는 안 함/거절/요청전) -> 나를 팔로우 함 (버튼: '맞팔하기')
        if (theirStatus == FollowStatus.PENDING) {
            return FriendType.FOLLOWED;
        }

        // 그 외 (둘 다 없거나, REJECTED 등)
        return FriendType.NONE;
    }

    /**
     * 여러 사용자 정보 일괄 조회 로직 (N+1 문제 해결)
     */
    public List<UserResponseDto> findUsersProfiles(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<User> users = userRepository.findAllById(userIds);
        List<Long> foundUserIds = users.stream().map(User::getId).toList();
        Map<Long, String> imageUrlsMap = imageRepository
                .findAllPrimaryImagesForUsers(ImageType.USER, foundUserIds)
                .stream()
                .collect(Collectors.toMap(Image::getRelatedId, Image::getUrl, (first, second) -> first));
        return users.stream()
                .map(user -> {
                    String imageUrl = imageUrlsMap.get(user.getId());
                    return UserResponseDto.from(user, imageUrl);
                })
                .collect(Collectors.toList());
    }


    /**
     * 사용자의 애플 계정 상태를 확인하는 메서드
     *
     * @param userId 확인할 사용자의 ID
     * @return UserAppleStatusResponse 사용자의 애플 계정 상태 정보
     */
    @Transactional(readOnly = true)
    public UserAppleStatusResponse checkUserAppleStatus(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        boolean isApple = Oauthplatform.APPLE.toString().equals(user.getProvider());

        boolean isRejoiningWithoutFullName = false;
        if (isApple) {
            isRejoiningWithoutFullName = (user.getFirstName() == null || user.getFirstName().isBlank());
        }

        return new UserAppleStatusResponse(isApple, isRejoiningWithoutFullName);
    }


    @Transactional
    public void updateUserCountry(Long userId, String country) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        user.updateCountry(country);
    }

    @Transactional
    public void updateUserResidence(Long userId, String residence) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        user.updateResidence(residence);
    }

    /**
     * 유저 프로필 완료 여부 확인
     *
     * @param userId 확인할 유저 ID
     * @return 프로필이 완료되었으면 true, 아니면 false
     */
    public ProfileCompletionResponse checkProfileCompletion(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        boolean completed = user.getBirthdate() != null
                            && user.getPurpose() != null
                            && user.getIntroduction() != null
                            && user.getLanguage() != null
                            && user.getHobby() != null
                            && user.getSex() != null;
        return new ProfileCompletionResponse(userId, completed);
    }
    private static final List<String> INTRODUCTIONS = List.of(
                "I want to make Korean friends! 👋",          // 한국 친구를 사귀고 싶어요!
                "I really love K-POP 🎵",                     // K-POP을 정말 좋아해요
                "I want to study Korean together 📚",         // 한국어 공부를 같이 하고 싶어요
                "I'm planning a trip to Korea ✈️",            // 한국 여행을 계획 중이에요
                "I want to share daily stories 💬",           // 일상 이야기를 나누고 싶어요
                "Let's share good restaurant info 🥘",        // 맛집 정보를 공유해요
                "I love BTS the most 💜",                     // BTS를 가장 좋아해요
                "Please recommend Korean dramas 📺",          // 한국 드라마 추천해주세요
                "Let's do language exchange 🇰🇷",              // 서로의 언어를 교환해요
                "Feel free to contact me! 😄",                // 편하게 연락주세요!
                "I'm interested in fashion & beauty 💄",      // 패션과 뷰티에 관심이 많아요
                "I want to learn Korean culture 🎎",          // 한국 문화를 배우고 싶어요
                "I want to have deep conversations ☕",       // 진지한 대화를 나누고 싶어요
                "I like exercising and taking walks 🏃",      // 운동과 산책을 좋아해요
                "I have a cat 🐱",                            // 고양이 집사입니다
                "I like going to cafes on weekends ☕",       // 주말에 카페 가는 걸 좋아해요
                "Let's talk about Netflix 🎬",                // 넷플릭스 같이 이야기해요
                "Taking photos is my hobby 📸",               // 사진 찍는 게 취미예요
                "I love delicious desserts 🍰",               // 맛있는 디저트를 좋아해요
                "I want to share positive energy ✨"           // 긍정적인 에너지를 나누고 싶어요
        );


    /**
     * 관심사 카테고리 및 아이템 (K-POP, K-DRAMA&MOVIE, LIFESTYLE)
     */
    private static final List<ProfileOptionsDto.CategoryItem> INTEREST_CATEGORIES = List.of(
            new ProfileOptionsDto.CategoryItem("K-POP", List.of(
                    "BTS", "BLACKPINK", "NewJeans", "SEVENTEEN", "Stray Kids",
                    "IVE", "NCT", "TWICE", "LE SSERAFIM", "aespa", "EXO"
            )),
            new ProfileOptionsDto.CategoryItem("K-DRAMA&MOVIE", List.of(
                    "Squid Game", "The Glory", "Parasite", "Moving", "Kingdom",
                    "Crash Landing on You", "All of Us Are Dead", "Reply 1988",
                    "Sweet Home", "Itaewon Class"
            )),
            new ProfileOptionsDto.CategoryItem("LIFESTYLE", List.of(
                    "Travel", "Food", "Fashion", "Beauty", "Language Exchange",
                    "Daily Life", "Cafe", "MBTI", "Exercise", "Music", "Drawing"
            ))
    );
    public ProfileOptionsDto.CombinedResponse getProfileOptions() {
        return ProfileOptionsDto.CombinedResponse.builder()
                .introductions(INTRODUCTIONS)
                .interests(new ProfileOptionsDto.InterestResponse(INTEREST_CATEGORIES))
                .build();
    }

    @Transactional(readOnly = true)
    public CursorPageResponse<UserProfileGroupChatRoomResponse> getUserGroupChatRooms(Long userId, String cursor, int size) {

        // 1. 유저 존재 여부 검증
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }

        // 2. 커서 해독 및 사이즈 설정
        final int pageSize = Math.min(Math.max(size, 1), 50);
        Map<String, Object> c = safeDecode(cursor);
        Long cursorId = c.containsKey("id") ? ((Number) c.get("id")).longValue() : null;

        // 3. 커서 기반 DB 조회 (JPQL 호출)
        // 🔥 offset은 0으로 고정하고, size만 pageSize + 1로 설정하여 Limit 역할만 수행하게 합니다.
        Pageable limitPageable = PageRequest.of(0, pageSize + 1);

        List<ChatParticipant> participants = chatParticipantRepository.findActiveGroupChatsByUserIdCursor(
                userId, ChatParticipantStatus.ACTIVE, cursorId, limitPageable
        );

        if (participants.isEmpty()) {
            return new CursorPageResponse<>(List.of(), false, null);
        }

        // 4. 채팅방 ID 목록 추출 및 이미지 Map 변환
        List<Long> chatRoomIds = participants.stream()
                .map(p -> p.getChatRoom().getId())
                .toList();

        Map<Long, String> imageMap = imageRepository.findAllByRelatedIdsAndType(chatRoomIds, ImageType.CHAT_ROOM)
                .stream()
                .collect(Collectors.toMap(Image::getRelatedId, Image::getUrl, (a, b) -> a));

        // 5. 다음 페이지 존재 여부 판별 및 리스트 자르기
        boolean hasNext = participants.size() > pageSize;
        List<ChatParticipant> actualList = hasNext ? participants.subList(0, pageSize) : participants;

        // 6. DTO 변환 작업
        List<UserProfileGroupChatRoomResponse> responseList = new ArrayList<>();

        for (ChatParticipant participant : actualList) {
            ChatRoom room = participant.getChatRoom();

            // 참여 인원 수 계산
            long activeCount = room.getParticipants().stream()
                    .filter(m -> m.getStatus() == ChatParticipantStatus.ACTIVE)
                    .count();

            responseList.add(UserProfileGroupChatRoomResponse.builder()
                    .roomId(room.getId())
                    .roomName(room.getRoomName())
                    .description(room.getDescription())
                    .roomImageUrl(imageMap.get(room.getId()))
                    .userCount(String.valueOf(activeCount))
                    .participantId(participant.getId())
                    .build());
        }

        // 7. 커서 페이징 응답 객체 반환
        return CursorPages.ofLatest(
                responseList,
                pageSize,
                dto -> null,
                UserProfileGroupChatRoomResponse::getParticipantId
        );
    }
    private Map<String, Object> safeDecode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Map.of();
        }

        try {
            return CursorCodec.decode(cursor);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }
    /**
     * 유저의 접속 상태 확인 (5분 이내 활동 시 Online)
     */
    public UserOnlineStatusResponse checkUserOnlineStatus(Long userId) {
        // 1. 유저 조회 (없으면 예외 발생)
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 2. 마지막 접속 시간 가져오기
        Instant lastSeenAt = user.getLastSeenAt();
        boolean isOnline = false;

        // 3. 접속 여부 판단 로직
        if (lastSeenAt != null) {
            // 현재 시간에서 5분을 뺀 시간
            Instant fiveMinutesAgo = Instant.now().minus(5, ChronoUnit.MINUTES);

            // 마지막 활동 시간이 5분 전보다 '이후'라면 접속 중으로 판단
            if (lastSeenAt.isAfter(fiveMinutesAgo)) {
                isOnline = true;
            }
        }

        // 4. 결과 반환
        return UserOnlineStatusResponse.builder()
                .isOnline(isOnline)
                .lastSeenAt(lastSeenAt)
                .build();
    }
    /**
     * 특정 유저의 게시글 목록 조회 (무한 스크롤)
     * 변경사항: UserProfilePostResponse의 필드명 변경에 따른 Builder 수정
     */
    public Slice<UserProfilePostResponse> getUserPosts(Long userId, Pageable pageable) {

        // 1. 유저 검증
        if (!userRepository.existsById(userId)) {
            throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        }

        // 2. 게시글 목록 조회 (Slice)
        Slice<Post> postSlice = postRepository.findAllByAuthorId(userId, pageable);

        if (postSlice.isEmpty()) {
            return new SliceImpl<>(Collections.emptyList(), pageable, false);
        }

        // 3. 게시글 ID 목록 추출
        List<Long> postIds = postSlice.getContent().stream()
                .map(Post::getId)
                .toList();

        // 4. [Bulk Fetch] 썸네일 이미지 조회 (Index = 0)
        List<Image> images = imageRepository.findAllByRelatedIdInAndImageTypeAndOrderIndex(
                postIds, ImageType.POST, 0
        );
        Map<Long, String> thumbnailMap = images.stream()
                .collect(Collectors.toMap(Image::getRelatedId, Image::getUrl));

        // 5. [Bulk Fetch] 좋아요 수 조회 (Group By)
        List<Object[]> likeCounts = likeRepository.countLikesByPostIds(postIds, LikeType.POST);
        Map<Long, Long> likeCountMap = likeCounts.stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        // 6. [Bulk Fetch] 댓글 수 조회 (Group By)
        List<Object[]> commentCounts = commentRepository.countCommentsByPostIds(postIds);
        Map<Long, Long> commentCountMap = commentCounts.stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        // 7. DTO 조립 (수정된 필드명 반영)
        List<UserProfilePostResponse> responseList = postSlice.getContent().stream()
                .map(post -> {
                    Long pid = post.getId();
                    return UserProfilePostResponse.builder()
                            .id(pid) // postId -> id
                            .contentPreview(post.getContent()) // content -> contentPreview
                            .contentImageUrl(thumbnailMap.get(pid)) // thumbnailUrl -> contentImageUrl
                            .likeCount(likeCountMap.getOrDefault(pid, 0L))
                            .commentCount(commentCountMap.getOrDefault(pid, 0L))
                            .createdAt(post.getCreatedAt())
                            .build();
                })
                .toList();

        return new SliceImpl<>(responseList, pageable, postSlice.hasNext());
    }
}
