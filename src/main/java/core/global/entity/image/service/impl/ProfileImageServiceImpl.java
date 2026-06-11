package core.global.entity.image.service.impl;

import core.global.entity.image.dto.ImageDto;
import core.global.entity.image.dto.ImageModerationEvent;
import core.global.entity.image.entity.Image;
import core.global.entity.image.repository.ImageRepository;
import core.global.entity.image.service.ImageCopyExecutor;
import core.global.entity.image.service.ImageOperationService;
import core.global.entity.image.service.ImageOperationRecoveryService;
import core.global.entity.image.service.ImageOperationStepService;
import core.global.entity.image.service.ImageStorageClient;
import core.global.entity.image.service.ProfileImageService;
import core.global.enums.ImageModerationStatus;
import core.global.enums.common.ImageOperationOwnerType;
import core.global.enums.common.ImageOperationType;
import core.global.enums.common.ImageType;
import core.global.enums.errorcode.ImageErrorCode;
import core.global.exception.BusinessException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static core.global.entity.image.utils.UrlUtil.buildCdnUrlFromKey;
import static core.global.entity.image.utils.UrlUtil.toKeyFromUrlOrKey;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileImageServiceImpl implements ProfileImageService {

    private static final long PROFILE_MAX_BYTES = 15L * 1024 * 1024;

    private final S3Client s3Client;
    private final ImageRepository imageRepository;
    private final ImageStorageClient storageClient;
    private final ApplicationEventPublisher eventPublisher;
    private final ImageOperationService imageOperationService;
    private final ImageOperationStepService imageOperationStepService;
    private final ImageOperationRecoveryService imageOperationRecoveryService;
    private final ImageCopyExecutor imageCopyExecutor;

    @Value("${ncp.s3.bucket}")
    private String bucket;
    @Value("${ncp.s3.endpoint}")
    private String endPoint;
    @Value("${cdn.base-url}")
    private String cdnBaseUrl;
    @Value("${image.cleanup.rabbit.enabled:false}")
    private boolean imageOperationRabbitEnabled;

    /**
     * 초기 셋업 시 유저 프로필 설정
     *
     * @param userId
     * @param requestedKeyOrUrl
     */
    @Transactional
    @Override
    public void saveUserProfileImage(Long userId, String requestedKeyOrUrl) {
        // 1) 입력 검증
        validateProfileInput(userId, requestedKeyOrUrl);

        // 2) URL/Key 판정 및 변환
        RequestInfo requestInfo = resolveRequestInfo(requestedKeyOrUrl);

        // 3) 기본이미지가 아니면 헤더 검사(용량 제한 포함)
        HeadObjectResponse sourceHead = validateImageHeadIfNecessary(requestInfo);

        // 5) 최종 후보 키/URL 계산 (버전드 키 전략)
        String candidateFinalKey = computeCandidateFinalKey(userId, requestInfo);
        Optional<Image> existingOpt =
                imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, userId);
        String oldKey = existingOpt
                .map(Image::getUrl)
                .filter(url -> !storageClient.isDefaultUrlOrKey(url))
                .map(url -> toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, url))
                .orElse(null);
        ImageOperationService.CopyOperationPlan copyPlan = null;
        String finalKey = candidateFinalKey;
        if (!requestInfo.isDefaultIncoming() && requestInfo.isStaging()) {
            copyPlan = copyProfileImage(
                    ImageOperationType.CREATE_USER_PROFILE_IMAGE,
                    userId,
                    requestInfo,
                    candidateFinalKey,
                    sourceHead
            );
            finalKey = copyPlan.targetKey();
        }

        Image targetImage;
        try {
            targetImage = upsertProfileImageEntityAndFlush(userId, ImageType.USER, existingOpt, finalKey);
        } catch (RuntimeException e) {
            if (copyPlan != null) {
                createCompensation(copyPlan, finalKey);
            }
            throw e;
        }

        scheduleCleanupAndRollbackCompensation(
                ImageOperationOwnerType.USER,
                userId,
                oldKey,
                requestInfo,
                finalKey,
                copyPlan
        );
        publishImageModerationEvent(finalKey, targetImage);
        log.info("[Profile Setup] 유저 프로필 이미지 저장 성공 - userId: {}, finalKey: {}", userId, finalKey);
    }

    /**
     * 프로필 수정 시 이미지 변경
     *
     * @param userId
     * @param requestedKeyOrUrl
     * @return
     */
    @Transactional
    @Override
    public String updateUserProfileImage(Long userId, String requestedKeyOrUrl) {
        log.info("[프로필 수정 시작] userId: {}, 요청값: {}", userId, requestedKeyOrUrl);

        // 1) 입력 검증
        validateProfileInput(userId, requestedKeyOrUrl);

        // 2) URL/Key 판정 및 변환
        RequestInfo requestInfo = resolveRequestInfo(requestedKeyOrUrl);
        log.info("[프로필 수정 - 정보 판정] default여부: {}, staging여부: {}, 추출된Key: {}",
                requestInfo.isDefaultIncoming(), requestInfo.isStaging(), requestInfo.getReqKey());

        // 3) 용량 및 헤더 검증
        HeadObjectResponse sourceHead = validateImageHeadIfNecessary(requestInfo);

        // 4) 기존 이미지 조회
        Optional<Image> existingOpt =
                imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, userId);
        log.info("[프로필 수정 - 기존 조회] 기존 이미지 존재 여부: {}", existingOpt.isPresent());

        // 5) 최종 후보 키/URL 계산
        String candidateFinalKey = computeCandidateFinalKey(userId, requestInfo);
        String candidateFinalUrl = buildCdnUrlFromKey(cdnBaseUrl, candidateFinalKey);
        log.info("[프로필 수정 - 후보 생성] 최종 저장 예정 Key: {}", candidateFinalKey);

        // 6) 동일 URL이면 no-op
        if (isNoOp(existingOpt, candidateFinalUrl)) {
            log.info("[프로필 수정 - 중단] 요청된 이미지가 기존 이미지와 동일합니다. 작업을 중단합니다.");
            return candidateFinalUrl;
        }

        ImageOperationService.CopyOperationPlan copyPlan = null;
        String finalKey = candidateFinalKey;
        String oldKey = existingOpt
                .map(Image::getUrl)
                .filter(url -> !storageClient.isDefaultUrlOrKey(url))
                .map(url -> toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, url))
                .orElse(null);
        if (!requestInfo.isDefaultIncoming() && requestInfo.isStaging()) {
            copyPlan = copyProfileImage(
                    ImageOperationType.UPDATE_USER_PROFILE_IMAGE,
                    userId,
                    requestInfo,
                    candidateFinalKey,
                    sourceHead
            );
            finalKey = copyPlan.targetKey();
        }

        String resultUrl;
        try {
            resultUrl = upsertProfileImageAndFlush(userId, ImageType.USER, existingOpt, finalKey);
        } catch (RuntimeException e) {
            if (copyPlan != null) {
                createCompensation(copyPlan, finalKey);
            }
            throw e;
        }

        scheduleCleanupAndRollbackCompensation(
                ImageOperationOwnerType.USER,
                userId,
                oldKey,
                requestInfo,
                finalKey,
                copyPlan
        );
        log.info("[프로필 수정 종료] 성공적으로 변경되었습니다. finalUrl: {}", resultUrl);

        return resultUrl;
    }

    @Override
    @Transactional
    public void deleteUserProfileImage(Long userId) {
        imageRepository.deleteByImageTypeAndRelatedId(ImageType.USER, userId);
        scheduleFolderCleanup(ImageOperationOwnerType.USER, userId, "users/%d/".formatted(userId));
    }

    @Override
    public String getUserProfileKey(Long userId) {
        return imageRepository
                .findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, userId)
                .map(Image::getUrl)
                .orElse(null);
    }

    /**
     * 초기 채팅방 생성 시 이미지 등록
     *
     * @param chatRoomId
     * @param requestedKeyOrUrl
     */
    @Transactional
    @Override
    public void saveChatRoomProfileImage(Long chatRoomId, String requestedKeyOrUrl) {
        // 1) 입력 검증
        validateProfileInput(chatRoomId, requestedKeyOrUrl);

        if (imageRepository.existsByImageTypeAndRelatedId(ImageType.CHAT_ROOM, chatRoomId)) {
            throw new BusinessException(ImageErrorCode.CHATROOM_IMAGES_ALREADY_EXIST);
        }

        // 2) URL/Key 판정 및 변환
        RequestInfo requestInfo = resolveRequestInfo(requestedKeyOrUrl);

        // 3) 기본이미지가 아니면 헤더 검사(용량 제한 포함)
        HeadObjectResponse sourceHead = validateImageHeadIfNecessary(requestInfo);

        // 5) 최종 후보 키/URL 계산
        String candidateFinalKey = computeChatRoomCandidateFinalKey(chatRoomId, requestInfo);

        ImageOperationService.CopyOperationPlan copyPlan = null;
        String finalKey = candidateFinalKey;
        if (!requestInfo.isDefaultIncoming() && requestInfo.isStaging()) {
            copyPlan = copyChatRoomProfileImage(
                    ImageOperationType.CREATE_CHAT_ROOM_PROFILE_IMAGE,
                    chatRoomId,
                    requestInfo,
                    candidateFinalKey,
                    sourceHead
            );
            finalKey = copyPlan.targetKey();
        }

        try {
            upsertProfileImageAndFlush(chatRoomId, ImageType.CHAT_ROOM, Optional.empty(), finalKey);
        } catch (RuntimeException e) {
            if (copyPlan != null) {
                createCompensation(copyPlan, finalKey);
            }
            throw e;
        }

        scheduleCleanupAndRollbackCompensation(
                ImageOperationOwnerType.CHAT_ROOM,
                chatRoomId,
                null,
                requestInfo,
                finalKey,
                copyPlan
        );
    }

    /**
     * 채팅방 이미지 수정 시 이미지 변경
     *
     * @param chatRoomId
     * @param requestedKeyOrUrl
     * @return
     */
    @Transactional
    @Override
    public String updateChatRoomProfileImage(Long chatRoomId, String requestedKeyOrUrl) {
        // 1) 입력 검증
        validateProfileInput(chatRoomId, requestedKeyOrUrl);

        // 2) URL/Key 판정 및 변환
        RequestInfo requestInfo = resolveRequestInfo(requestedKeyOrUrl);

        // 3) 기본이미지가 아니면 헤더 검사(용량 제한 포함)
        HeadObjectResponse sourceHead = validateImageHeadIfNecessary(requestInfo);

        // 4) 기존 이미지 조회
        Optional<Image> existingOpt =
                imageRepository.findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, chatRoomId);

        // 5) 최종 후보 키/URL 계산
        String candidateFinalKey = computeChatRoomCandidateFinalKey(chatRoomId, requestInfo);
        String candidateFinalUrl = buildCdnUrlFromKey(cdnBaseUrl, candidateFinalKey);

        // 6) 동일 URL이면 no-op
        if (isNoOp(existingOpt, candidateFinalUrl)) {
            return candidateFinalUrl;
        }

        String oldKey = existingOpt
                .map(Image::getUrl)
                .filter(url -> !storageClient.isDefaultUrlOrKey(url))
                .map(url -> toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, url))
                .orElse(null);
        ImageOperationService.CopyOperationPlan copyPlan = null;
        String finalKey = candidateFinalKey;
        if (!requestInfo.isDefaultIncoming() && requestInfo.isStaging()) {
            copyPlan = copyChatRoomProfileImage(
                    ImageOperationType.UPDATE_CHAT_ROOM_PROFILE_IMAGE,
                    chatRoomId,
                    requestInfo,
                    candidateFinalKey,
                    sourceHead
            );
            finalKey = copyPlan.targetKey();
        }

        String resultUrl;
        try {
            resultUrl = upsertProfileImageAndFlush(chatRoomId, ImageType.CHAT_ROOM, existingOpt, finalKey);
        } catch (RuntimeException e) {
            if (copyPlan != null) {
                createCompensation(copyPlan, finalKey);
            }
            throw e;
        }

        scheduleCleanupAndRollbackCompensation(
                ImageOperationOwnerType.CHAT_ROOM,
                chatRoomId,
                oldKey,
                requestInfo,
                finalKey,
                copyPlan
        );
        return resultUrl;
    }

    @Transactional
    @Override
    public void deleteChatRoomProfileImage(Long chatRoomId) {
        imageRepository.deleteByImageTypeAndRelatedId(ImageType.CHAT_ROOM, chatRoomId);
        scheduleFolderCleanup(ImageOperationOwnerType.CHAT_ROOM, chatRoomId, "chatRoom/%d/".formatted(chatRoomId));
    }

    @Override
    public String getRoomImageUrl(Long roomId) {
        return imageRepository
                .findFirstByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.CHAT_ROOM, roomId)
                .map(Image::getUrl)
                .orElse(null);
    }

    @Override
    public List<ImageDto> findImagesForChatRooms(List<Long> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Image> images = imageRepository.findAllByImageTypeAndRelatedIdIn(ImageType.CHAT_ROOM, roomIds);

        return images.stream()
                .map(image -> new ImageDto(image.getId(), image.getRelatedId(), image.getUrl()))
                .collect(Collectors.toList());
    }

    @Transactional
    @Override
    public void uploadUserProfileImage(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
        if (file.getSize() > PROFILE_MAX_BYTES) {
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }

        if (imageRepository.existsByImageTypeAndRelatedId(ImageType.USER, userId)) {
            throw new BusinessException(ImageErrorCode.USER_IMAGES_ALREADY_EXIST);
        }

        String originalFilename = file.getOriginalFilename();
        String ext = StringUtils.getFilenameExtension(originalFilename);
        if (ext == null) ext = "jpg";

        String uuid = UUID.randomUUID().toString().replace("-", "");

        String key = "users/%d/profile.%s.%s".formatted(userId, uuid, ext);

        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .acl(ObjectCannedACL.PUBLIC_READ)
                    .contentType(file.getContentType())
                    .cacheControl("public, max-age=31536000, immutable")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(file.getBytes()));

        } catch (Exception e) {
            log.error("Profile Image Direct Upload Failed userId={}", userId, e);
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }

        saveImageInDB(userId, ImageType.USER, key);
    }


    private void validateProfileInput(Long userId, String requestedKeyOrUrl) {
        log.info("[검증 - validateProfileInput] userId: {}, requestedKeyOrUrl: {}", userId, requestedKeyOrUrl);
        if (requestedKeyOrUrl == null || requestedKeyOrUrl.isBlank()) {
            log.warn("[UPI] fail.input_validation reason=null_or_blank userId={}", userId);
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
        log.info("[검증 성공] 입력값 유효함");
    }

    private RequestInfo resolveRequestInfo(String requestedKeyOrUrl) {
        log.info("[분석 - resolveRequestInfo] 분석 시작: {}", requestedKeyOrUrl);

        boolean isDefaultIncoming = storageClient.isDefaultUrlOrKey(requestedKeyOrUrl);
        String reqKey = toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, requestedKeyOrUrl);
        boolean reqIsStaging = storageClient.isStagingKey(reqKey);

        log.info("[분석 결과] default여부: {}, 추출된Key: {}, staging여부: {}", isDefaultIncoming, reqKey, reqIsStaging);
        return new RequestInfo(isDefaultIncoming, reqKey, reqIsStaging);
    }

    private String computeCandidateFinalKey(Long userId, RequestInfo requestInfo) {
        log.info("[계산 - computeCandidateFinalKey] 후보 키 계산 중... userId: {}", userId);
        String reqKey = requestInfo.getReqKey();

        if (!requestInfo.isDefaultIncoming() && requestInfo.isStaging()) {
            String versionedKey = buildVersionedProfileKey(userId, ImageType.USER, reqKey);
            log.info("[계산 결과] staging이므로 버전 키 생성: {}", versionedKey);
            return versionedKey;
        }

        log.info("[계산 결과] 기본이미지거나 이미 영구키이므로 유지: {}", reqKey);
        return reqKey;
    }

    private HeadObjectResponse validateImageHeadIfNecessary(RequestInfo requestInfo) {
        if (requestInfo.isDefaultIncoming()) {
            log.info("[헤더검사 - skip] 기본 이미지이므로 헤더 검사를 생략합니다.");
            return null;
        }
        log.info("[헤더검사 - 시작] Key: {}", requestInfo.getReqKey());
        return validateImageHeadOrThrow(requestInfo.getReqKey(), PROFILE_MAX_BYTES);
    }

    private HeadObjectResponse validateImageHeadOrThrow(String key, long maxBytes) {
        HeadObjectResponse head = storageClient.headObject(key);
        long size = head.contentLength();
        String ct = Optional.ofNullable(head.contentType()).orElse("").toLowerCase();

        log.info("[헤더검사 상세] size: {} bytes (제한: {}), contentType: {}", size, maxBytes, ct);

        if (size <= 0 || size > maxBytes) {
            log.info("[헤더검사 실패] 용량 부적합 (0 이하 또는 10MB 초과)");
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
        if (!ct.startsWith("image/")) {
            log.info("[헤더검사 실패] 이미지 타입이 아님: {}", ct);
            throw new BusinessException(ImageErrorCode.IMAGE_FILE_UPLOAD_TYPE_ERROR);
        }
        log.info("[헤더검사 성공]");
        return head;
    }

    private ImageOperationService.CopyOperationPlan copyProfileImage(
            ImageOperationType operationType,
            Long userId,
            RequestInfo requestInfo,
            String targetKey,
            HeadObjectResponse sourceHead
    ) {
        ImageOperationService.CopyOperationPlan plan = imageOperationService.createCopyOperation(
                operationType,
                ImageOperationOwnerType.USER,
                userId,
                requestInfo.getReqKey(),
                targetKey,
                sourceHead == null ? null : sourceHead.eTag(),
                sourceHead == null ? null : sourceHead.contentLength()
        );
        imageOperationService.markProcessing(plan.operationId());
        imageOperationStepService.markProcessing(plan.stepId());

        try {
            ImageCopyExecutor.ImageCopyResult result =
                    imageCopyExecutor.copyProfile(requestInfo.getReqKey(), plan.targetKey());
            imageOperationStepService.markCompleted(plan.stepId(), result.resultETag());
            return plan;
        } catch (RuntimeException e) {
            imageOperationStepService.markTerminalFailed(plan.stepId(), e.getMessage());
            imageOperationService.markFailed(plan.operationId());
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
    }

    private ImageOperationService.CopyOperationPlan copyChatRoomProfileImage(
            ImageOperationType operationType,
            Long chatRoomId,
            RequestInfo requestInfo,
            String targetKey,
            HeadObjectResponse sourceHead
    ) {
        ImageOperationService.CopyOperationPlan plan = imageOperationService.createCopyOperation(
                operationType,
                ImageOperationOwnerType.CHAT_ROOM,
                chatRoomId,
                requestInfo.getReqKey(),
                targetKey,
                sourceHead == null ? null : sourceHead.eTag(),
                sourceHead == null ? null : sourceHead.contentLength()
        );
        imageOperationService.markProcessing(plan.operationId());
        imageOperationStepService.markProcessing(plan.stepId());

        try {
            ImageCopyExecutor.ImageCopyResult result =
                    imageCopyExecutor.copy(requestInfo.getReqKey(), plan.targetKey());
            imageOperationStepService.markCompleted(plan.stepId(), result.resultETag());
            return plan;
        } catch (RuntimeException e) {
            imageOperationStepService.markTerminalFailed(plan.stepId(), e.getMessage());
            imageOperationService.markFailed(plan.operationId());
            throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
        }
    }

    private void scheduleCleanupAndRollbackCompensation(
            ImageOperationOwnerType ownerType,
            Long ownerId,
            String previousKey,
            RequestInfo requestInfo,
            String finalKey,
            ImageOperationService.CopyOperationPlan copyPlan
    ) {
        String oldKey = java.util.Objects.equals(previousKey, finalKey) ? null : previousKey;
        String stagingKey = requestInfo.isStaging() ? requestInfo.getReqKey() : null;
        List<String> cleanupKeys = java.util.stream.Stream.of(oldKey, stagingKey)
                .filter(java.util.Objects::nonNull)
                .filter(key -> !storageClient.isDefaultUrlOrKey(key))
                .distinct()
                .toList();

        if (TransactionSynchronizationManager.isSynchronizationActive() && copyPlan != null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        createCompensation(copyPlan, finalKey);
                    }
                }
            });
        }

        if (!imageOperationRabbitEnabled) {
            scheduleDirectCleanupFallback(cleanupKeys, copyPlan);
            return;
        }

        imageOperationRecoveryService.scheduleDeleteObjects(
                copyPlan == null ? null : copyPlan.operationId(),
                ownerType,
                ownerId,
                cleanupKeys
        );
    }

    private void scheduleDirectCleanupFallback(
            List<String> cleanupKeys,
            ImageOperationService.CopyOperationPlan copyPlan
    ) {
        Runnable cleanup = () -> {
            try {
                storageClient.deleteObjectsBulk(cleanupKeys);
            } catch (RuntimeException e) {
                log.error("[UPI] direct cleanup fallback failed keys={}", cleanupKeys, e);
            }
            if (copyPlan != null) {
                try {
                    imageOperationService.markCompleted(copyPlan.operationId());
                } catch (RuntimeException e) {
                    log.error("[UPI] operation completion record failed operationId={}",
                            copyPlan.operationId(), e);
                }
            }
        };

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }

    private String upsertProfileImageAndFlush(
            Long relatedId,
            ImageType imageType,
            Optional<Image> existingOpt,
            String finalKey
    ) {
        upsertProfileImageEntityAndFlush(relatedId, imageType, existingOpt, finalKey);
        return buildCdnUrlFromKey(cdnBaseUrl, finalKey);
    }

    private Image upsertProfileImageEntityAndFlush(
            Long relatedId,
            ImageType imageType,
            Optional<Image> existingOpt,
            String finalKey
    ) {
        String finalUrl = buildCdnUrlFromKey(cdnBaseUrl, finalKey);
        Image image;
        if (existingOpt.isPresent()) {
            image = existingOpt.get();
            image.updateUrl(finalUrl);
        } else {
            image = imageRepository.save(
                    Image.of(imageType, relatedId, finalUrl, 0, ImageModerationStatus.CLEAN, null)
            );
        }
        imageRepository.flush();
        return image;
    }

    private void scheduleFolderCleanup(
            ImageOperationOwnerType ownerType,
            Long ownerId,
            String folder
    ) {
        if (imageOperationRabbitEnabled) {
            imageOperationRecoveryService.scheduleDeleteFolder(ownerType, ownerId, folder);
            return;
        }

        Runnable cleanup = () -> {
            try {
                storageClient.deleteFolder(folder);
            } catch (RuntimeException e) {
                log.error("[ImageCleanup] direct folder cleanup fallback failed folder={}", folder, e);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanup.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanup.run();
            }
        });
    }

    private void createCompensation(
            ImageOperationService.CopyOperationPlan copyPlan,
            String finalKey
    ) {
        try {
            imageOperationRecoveryService.scheduleCompensation(copyPlan.operationId(), finalKey);
        } catch (RuntimeException compensationError) {
            log.error("[UPI] compensation record failed operationId={} targetKey={}",
                    copyPlan.operationId(), finalKey, compensationError);
        }
    }

    private boolean isNoOp(Optional<Image> existingOpt, String candidateFinalUrl) {
        if (existingOpt.isEmpty()) {
            log.info("[No-Op 검사] 기존 이미지가 없어 No-Op이 아닙니다.");
            return false;
        }
        String existingUrl = existingOpt.get().getUrl();
        boolean same = java.util.Objects.equals(existingUrl, candidateFinalUrl);
        log.info("[No-Op 검사] 기존URL: {}, 새URL: {}, 동일여부: {}", existingUrl, candidateFinalUrl, same);
        return same;
    }

    private void deleteOldS3ImageIfNecessary(Long userId, Optional<Image> existingOpt) {
        if (existingOpt.isEmpty()) {
            log.info("[S3삭제 - skip] 기존 이미지 정보가 DB에 없습니다.");
            return;
        }

        String oldUrl = existingOpt.get().getUrl();
        existingOpt.ifPresent(old -> {
            if (!storageClient.isDefaultUrlOrKey(oldUrl)) {
                try {
                    String oldKey = toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, oldUrl);
                    log.info("[S3삭제 - 실행] userId: {}, 삭제할 Key: {}", userId, oldKey);
                    s3Client.deleteObject(b -> b.bucket(bucket).key(oldKey));
                } catch (SdkException e) {
                    // 실패해도 치명적이지 않으므로 경고만
                    log.warn("[UPI] old_s3_delete_ignored userId={} url={} err={}", userId, old.getUrl(), e.getMessage());
                }
            }else {
                log.info("[S3삭제 - skip] 기존 이미지가 기본 이미지(default)이므로 삭제하지 않습니다. url: {}", oldUrl);
            }
        });
    }

    private String saveImageInDB(Long relatedId, ImageType imageType, String finalKey) {
        String finalUrl = buildCdnUrlFromKey(cdnBaseUrl, finalKey);
        log.info("[DB저장 - 시작] Type: {}, ID: {}, URL: {}", imageType, relatedId, finalUrl);

        Image image = Image.of(imageType, relatedId, finalUrl, 0, ImageModerationStatus.CLEAN, null);
        Image savedImage = imageRepository.save(image);

        log.info("[DB저장 - 완료] Image 엔티티 ID: {}", savedImage.getId());

        return finalUrl;
    }

    private void publishImageModerationEvent(String finalKey, Image savedImage) {
        if (!storageClient.isDefaultUrlOrKey(finalKey)) {
            log.info("[유해성검사] 이벤트 발행 시작. ID: {}, Key: {}", savedImage.getId(), finalKey);
            eventPublisher.publishEvent(new ImageModerationEvent(savedImage.getId(), finalKey));
        } else {
            log.info("[유해성검사 - skip] 기본 이미지이므로 검사 생략");
        }
    }

    private String computeChatRoomCandidateFinalKey(Long chatRoomId, RequestInfo requestInfo) {
        String reqKey = requestInfo.getReqKey();

        if (!requestInfo.isDefaultIncoming() && requestInfo.isStaging()) {
            return buildVersionedProfileKey(chatRoomId, ImageType.CHAT_ROOM, reqKey);
        }
        // default거나 이미 영구키면 그대로 사용
        return reqKey;
    }

    private void deleteOldS3ImageIfNecessaryForChatRoom(Long chatRoomId,
                                                        Optional<Image> existingOpt) {
        existingOpt.ifPresent(old -> {
            if (!storageClient.isDefaultUrlOrKey(old.getUrl())) {
                try {
                    String oldKey = toKeyFromUrlOrKey(endPoint, bucket, cdnBaseUrl, old.getUrl());
                    s3Client.deleteObject(b -> b.bucket(bucket).key(oldKey));
                } catch (SdkException e) {
                    log.warn("[CHAT_ROOM {}] old S3 delete ignored: {}", chatRoomId, e.getMessage());
                }
            } else {
                log.info("[CHAT_ROOM {}] old image is default - skip S3 delete", chatRoomId);
            }
        });
    }

    private String buildVersionedProfileKey(Long id, ImageType imageType, String reqKey) {
        String ext = storageClient.extOf(reqKey);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String key = (imageType == ImageType.USER)
                ? "users/%d/profile.%s.%s".formatted(id, uuid, ext)
                : "chatRoom/%d/chat_profile_%s.%s".formatted(id, uuid, ext);

        log.info("[버전 키 생성] id: {}, type: {}, 생성된 key: {}", id, imageType, key);
        return key;
    }

    private String moveStagingProfileIfNecessary(Long userId, RequestInfo requestInfo, String candidateFinalKey) {
        String reqKey = requestInfo.getReqKey();

        if (!requestInfo.isDefaultIncoming() && requestInfo.isStaging()) {
            String dstKey = candidateFinalKey;
            log.info("[S3이동 - 시작] userId: {}, src: {}, dst: {}", userId, reqKey, candidateFinalKey);
            try {
                // 메타데이터는 REPLACE하여 표준화(원치 않으면 COPY로 유지 가능)
                s3Client.copyObject(b -> b
                        .sourceBucket(bucket).sourceKey(reqKey)
                        .destinationBucket(bucket).destinationKey(dstKey)
                        .acl(ObjectCannedACL.PUBLIC_READ)
                        .metadataDirective(MetadataDirective.REPLACE)
                        .cacheControl("public, max-age=31536000, immutable"));
                s3Client.deleteObject(b -> b.bucket(bucket).key(reqKey));
                log.info("[S3이동 - 완료] 객체 이동 및 원본 staging 삭제 완료");
                return dstKey;
            } catch (SdkException e) {
                log.warn("[UPI] staging_move_failed userId={} src={} dst={} err={}", userId, reqKey, dstKey, e.getMessage());
                throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
            }
        }

        log.info("[S3이동 - skip] 이동 조건 미충족 (default 이미지거나 staging이 아님)");

        return candidateFinalKey;
    }

    private String moveChatRoomStagingIfNecessary(Long chatRoomId,
                                                  RequestInfo requestInfo,
                                                  String candidateFinalKey) {
        String reqKey = requestInfo.getReqKey();

        if (!requestInfo.isDefaultIncoming() && requestInfo.isStaging()) {
            try {
                s3Client.copyObject(b -> b
                        .sourceBucket(bucket).sourceKey(reqKey)
                        .destinationBucket(bucket).destinationKey(candidateFinalKey)
                        .acl(ObjectCannedACL.PUBLIC_READ)
                        .metadataDirective(MetadataDirective.COPY));
                s3Client.deleteObject(b -> b.bucket(bucket).key(reqKey));
                return candidateFinalKey;
            } catch (SdkException e) {
                throw new BusinessException(ImageErrorCode.IMAGE_UPLOAD_FAILED);
            }
        }

        // default거나 이미 영구키면 candidateFinalKey 그대로 사용
        return candidateFinalKey;
    }

    private static class RequestInfo {
        private final boolean defaultIncoming;
        private final String reqKey;
        private final boolean staging;

        private RequestInfo(boolean defaultIncoming, String reqKey, boolean staging) {
            this.defaultIncoming = defaultIncoming;
            this.reqKey = reqKey;
            this.staging = staging;
        }

        boolean isDefaultIncoming() {
            return defaultIncoming;
        }

        String getReqKey() {
            return reqKey;
        }

        boolean isStaging() {
            return staging;
        }
    }
}
